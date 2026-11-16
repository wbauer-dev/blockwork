package app.indoortrain.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

data class UserSettings(
    val ftpWatts: Int = DEFAULT_FTP,
    val maxHeartRate: Int? = null,
) {
    companion object {
        const val DEFAULT_FTP = 200
    }
}

@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private object Keys {
        val FTP = intPreferencesKey("ftp_watts")
        val MAX_HR = intPreferencesKey("max_heart_rate")
    }

    val settings: Flow<UserSettings> = context.settingsDataStore.data.map { p ->
        UserSettings(
            ftpWatts = p[Keys.FTP] ?: UserSettings.DEFAULT_FTP,
            maxHeartRate = p[Keys.MAX_HR],
        )
    }

    suspend fun setFtp(watts: Int) {
        require(watts in 50..800) { "FTP out of range: $watts" }
        context.settingsDataStore.edit { it[Keys.FTP] = watts }
    }

    suspend fun setMaxHeartRate(bpm: Int?) {
        context.settingsDataStore.edit {
            if (bpm == null) it.remove(Keys.MAX_HR) else it[Keys.MAX_HR] = bpm
        }
    }
}
