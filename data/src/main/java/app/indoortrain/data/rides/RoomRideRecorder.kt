package app.indoortrain.data.rides

import app.indoortrain.domain.training.RideRecorder
import app.indoortrain.domain.training.RideSample
import app.indoortrain.domain.training.RideStart
import app.indoortrain.domain.training.RideSummary
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoomRideRecorder @Inject constructor(private val dao: RideDao) : RideRecorder {

    override suspend fun startRide(start: RideStart): Long = dao.insertRide(
        RideEntity(
            startedAt = start.startedAtMillis,
            workoutId = start.workoutId,
            workoutName = start.workoutName,
            ftp = start.ftp,
        ),
    )

    override suspend fun addSample(rideId: Long, sample: RideSample) = dao.insertSample(
        RideSampleEntity(
            rideId = rideId,
            elapsedSec = sample.elapsedSec,
            timestamp = sample.timestampMillis,
            power = sample.powerWatts,
            cadence = sample.cadenceRpm,
            speed = sample.speedKmh,
            heartRate = sample.heartRateBpm,
            targetPower = sample.targetWatts,
            stepIndex = sample.stepIndex,
        ),
    )

    override suspend fun finishRide(rideId: Long, summary: RideSummary, completed: Boolean) {
        val ride = dao.ride(rideId) ?: return
        dao.updateRide(
            ride.copy(
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
            ),
        )
    }
}
