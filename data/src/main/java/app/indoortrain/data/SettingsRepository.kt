package app.indoortrain.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.indoortrain.domain.devices.DeviceKind
import app.indoortrain.domain.devices.PairedDevice
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

data class UserSettings(
    val ftpWatts: Int = DEFAULT_FTP,
    val maxHeartRate: Int? = null,
    val stravaAutoUpload: Boolean = false,
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
        val STRAVA_AUTO_UPLOAD = booleanPreferencesKey("strava_auto_upload")
        fun address(kind: DeviceKind) = stringPreferencesKey("${kind.name}_address")
        fun name(kind: DeviceKind) = stringPreferencesKey("${kind.name}_name")
    }

    private val store get() = context.settingsDataStore

    val settings: Flow<UserSettings> = store.data.map { p ->
        UserSettings(
            ftpWatts = p[Keys.FTP] ?: UserSettings.DEFAULT_FTP,
            maxHeartRate = p[Keys.MAX_HR],
            stravaAutoUpload = p[Keys.STRAVA_AUTO_UPLOAD] ?: false,
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

    suspend fun setStravaAutoUpload(enabled: Boolean) {
        store.edit { it[Keys.STRAVA_AUTO_UPLOAD] = enabled }
    }

    suspend fun setUseSimulation(enabled: Boolean) {
        store.edit { it[Keys.USE_SIMULATION] = enabled }
    }
}
