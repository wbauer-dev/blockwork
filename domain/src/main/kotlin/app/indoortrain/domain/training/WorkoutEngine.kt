package app.indoortrain.domain.training

import app.indoortrain.domain.Step
import app.indoortrain.domain.Workout
import kotlin.math.roundToInt

enum class SessionPhase { Ready, Running, Paused, Finished }

sealed interface EngineEvent {
    /** A new step started (also emitted for the first step). */
    data class StepStarted(val index: Int, val step: Step) : EngineEvent

    /** Seconds left in the current step (3, 2, 1). */
    data class Countdown(val secondsLeft: Int) : EngineEvent

    data class Finished(val completed: Boolean) : EngineEvent
}

/**
 * Deterministic workout state machine. Time only advances through [tick] (one call per
 * second while running), which makes it fully testable without clocks.
 * Not thread-safe; callers serialize access.
 */
class WorkoutEngine(val workout: Workout, val ftp: Int) {

    init {
        require(ftp > 0) { "FTP must be positive" }
        require(workout.durationSec > 0) { "Workout has no steps" }
    }

    var steps: List<Step> = workout.flatten().filter { it.durationSec > 0 }
        private set
    var phase = SessionPhase.Ready
        private set
    var stepIndex = 0
        private set

    /** Seconds elapsed in the current step. */
    var stepElapsedSec = 0
        private set

    /** Seconds spent running (pauses excluded). */
    var elapsedSec = 0
        private set

    /** Multiplier on all targets, adjusted in 5 % steps. */
    var intensity = 1.0
        private set

    var completed = false
        private set

    val currentStep: Step get() = steps[stepIndex]
    val nextStep: Step? get() = steps.getOrNull(stepIndex + 1)
    val stepRemainingSec: Int get() = currentStep.durationSec - stepElapsedSec
    val totalDurationSec: Int get() = steps.last().endSec
    val remainingSec: Int get() = (totalDurationSec - currentStep.startSec - stepElapsedSec).coerceAtLeast(0)

    /** ERG target for the trainer; null when nothing should be controlled. */
    val targetWatts: Int?
        get() = when (phase) {
            SessionPhase.Running -> wattsFor(currentStep, stepElapsedSec)
            SessionPhase.Paused -> (PAUSE_FTP_FRACTION * ftp).roundToInt()
            else -> null
        }

    fun wattsFor(step: Step, offsetSec: Int): Int = (step.targetFtpAt(offsetSec) * ftp * intensity).roundToInt()

    fun start(): List<EngineEvent> {
        if (phase != SessionPhase.Ready) return emptyList()
        phase = SessionPhase.Running
        return listOf(EngineEvent.StepStarted(0, currentStep))
    }

    fun tick(): List<EngineEvent> {
        if (phase != SessionPhase.Running) return emptyList()
        elapsedSec++
        stepElapsedSec++
        val remaining = stepRemainingSec
        return when {
            remaining <= 0 -> advance()
            remaining <= COUNTDOWN_SEC -> listOf(EngineEvent.Countdown(remaining))
            else -> emptyList()
        }
    }

    fun pause() {
        if (phase == SessionPhase.Running) phase = SessionPhase.Paused
    }

    fun resume() {
        if (phase == SessionPhase.Paused) phase = SessionPhase.Running
    }

    fun skipStep(): List<EngineEvent> =
        if (phase == SessionPhase.Running || phase == SessionPhase.Paused) advance() else emptyList()

    /** Makes the current step longer by [seconds]; following steps move back. */
    fun extendStep(seconds: Int) {
        if (phase == SessionPhase.Finished || seconds <= 0) return
        steps = steps.mapIndexed { i, s -> if (i == stepIndex) s.copy(durationSec = s.durationSec + seconds) else s }
            .rebased()
    }

    fun adjustIntensity(delta: Double) {
        intensity = ((intensity + delta) * 100).roundToInt().coerceIn(50, 150) / 100.0
    }

    /** Ends the workout early. */
    fun stop(): List<EngineEvent> {
        if (phase == SessionPhase.Finished) return emptyList()
        phase = SessionPhase.Finished
        return listOf(EngineEvent.Finished(completed = false))
    }

    private fun advance(): List<EngineEvent> {
        if (stepIndex >= steps.lastIndex) {
            phase = SessionPhase.Finished
            completed = true
            stepElapsedSec = currentStep.durationSec
            return listOf(EngineEvent.Finished(completed = true))
        }
        // When skipping, shorten the skipped step to what was ridden so the timeline stays truthful.
        if (stepElapsedSec < currentStep.durationSec) {
            steps = steps.mapIndexed { i, s -> if (i == stepIndex) s.copy(durationSec = stepElapsedSec) else s }
                .rebased()
        }
        stepIndex++
        stepElapsedSec = 0
        return listOf(EngineEvent.StepStarted(stepIndex, currentStep))
    }

    companion object {
        const val COUNTDOWN_SEC = 3
        const val INTENSITY_STEP = 0.05
        const val PAUSE_FTP_FRACTION = 0.4

        /** Recomputes start times so steps are contiguous. */
        fun List<Step>.rebased(): List<Step> {
            var t = 0
            return map { s -> s.copy(startSec = t).also { t += s.durationSec } }
        }
    }
}
