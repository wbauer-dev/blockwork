package app.indoortrain.training

import android.content.Context
import app.indoortrain.data.SettingsRepository
import app.indoortrain.data.strava.StravaRepository
import app.indoortrain.data.strava.StravaState
import app.indoortrain.devices.DeviceManager
import app.indoortrain.di.ApplicationScope
import app.indoortrain.domain.Workout
import app.indoortrain.domain.training.RideRecorder
import app.indoortrain.domain.training.SessionPhase
import app.indoortrain.domain.training.SessionState
import app.indoortrain.domain.training.TrainingSession
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
    private val strava: StravaRepository,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val session = MutableStateFlow<TrainingSession?>(null)
    private var cueJob: Job? = null

    /** State of the current session; null when no session exists. Finished sessions stay until [dismiss]. */
    val state: StateFlow<SessionState?> = session
        .flatMapLatest { it?.state ?: flowOf(null) }
        .stateIn(scope, SharingStarted.Eagerly, null)

    val isActive: Boolean
        get() = state.value?.phase.let { it == SessionPhase.Running || it == SessionPhase.Paused }

    suspend fun start(workout: Workout) {
        if (isActive) return
        val ftp = settings.settings.first().ftpWatts
        val s = TrainingSession(
            workout = workout,
            ftp = ftp,
            trainer = devices.trainer.value,
            heartRateMonitor = devices.heartRateMonitor.value,
            recorder = recorder,
            scope = scope,
        )
        cueJob?.cancel()
        cueJob = scope.launch { s.cues.collect(cuePlayer::play) }
        scope.launch { autoUploadWhenFinished(s) }
        session.value = s
        TrainingService.start(context)
        s.start()
    }

    fun pause() = session.value?.pause()
    fun resume() = session.value?.resume()
    fun skipStep() = session.value?.skipStep()
    fun extendStep() = session.value?.extendStep(60)
    fun increaseIntensity() = session.value?.increaseIntensity()
    fun decreaseIntensity() = session.value?.decreaseIntensity()
    fun stop() = session.value?.stop()

    private suspend fun autoUploadWhenFinished(s: TrainingSession) {
        val finished = s.state.first { it.phase == SessionPhase.Finished && it.summary != null }
        val rideId = finished.rideId ?: return
        val enabled = settings.settings.first().stravaAutoUpload
        val connected = strava.state.first() is StravaState.Connected
        // Skip very short rides (aborted starts).
        if (enabled && connected && (finished.summary?.durationSec ?: 0) >= MIN_UPLOAD_SEC) strava.upload(rideId)
    }

    /** Clears a finished session (after the summary was shown). */
    fun dismiss() {
        if (isActive) return
        cueJob?.cancel()
        session.value = null
    }

    private companion object {
        const val MIN_UPLOAD_SEC = 120
    }
}
