package app.indoortrain.domain.training

data class RideStart(
    val startedAtMillis: Long,
    val workoutId: String,
    val workoutName: String,
    val ftp: Int,
)

/** Persists rides; implemented by the data layer. */
interface RideRecorder {
    suspend fun startRide(start: RideStart): Long

    suspend fun addSample(rideId: Long, sample: RideSample)

    suspend fun finishRide(rideId: Long, summary: RideSummary, completed: Boolean)
}
