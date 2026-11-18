package app.blockwork.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.blockwork.domain.devices.DeviceKind
import app.blockwork.domain.devices.PairedDevice
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

enum class ThemeMode { System, Light, Dark }

/** Pages of the running-workout carousel, in swipe order (stored by name, so they can be reordered). */
enum class TrainingPage(val title: String) {
    Numbers("Big numbers"),
    Charts("Power and heart-rate charts"),
}

data class UserSettings(
    val ftpWatts: Int = DEFAULT_FTP,
    /** false until the user saved an FTP (or applied a ramp test result). */
    val ftpConfigured: Boolean = false,
    val maxHeartRate: Int? = null,
    val cueSound: Boolean = true,
    val cueVibration: Boolean = true,
    val cueVoice: Boolean = false,
    val theme: ThemeMode = ThemeMode.System,
    val onboardingDismissed: Boolean = false,
    val trainingPage: TrainingPage = TrainingPage.Numbers,
    /** Workout waits for the rider to pedal before it starts. */
    val autoStart: Boolean = false,
    /** Workout pauses when the rider stops pedaling and resumes when they pedal again. */
    val autoPause: Boolean = false,
) {
    companion object {
        const val DEFAULT_FTP = 200
        val FTP_RANGE = 50..800
    }
}

data class DeviceSettings(
    val heartRateMonitor: PairedDevice? = null,
    val trainer: PairedDevice? = null,
    val useSimulation: Boolean = false,
)

@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private object Keys {
        val FTP = intPreferencesKey("ftp_watts")
        val MAX_HR = intPreferencesKey("max_heart_rate")
        val USE_SIMULATION = booleanPreferencesKey("use_simulation")
        val CUE_SOUND = booleanPreferencesKey("cue_sound")
        val CUE_VIBRATION = booleanPreferencesKey("cue_vibration")
        val CUE_VOICE = booleanPreferencesKey("cue_voice")
        val THEME = stringPreferencesKey("theme")
        val ONBOARDING_DISMISSED = booleanPreferencesKey("onboarding_dismissed")
        val TRAINING_PAGE = stringPreferencesKey("training_page")
        val AUTO_START = booleanPreferencesKey("auto_start")
        val AUTO_PAUSE = booleanPreferencesKey("auto_pause")
        fun address(kind: DeviceKind) = stringPreferencesKey("${kind.name}_address")
        fun name(kind: DeviceKind) = stringPreferencesKey("${kind.name}_name")
    }

    private val store get() = context.settingsDataStore

    val settings: Flow<UserSettings> = store.data.map { p ->
        UserSettings(
            ftpWatts = p[Keys.FTP] ?: UserSettings.DEFAULT_FTP,
            ftpConfigured = p[Keys.FTP] != null,
            maxHeartRate = p[Keys.MAX_HR],
            cueSound = p[Keys.CUE_SOUND] ?: true,
            cueVibration = p[Keys.CUE_VIBRATION] ?: true,
            cueVoice = p[Keys.CUE_VOICE] ?: false,
            theme = p[Keys.THEME]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.System,
            onboardingDismissed = p[Keys.ONBOARDING_DISMISSED] ?: false,
            trainingPage = p[Keys.TRAINING_PAGE]?.let { runCatching { TrainingPage.valueOf(it) }.getOrNull() }
                ?: TrainingPage.Numbers,
            autoStart = p[Keys.AUTO_START] ?: false,
            autoPause = p[Keys.AUTO_PAUSE] ?: false,
        )
    }.distinctUntilChanged()

    val devices: Flow<DeviceSettings> = store.data.map { p ->
        fun paired(kind: DeviceKind) = p[Keys.address(kind)]?.let { PairedDevice(it, p[Keys.name(kind)], kind) }
        DeviceSettings(
            heartRateMonitor = paired(DeviceKind.HeartRateMonitor),
            trainer = paired(DeviceKind.Trainer),
            useSimulation = p[Keys.USE_SIMULATION] ?: false,
        )
    }.distinctUntilChanged()

    suspend fun setFtp(watts: Int) {
        require(watts in UserSettings.FTP_RANGE) { "FTP out of range: $watts" }
        store.edit { it[Keys.FTP] = watts }
    }

    suspend fun setMaxHeartRate(bpm: Int?) {
        store.edit { if (bpm == null) it.remove(Keys.MAX_HR) else it[Keys.MAX_HR] = bpm }
    }

    suspend fun setCues(sound: Boolean, vibration: Boolean, voice: Boolean) {
        store.edit {
            it[Keys.CUE_SOUND] = sound
            it[Keys.CUE_VIBRATION] = vibration
            it[Keys.CUE_VOICE] = voice
        }
    }

    suspend fun setAutoStart(enabled: Boolean) {
        store.edit { it[Keys.AUTO_START] = enabled }
    }

    suspend fun setAutoPause(enabled: Boolean) {
        store.edit { it[Keys.AUTO_PAUSE] = enabled }
    }

    suspend fun setTheme(mode: ThemeMode) {
        store.edit { it[Keys.THEME] = mode.name }
    }

    suspend fun setTrainingPage(page: TrainingPage) {
        store.edit { it[Keys.TRAINING_PAGE] = page.name }
    }

    suspend fun dismissOnboarding() {
        store.edit { it[Keys.ONBOARDING_DISMISSED] = true }
    }

    suspend fun setPairedDevice(device: PairedDevice) {
        store.edit {
            it[Keys.address(device.kind)] = device.address
            val name = device.name
            if (name != null) it[Keys.name(device.kind)] = name else it.remove(Keys.name(device.kind))
        }
    }

    suspend fun forgetDevice(kind: DeviceKind) {
        store.edit {
            it.remove(Keys.address(kind))
            it.remove(Keys.name(kind))
        }
    }

    suspend fun setUseSimulation(enabled: Boolean) {
        store.edit { it[Keys.USE_SIMULATION] = enabled }
    }
}
