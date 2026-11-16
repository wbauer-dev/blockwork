package app.indoortrain.domain.training

import app.indoortrain.domain.Workout
import kotlin.math.roundToInt

data class WorkoutEstimate(val normalizedPower: Int?, val intensityFactor: Double?, val tss: Int?)

/** Planned load of a workout if ridden exactly on target (free ride counted as 50 % FTP). */
object WorkoutMetrics {
    private const val FREE_RIDE_FTP = 0.5

    fun estimate(workout: Workout, ftp: Int): WorkoutEstimate {
        val perSecond = workout.flatten().flatMap { step ->
            (0 until step.durationSec).map { t ->
                val fraction = if (step.freeRide) FREE_RIDE_FTP else step.targetFtpAt(t)
                (fraction * ftp).roundToInt()
            }
        }
        val np = RideStats.normalizedPower(perSecond) ?: return WorkoutEstimate(null, null, null)
        val intensityFactor = (np.toDouble() / ftp * 100).roundToInt() / 100.0
        val tss = (perSecond.size * np * intensityFactor / (ftp * 3600.0) * 100).roundToInt()
        return WorkoutEstimate(np, intensityFactor, tss)
    }
}
