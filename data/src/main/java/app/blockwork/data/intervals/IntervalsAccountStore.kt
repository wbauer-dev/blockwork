package app.blockwork.data.intervals

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

private val Context.intervalsDataStore: DataStore<Preferences> by preferencesDataStore(name = "intervals")

/** What the UI shows about the intervals.icu connection; the key itself is never exposed. */
data class IntervalsAccount(val athleteName: String?, val autoUpload: Boolean)

/**
 * intervals.icu API key, encrypted with an AES key that never leaves the Android Keystore.
 * The DataStore file is excluded from Android backup (data_extraction_rules.xml).
 */
@Singleton
class IntervalsAccountStore @Inject constructor(@ApplicationContext private val context: Context) {
    private object Keys {
        val API_KEY = stringPreferencesKey("api_key")
        val ATHLETE_NAME = stringPreferencesKey("athlete_name")
        val AUTO_UPLOAD = booleanPreferencesKey("auto_upload")
    }

    private val store get() = context.intervalsDataStore

    /** null when not connected. */
    val account: Flow<IntervalsAccount?> = store.data.map { p ->
        if (p[Keys.API_KEY] == null) null else IntervalsAccount(p[Keys.ATHLETE_NAME], p[Keys.AUTO_UPLOAD] ?: true)
    }.distinctUntilChanged()

    /** The decrypted key; null when not connected or no longer readable (e.g. Keystore reset). */
    suspend fun apiKey(): String? = store.data.first()[Keys.API_KEY]?.let(::decrypt)

    suspend fun connect(apiKey: String, athleteName: String?) {
        val blob = encrypt(apiKey)
        store.edit {
            it[Keys.API_KEY] = blob
            if (athleteName != null) it[Keys.ATHLETE_NAME] = athleteName else it.remove(Keys.ATHLETE_NAME)
            it[Keys.AUTO_UPLOAD] = true
        }
    }

    suspend fun disconnect() {
        store.edit {
            it.remove(Keys.API_KEY)
            it.remove(Keys.ATHLETE_NAME)
            it.remove(Keys.AUTO_UPLOAD)
        }
    }

    suspend fun setAutoUpload(enabled: Boolean) {
        store.edit { it[Keys.AUTO_UPLOAD] = enabled }
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        return Base64.getEncoder().encodeToString(cipher.iv + cipher.doFinal(plain.toByteArray()))
    }

    private fun decrypt(blob: String): String? = try {
        val data = Base64.getDecoder().decode(blob)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data, 0, IV_SIZE))
        }
        String(cipher.doFinal(data, IV_SIZE, data.size - IV_SIZE))
    } catch (e: Exception) {
        Log.w("IntervalsAccountStore", "Stored API key unreadable – reconnect needed", e)
        null
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).apply {
            init(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
        }.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "blockwork_intervals_api_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
    }
}
