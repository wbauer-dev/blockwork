package app.blockwork.upload

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.blockwork.data.intervals.IntervalsAccountStore
import app.blockwork.data.intervals.IntervalsUploader
import app.blockwork.data.intervals.UploadOutcome
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Queues intervals.icu uploads as background work that waits for a network connection and retries. */
@Singleton
class IntervalsUploads @Inject constructor(
    @ApplicationContext private val context: Context,
    private val uploader: IntervalsUploader,
    private val account: IntervalsAccountStore,
) {
    /** Auto upload after a recorded ride, if connected and enabled; very short rides are skipped. */
    suspend fun onRideFinished(rideId: Long, durationSec: Int) {
        val a = account.account.first() ?: return
        if (!a.autoUpload || durationSec < MIN_AUTO_UPLOAD_SEC) return
        enqueue(rideId, manual = false)
    }

    /** A manual upload replaces any queued attempt for the ride, so it runs as soon as there is a network. */
    suspend fun enqueue(rideId: Long, manual: Boolean = true) {
        uploader.markPending(rideId)
        val request = OneTimeWorkRequestBuilder<IntervalsUploadWorker>()
            .setInputData(workDataOf(IntervalsUploadWorker.KEY_RIDE_ID to rideId))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "intervals-upload-$rideId",
            if (manual) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            request,
        )
    }

    companion object {
        const val MIN_AUTO_UPLOAD_SEC = 5 * 60
    }
}

class IntervalsUploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Dependencies {
        fun uploader(): IntervalsUploader
    }

    override suspend fun doWork(): Result {
        val rideId = inputData.getLong(KEY_RIDE_ID, -1).takeIf { it >= 0 } ?: return Result.failure()
        val uploader = EntryPointAccessors.fromApplication(applicationContext, Dependencies::class.java).uploader()
        return when (uploader.upload(rideId, lastAttempt = runAttemptCount >= MAX_ATTEMPTS - 1)) {
            UploadOutcome.Done -> Result.success()
            UploadOutcome.Retry -> Result.retry()
            UploadOutcome.Failed -> Result.failure()
        }
    }

    companion object {
        const val KEY_RIDE_ID = "rideId"
        /** With exponential backoff from 1 minute: gives up after about two hours. */
        const val MAX_ATTEMPTS = 8
    }
}
