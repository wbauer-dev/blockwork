package app.indoortrain.data.rides

import app.indoortrain.domain.training.RideSample
import app.indoortrain.domain.training.RideStats
import app.indoortrain.domain.training.RideSummary
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RideRepository @Inject constructor(private val dao: RideDao) {

    val rides: Flow<List<RideEntity>> = dao.observeRides()

    fun observe(id: Long): Flow<RideEntity?> = dao.observeRide(id)

    suspend fun ride(id: Long): RideEntity? = dao.ride(id)

    suspend fun samples(id: Long): List<RideSample> = dao.samples(id).map { it.toDomain() }

    /**
     * Rides interrupted by the app being killed have samples but no summary:
     * compute it from the samples and mark the ride finished (not completed).
     */
    suspend fun recoverIfUnfinished(ride: RideEntity): RideEntity {
        if (ride.finished) return ride
        val summary = RideStats.summarize(samples(ride.id), ride.ftp)
        val recovered = ride.withSummary(summary, completed = false)
        dao.updateRide(recovered)
        return recovered
    }

    suspend fun delete(id: Long) = dao.deleteRide(id)
}

internal fun RideSampleEntity.toDomain() = RideSample(
    elapsedSec = elapsedSec,
    timestampMillis = timestamp,
    powerWatts = power,
    cadenceRpm = cadence,
    speedKmh = speed,
    heartRateBpm = heartRate,
    targetWatts = targetPower,
    stepIndex = stepIndex,
)

internal fun RideEntity.withSummary(summary: RideSummary, completed: Boolean) = copy(
    finished = true,
    completed = completed,
    durationSec = summary.durationSec,
    avgPower = summary.avgPower,
    maxPower = summary.maxPower,
    normalizedPower = summary.normalizedPower,
    avgHeartRate = summary.avgHeartRate,
    maxHeartRate = summary.maxHeartRate,
    avgCadence = summary.avgCadence,
    energyKj = summary.energyKj,
    intensityFactor = summary.intensityFactor,
    tss = summary.tss,
    distanceMeters = summary.distanceMeters,
)
