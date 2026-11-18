package app.blockwork.data.intervals

import app.blockwork.data.export.FitExporter
import app.blockwork.data.rides.RideDao
import app.blockwork.data.rides.RideRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

enum class IntervalsUploadState { Pending, Uploaded, Failed }

enum class UploadOutcome { Done, Retry, Failed }

/** Exports one ride as FIT and uploads it to intervals.icu, keeping the ride's upload status. */
@Singleton
class IntervalsUploader @Inject constructor(
    private val dao: RideDao,
    private val rides: RideRepository,
    private val account: IntervalsAccountStore,
    private val client: IntervalsClient,
) {
    suspend fun markPending(rideId: Long) {
        val ride = dao.ride(rideId) ?: return
        dao.setIntervalsStatus(rideId, IntervalsUploadState.Pending.name, ride.intervalsActivityId, null)
    }

    /** @param lastAttempt a retryable problem is final now (the background job gives up). */
    suspend fun upload(rideId: Long, lastAttempt: Boolean): UploadOutcome {
        val ride = dao.ride(rideId)?.let { rides.recoverIfUnfinished(it) } ?: return UploadOutcome.Failed // deleted
        suspend fun status(state: IntervalsUploadState, message: String?, activityId: String? = ride.intervalsActivityId) =
            dao.setIntervalsStatus(rideId, state.name, activityId, message)

        val apiKey = account.apiKey()
        if (apiKey == null) {
            status(IntervalsUploadState.Failed, "Not connected to intervals.icu – connect in Settings.")
            return UploadOutcome.Failed
        }
        val samples = rides.samples(rideId)
        if (samples.isEmpty()) {
            status(IntervalsUploadState.Failed, "This ride has no recorded data.")
            return UploadOutcome.Failed
        }
        val fit = withContext(Dispatchers.Default) {
            FitExporter.export(FitExporter.RideInfo(ride.startedAt, ride.ftp), samples, rides.rrIntervals(rideId))
        }
        val result = withContext(Dispatchers.IO) {
            client.uploadActivity(
                apiKey = apiKey,
                fit = fit,
                fileName = "blockwork-${ride.startedAt}.fit",
                name = ride.workoutName,
                externalId = "blockwork-${ride.startedAt}",
            )
        }
        return when (result) {
            is UploadResult.Created -> {
                status(IntervalsUploadState.Uploaded, null, result.activityId ?: ride.intervalsActivityId)
                UploadOutcome.Done
            }
            UploadResult.Duplicate -> {
                status(IntervalsUploadState.Uploaded, "Already on intervals.icu.")
                UploadOutcome.Done
            }
            UploadResult.KeyRejected -> {
                status(IntervalsUploadState.Failed, "intervals.icu rejected the API key – reconnect in Settings.")
                UploadOutcome.Failed
            }
            is UploadResult.Failed -> if (result.retryable && !lastAttempt) {
                status(IntervalsUploadState.Pending, "${result.message}. Retrying automatically.")
                UploadOutcome.Retry
            } else {
                status(IntervalsUploadState.Failed, result.message + ".")
                UploadOutcome.Failed
            }
        }
    }
}
