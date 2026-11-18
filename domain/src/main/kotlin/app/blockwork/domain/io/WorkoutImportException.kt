package app.blockwork.domain.io

import app.blockwork.domain.Segment
import app.blockwork.domain.Workout

/** Import failed; [message] is shown to the user. */
class WorkoutImportException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Sanity limits for imported and edited workouts. */
object WorkoutValidator {
    const val MAX_DURATION_SEC = 6 * 3600
    const val MAX_FTP_FRACTION = 3.0
    private const val MAX_REPEAT = 100
    private const val MAX_DEPTH = 3

    /** Throws [WorkoutImportException] describing the first problem. */
    fun validate(workout: Workout) {
        if (workout.name.isBlank()) fail("Workout has no name")
        if (workout.segments.isEmpty()) fail("Workout has no intervals")
        workout.segments.forEach { check(it, 1) }
        val total = workout.durationSec
        if (total <= 0) fail("Workout has no duration")
        if (total > MAX_DURATION_SEC) fail("Workout is longer than 6 hours")
    }

    private fun check(segment: Segment, depth: Int) {
        if (depth > MAX_DEPTH) fail("Repeat blocks are nested too deeply")
        when (segment) {
            is Segment.Steady -> {
                duration(segment.durationSec)
                power(segment.targetFtp)
            }
            is Segment.Ramp -> {
                duration(segment.durationSec)
                power(segment.fromFtp)
                power(segment.toFtp)
            }
            is Segment.FreeRide -> duration(segment.durationSec)
            is Segment.Repeat -> {
                if (segment.times !in 1..MAX_REPEAT) fail("Repeat count must be 1–$MAX_REPEAT")
                if (segment.segments.isEmpty()) fail("Repeat block is empty")
                segment.segments.forEach { check(it, depth + 1) }
            }
        }
    }

    private fun duration(sec: Int) {
        if (sec <= 0) fail("Interval with zero or negative duration")
    }

    private fun power(ftpFraction: Double) {
        if (ftpFraction.isNaN() || ftpFraction < 0 || ftpFraction > MAX_FTP_FRACTION) {
            fail("Target power out of range (0–${(MAX_FTP_FRACTION * 100).toInt()} % FTP)")
        }
    }

    private fun fail(message: String): Nothing = throw WorkoutImportException(message)
}
