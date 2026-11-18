package app.blockwork.domain.training

import app.blockwork.domain.BikeSample
import app.blockwork.domain.Step
import app.blockwork.domain.Workout
import app.blockwork.domain.devices.ConnectionState
import app.blockwork.domain.devices.ControlResult
import app.blockwork.domain.devices.HeartRateMonitor
import app.blockwork.domain.devices.SmartTrainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/** Audio/haptic/voice cue requested by the session. */
sealed interface Cue {
    data class StepStart(val label: String?, val durationSec: Int, val targetWatts: Int?, val freeRide: Boolean) : Cue

    data class Upcoming(
        val label: String?,
        val durationSec: Int,
        val targetWatts: Int?,
        val freeRide: Boolean,
        val inSeconds: Int,
    ) : Cue

    data class Countdown(val secondsLeft: Int) : Cue

    data class Finished(val completed: Boolean) : Cue
}

data class SessionState(
    /** Database id of the recorded ride; null until recording started. */
    val rideId: Long?,
    val workoutId: String,
    val workoutName: String,
    val isFtpTest: Boolean,
    val ftp: Int,
    val phase: SessionPhase,
    val steps: List<Step>,
    val stepIndex: Int,
    val stepElapsedSec: Int,
    val stepRemainingSec: Int,
    val elapsedSec: Int,
    val remainingSec: Int,
    val totalDurationSec: Int,
    val targetWatts: Int?,
    val nextStepWatts: Int?,
    val intensity: Double,
    val power3s: Int?,
    val cadenceRpm: Int?,
    val speedKmh: Double?,
    val heartRateBpm: Int?,
    val trainerConnected: Boolean,
    val heartRateConnected: Boolean,
    /** Last control result if it was not successful (shown as a warning). */
    val controlProblem: ControlResult?,
    /** Recorded power per running second, for the chart. */
    val powerHistory: List<Int?>,
    /** Heart rate per recorded second (same indexing as [powerHistory]); null where no reading. */
    val heartRateHistory: List<Int?>,
    val summary: RideSummary?,
    val completed: Boolean,
    /** Auto start is on and the rider has not started pedaling yet (phase [SessionPhase.Ready]). */
    val waitingForPedaling: Boolean = false,
    /** Paused because the rider stopped pedaling; pedaling again resumes. */
    val autoPaused: Boolean = false,
) {
    val currentStep: Step get() = steps[stepIndex]
    val nextStep: Step? get() = steps.getOrNull(stepIndex + 1)
}

/**
 * Runs one workout: ticks the [WorkoutEngine] once per second, sends ERG targets to the
 * trainer, records samples and publishes [state] and [cues].
 *
 * With [autoStart] the workout (and the ride recording) only begins once the rider pedals;
 * [resume] starts it by hand. With [autoPause] it pauses after [Pedaling.AUTO_PAUSE_AFTER_SEC]
 * seconds without pedaling and resumes as soon as the rider pedals again. A pause the rider
 * chose is never resumed automatically.
 */
