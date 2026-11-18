package app.blockwork.domain.training

data class RideStart(
    val startedAtMillis: Long,
    val workoutId: String,
    val workoutName: String,
    val ftp: Int,
    /** Set when the workout was started from a training plan. */
    val plannedSessionId: String? = null,
    val isFtpTest: Boolean = false,
)

/** One beat-to-beat interval from the heart-rate strap. */
data class RrInterval(
    /** 0, 1, 2 … in arrival order within the ride. */
    val index: Int,
    /** Ride running seconds (as in [RideSample.elapsedSec]) completed when the interval arrived. */
    val elapsedSec: Int,
    val rrMs: Int,
)

/** Persists rides; implemented by the data layer. */
interface RideRecorder {
    suspend fun startRide(start: RideStart): Long

    suspend fun addSample(rideId: Long, sample: RideSample)

    suspend fun addRrIntervals(rideId: Long, intervals: List<RrInterval>)

    suspend fun finishRide(rideId: Long, summary: RideSummary, completed: Boolean)
}
