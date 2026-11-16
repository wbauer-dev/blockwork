package app.indoortrain.domain.training

import app.indoortrain.domain.Step
import app.indoortrain.domain.Workout
import app.indoortrain.domain.devices.ConnectionState
import app.indoortrain.domain.devices.ControlResult
import app.indoortrain.domain.devices.HeartRateMonitor
import app.indoortrain.domain.devices.SmartTrainer
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

/** Audio/haptic cue requested by the session. */
enum class Cue { StepStart, Countdown, Finished }

data class SessionState(
    val workoutId: String,
    val workoutName: String,
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
    val summary: RideSummary?,
    val completed: Boolean,
) {
    val currentStep: Step get() = steps[stepIndex]
    val nextStep: Step? get() = steps.getOrNull(stepIndex + 1)
}

/**
 * Runs one workout: ticks the [WorkoutEngine] once per second, sends ERG targets to the
 * trainer, records samples and publishes [state] and [cues].
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
) {
    private val engine = WorkoutEngine(workout, ftp)
    private val mutex = Mutex()
    private val samples = ArrayList<RideSample>()
    private val lastPowers = ArrayDeque<Int>()

    private var rideId: Long? = null
    private var lastSentTarget: Int? = null
    private var retryTargetAtSec = 0
    private var ticks = 0
    private var wasTrainerConnected = false
    private var controlProblem: ControlResult? = null
    private var summary: RideSummary? = null
    private var job: Job? = null

    private val _state = MutableStateFlow(snapshot())
    val state: StateFlow<SessionState> = _state.asStateFlow()

    private val _cues = MutableSharedFlow<Cue>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val cues: SharedFlow<Cue> = _cues.asSharedFlow()

    fun start() {
        if (job != null) return
        job = scope.launch {
            rideId = recorder.startRide(RideStart(wallClock(), workout.id, workout.name, ftp))
            mutex.withLock {
                handle(engine.start())
                updateTarget()
                publish()
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
            finish()
        }
    }

    fun pause() = command { engine.pause() }
    fun resume() = command { engine.resume() }
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
            )
            samples += sample
            rideId?.let { recorder.addSample(it, sample) }
        }
        handle(events)
        updateTarget()
        publish()
        return engine.phase == SessionPhase.Finished
    }

    private fun handle(events: List<EngineEvent>) {
        for (e in events) {
            when (e) {
                is EngineEvent.StepStarted -> _cues.tryEmit(Cue.StepStart)
                is EngineEvent.Countdown -> _cues.tryEmit(Cue.Countdown)
                is EngineEvent.Finished -> _cues.tryEmit(Cue.Finished)
            }
        }
    }

    /** Sends the target when it changed; re-sends after a reconnect; retries failures every 5 s. */
    private suspend fun updateTarget() {
        val connected = trainer.status.value.connection == ConnectionState.Connected
        if (connected && !wasTrainerConnected) lastSentTarget = null
        wasTrainerConnected = connected
        val target = engine.targetWatts ?: return
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
            workoutId = workout.id,
            workoutName = workout.name,
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
            nextStepWatts = next?.let { engine.wattsFor(it, 0) },
            intensity = engine.intensity,
            power3s = lastPowers.takeIf { it.isNotEmpty() && bike != null }?.average()?.roundToInt(),
            cadenceRpm = bike?.cadenceRpm?.roundToInt(),
            speedKmh = bike?.speedKmh,
            heartRateBpm = heartRateMonitor.heartRate.value?.bpm,
            trainerConnected = trainer.status.value.connection == ConnectionState.Connected,
            heartRateConnected = heartRateMonitor.status.value.connection == ConnectionState.Connected,
            controlProblem = controlProblem,
            powerHistory = samples.map { it.powerWatts },
            summary = summary,
            completed = engine.completed,
        )
    }
}