class TrainingSession(
    private val workout: Workout,
    private val ftp: Int,
    private val trainer: SmartTrainer,
    private val heartRateMonitor: HeartRateMonitor,
    private val recorder: RideRecorder,
    private val scope: CoroutineScope,
    private val timeSource: TimeSource = TimeSource.Monotonic,
    private val wallClock: () -> Long = System::currentTimeMillis,
    private val plannedSessionId: String? = null,
    private val autoStart: Boolean = false,
    private val autoPause: Boolean = false,
) {
    private val engine = WorkoutEngine(workout, ftp)
    private val mutex = Mutex()
    private val samples = ArrayList<RideSample>()
    /** RR intervals received since the last tick, written with the next sample. */
    private val pendingRr = ArrayList<RrInterval>()
    private var rrCount = 0
    private val lastPowers = ArrayDeque<Int>()

    private var rideId: Long? = null
    private var lastSentTarget: Int? = null
    private var retryTargetAtSec = 0
    private var ticks = 0
    private var wasTrainerConnected = false
    private var controlProblem: ControlResult? = null
    private var summary: RideSummary? = null
    private var job: Job? = null
    private var autoPaused = false
    private var notPedalingSec = 0

    private val _state = MutableStateFlow(snapshot())
    val state: StateFlow<SessionState> = _state.asStateFlow()

    private val _cues = MutableSharedFlow<Cue>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val cues: SharedFlow<Cue> = _cues.asSharedFlow()

    fun start() {
        if (job != null) return
        job = scope.launch {
            mutex.withLock {
                if (!autoStart) begin()
                updateTarget()
                publish()
            }
            val rrJob = launch {
                heartRateMonitor.rrIntervals.collect { rr -> mutex.withLock { onRrIntervals(rr) } }
            }
            val startMark = timeSource.markNow()
            var tickNo = 0L
            while (true) {
                tickNo++
                val wait = (tickNo * 1000).milliseconds - startMark.elapsedNow()
                if (wait.isPositive()) delay(wait)
                val finished = mutex.withLock { onTick() }
                if (finished) break
            }
            rrJob.cancel()
            finish()
        }
    }

    fun pause() = command {
        engine.pause()
        autoPaused = false
    }

    /** Resumes a pause, or starts a workout that is waiting for the rider to pedal. */
    fun resume() = command {
        if (engine.phase == SessionPhase.Ready) begin() else engine.resume()
        autoPaused = false
        notPedalingSec = 0
    }

    fun skipStep() = command { handle(engine.skipStep()) }
    fun extendStep(seconds: Int = 60) = command { engine.extendStep(seconds) }
    fun increaseIntensity() = command { engine.adjustIntensity(WorkoutEngine.INTENSITY_STEP) }
    fun decreaseIntensity() = command { engine.adjustIntensity(-WorkoutEngine.INTENSITY_STEP) }
    fun stop() = command { handle(engine.stop()) }

    private fun command(block: suspend () -> Unit) {
        scope.launch {
            mutex.withLock {
                block()
                updateTarget()
                publish()
            }
        }
    }

    /** @return true when the workout is finished. */
    private suspend fun onTick(): Boolean {
        ticks++
        val wasRunning = engine.phase == SessionPhase.Running
        val events = engine.tick()
        val bike = trainer.bikeData.value
        bike?.powerWatts?.let {
            lastPowers.addLast(it)
            while (lastPowers.size > 3) lastPowers.removeFirst()
        }
        if (wasRunning) {
            val sample = RideSample(
                elapsedSec = engine.elapsedSec,
                timestampMillis = wallClock(),
                powerWatts = bike?.powerWatts,
                cadenceRpm = bike?.cadenceRpm,
                speedKmh = bike?.speedKmh,
                heartRateBpm = heartRateMonitor.heartRate.value?.bpm,
                targetWatts = lastSentTarget,
                stepIndex = engine.stepIndex,
                intensity = engine.intensity,
            )
            samples += sample
            rideId?.let { recorder.addSample(it, sample) }
        }
        flushRrIntervals()
        handle(events)
        autoStartPause(bike)
        updateTarget()
        publish()
        return engine.phase == SessionPhase.Finished
    }

    /** Like the samples, RR intervals are kept only while the workout is running. */
    private fun onRrIntervals(rr: List<Int>) {
        if (engine.phase != SessionPhase.Running || rideId == null) return
        for (ms in rr) pendingRr += RrInterval(rrCount++, engine.elapsedSec, ms)
    }

    private suspend fun flushRrIntervals() {
        val id = rideId ?: return
        if (pendingRr.isEmpty()) return
        recorder.addRrIntervals(id, pendingRr.toList())
        pendingRr.clear()
    }

    /** Starts the ride and the engine; the ride's start time is when the rider actually begins. */
    private suspend fun begin() {
        if (engine.phase != SessionPhase.Ready) return
        rideId = recorder.startRide(RideStart(wallClock(), workout.id, workout.name, ftp, plannedSessionId, workout.isFtpTest))
        handle(engine.start())
    }

    /** Runs after the tick, so a workout started here gets its first full second on the next tick. */
    private suspend fun autoStartPause(bike: BikeSample?) {
        // No data (e.g. trainer disconnected): keep the current phase.
        val pedaling = Pedaling.detect(bike) ?: return
        when (engine.phase) {
            SessionPhase.Ready -> if (autoStart && pedaling) begin()
            SessionPhase.Running -> if (autoPause) {
                notPedalingSec = if (pedaling) 0 else notPedalingSec + 1
                if (notPedalingSec >= Pedaling.AUTO_PAUSE_AFTER_SEC) {
                    engine.pause()
                    autoPaused = true
                    notPedalingSec = 0
                }
            }
            SessionPhase.Paused -> if (autoPaused && pedaling) {
                engine.resume()
                autoPaused = false
            }
            SessionPhase.Finished -> Unit
        }
    }

    private fun handle(events: List<EngineEvent>) {
        for (e in events) {
            val cue = when (e) {
                is EngineEvent.StepStarted ->
                    Cue.StepStart(e.step.label, e.step.durationSec, engine.startWattsFor(e.step), e.step.freeRide)
                is EngineEvent.Upcoming -> Cue.Upcoming(
                    e.step.label, e.step.durationSec, engine.startWattsFor(e.step), e.step.freeRide,
                    WorkoutEngine.ANNOUNCE_SEC,
                )
                is EngineEvent.Countdown -> Cue.Countdown(e.secondsLeft)
                is EngineEvent.Finished -> Cue.Finished(e.completed)
            }
            _cues.tryEmit(cue)
        }
    }

    /** Sends the target when it changed; re-sends after a reconnect; retries failures every 5 s. */
    private suspend fun updateTarget() {
        val connected = trainer.status.value.connection == ConnectionState.Connected
        if (connected && !wasTrainerConnected) lastSentTarget = null
        wasTrainerConnected = connected
        val target = engine.targetWatts
        if (target == null) {
            // Free ride: hand resistance back to the rider once.
            if (engine.phase == SessionPhase.Running && connected && lastSentTarget != null) {
                trainer.releaseControl()
                lastSentTarget = null
            }
            return
        }
        if (!connected || target == lastSentTarget || ticks < retryTargetAtSec) return
        val result = trainer.setTargetPower(target)
        if (result == ControlResult.Success) {
            lastSentTarget = target
            controlProblem = null
        } else {
            controlProblem = result
            retryTargetAtSec = ticks + 5
        }
    }

    private suspend fun finish() {
        withContext(NonCancellable) {
            mutex.withLock {
                flushRrIntervals()
                val s = RideStats.summarize(samples, ftp)
                summary = s
                rideId?.let { recorder.finishRide(it, s, engine.completed) }
                publish()
            }
            trainer.releaseControl()
        }
    }

    private fun publish() {
        _state.value = snapshot()
    }

    private fun snapshot(): SessionState {
        val bike = trainer.bikeData.value
        val next = engine.nextStep
        return SessionState(
            rideId = rideId,
            workoutId = workout.id,
            workoutName = workout.name,
            isFtpTest = workout.isFtpTest,
            ftp = ftp,
            phase = engine.phase,
            steps = engine.steps,
            stepIndex = engine.stepIndex,
            stepElapsedSec = engine.stepElapsedSec,
            stepRemainingSec = engine.stepRemainingSec,
            elapsedSec = engine.elapsedSec,
            remainingSec = engine.remainingSec,
            totalDurationSec = engine.totalDurationSec,
            targetWatts = engine.targetWatts,
            nextStepWatts = next?.let { engine.startWattsFor(it) },
            intensity = engine.intensity,
            power3s = lastPowers.takeIf { it.isNotEmpty() && bike != null }?.average()?.roundToInt(),
            cadenceRpm = bike?.cadenceRpm?.roundToInt(),
            speedKmh = bike?.speedKmh,
            heartRateBpm = heartRateMonitor.heartRate.value?.bpm,
            trainerConnected = trainer.status.value.connection == ConnectionState.Connected,
            heartRateConnected = heartRateMonitor.status.value.connection == ConnectionState.Connected,
            controlProblem = controlProblem,
            powerHistory = samples.map { it.powerWatts },
            heartRateHistory = samples.map { it.heartRateBpm },
            summary = summary,
            completed = engine.completed,
            waitingForPedaling = autoStart && engine.phase == SessionPhase.Ready,
            autoPaused = autoPaused && engine.phase == SessionPhase.Paused,
        )
    }
}
