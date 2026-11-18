package app.blockwork.data.rides

import app.blockwork.domain.plan.LinkedRide
import app.blockwork.domain.training.FtpCheck
import app.blockwork.domain.training.RideFingerprint
import app.blockwork.domain.training.RideProfile
import app.blockwork.domain.training.RideSample
import app.blockwork.domain.training.RideStats
import app.blockwork.domain.training.RideSummary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
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

    /** Finished rides never change, so their profiles are computed once per app run. */
    private val profileCache = ConcurrentHashMap<Long, RideProfile>()

    /**
     * Profiles of all finished rides for the history analyses, oldest first. Reads the samples of
     * rides not seen before; call off the main thread.
     */
    suspend fun profiles(zone: ZoneId = ZoneId.systemDefault()): List<RideProfile> {
        val rides = dao.finishedRidesSince(0)
        profileCache.keys.retainAll(rides.map { it.id }.toSet())
        return rides.map { ride ->
            profileCache.getOrPut(ride.id) {
                RideProfile.of(samples(ride.id), Instant.ofEpochMilli(ride.startedAt).atZone(zone).toLocalDate(), ride.ftp)
            }
        }
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
