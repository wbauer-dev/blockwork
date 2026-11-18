package app.blockwork.training

import android.content.Context
import app.blockwork.data.SettingsRepository
import app.blockwork.devices.DeviceManager
import app.blockwork.di.ApplicationScope
import app.blockwork.domain.Workout
import app.blockwork.domain.training.RideRecorder
import app.blockwork.domain.training.SessionPhase
import app.blockwork.domain.training.SessionState
import app.blockwork.domain.training.TrainingSession
import app.blockwork.upload.IntervalsUploads
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** Owns the (at most one) running training session; lives as long as the process. */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class TrainingController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val devices: DeviceManager,
    private val settings: SettingsRepository,
    private val recorder: RideRecorder,
    private val cuePlayer: CuePlayer,
    private val uploads: IntervalsUploads,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val session = MutableStateFlow<TrainingSession?>(null)
    private var cueJob: Job? = null

    /** State of the current session; null when no session exists. Finished sessions stay until [dismiss]. */
    val state: StateFlow<SessionState?> = session
        .flatMapLatest { it?.state ?: flowOf(null) }
        .stateIn(scope, SharingStarted.Eagerly, null)

    /** A session exists that has not finished (it may still be waiting for the rider to pedal). */
    val isActive: Boolean
        get() = state.value?.phase.let { it != null && it != SessionPhase.Finished }

    suspend fun start(workout: Workout, plannedSessionId: String? = null) {
        if (isActive) return
        val userSettings = settings.settings.first()
        val s = TrainingSession(
            workout = workout,
            ftp = userSettings.ftpWatts,
            trainer = devices.trainer.value,
            heartRateMonitor = devices.heartRateMonitor.value,
            recorder = recorder,
            scope = scope,
            plannedSessionId = plannedSessionId,
            autoStart = userSettings.autoStart,
            autoPause = userSettings.autoPause,
        )
        cueJob?.cancel()
        cuePlayer.prepare()
        cueJob = scope.launch { s.cues.collect(cuePlayer::play) }
        session.value = s
        TrainingService.start(context)
        s.start()
        // The summary is published only after the ride was saved, so the upload sees the final ride.
        scope.launch {
            val done = s.state.first { it.phase == SessionPhase.Finished && it.summary != null }
            val rideId = done.rideId ?: return@launch
            uploads.onRideFinished(rideId, done.summary!!.durationSec)
        }
    }

    fun pause() = session.value?.pause()
    fun resume() = session.value?.resume()
    fun skipStep() = session.value?.skipStep()
    fun extendStep() = session.value?.extendStep(60)
    fun increaseIntensity() = session.value?.increaseIntensity()
    fun decreaseIntensity() = session.value?.decreaseIntensity()
    fun stop() = session.value?.stop()

    /** Clears a finished session (after the summary was shown). */
    fun dismiss() {
        if (isActive) return
        cueJob?.cancel()
        cuePlayer.release()
        session.value = null
    }

}
