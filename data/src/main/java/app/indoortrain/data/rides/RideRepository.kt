package app.indoortrain.data.rides

import app.indoortrain.domain.plan.LinkedRide
import app.indoortrain.domain.training.FtpCheck
import app.indoortrain.domain.training.RideFingerprint
import app.indoortrain.domain.training.RideSample
import app.indoortrain.domain.training.RideStats
import app.indoortrain.domain.training.RideSummary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RideRepository @Inject constructor(private val dao: RideDao) {

    val rides: Flow<List<RideEntity>> = dao.observeRides()

    fun observe(id: Long): Flow<RideEntity?> = dao.observeRide(id)

    /** Finished rides linked to training-plan sessions. */
    val planRides: Flow<List<LinkedRide>> = dao.observePlanRides().map { rows ->
        rows.mapNotNull { r ->
            r.plannedSessionId?.let { LinkedRide(it, r.id, r.durationSec ?: 0, r.completed) }
        }
    }

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

    /**
     * Fingerprints of the finished rides from [since] on, for [FtpCheck]. Loads their samples one
     * ride at a time; call off the main thread.
     */
    suspend fun fingerprintsSince(since: LocalDate, zone: ZoneId = ZoneId.systemDefault()): List<RideFingerprint> =
        dao.finishedRidesSince(since.atStartOfDay(zone).toInstant().toEpochMilli()).map { ride ->
            FtpCheck.fingerprint(
                samples = samples(ride.id),
                date = Instant.ofEpochMilli(ride.startedAt).atZone(zone).toLocalDate(),
                ftp = ride.ftp,
                isFtpTest = ride.isFtpTest,
                completed = ride.completed,
            )
        }

    suspend fun delete(id: Long) = dao.deleteRide(id)

    suspend fun deleteAll() = dao.deleteAll()
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
    intensity = intensity,
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
