package app.indoortrain.data.strava

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class StravaTokens(
    val accessToken: String,
    val refreshToken: String,
    val expiresAt: Long,
    val athleteId: Long? = null,
    val athleteName: String? = null,
)

private val Context.stravaDataStore: DataStore<Preferences> by preferencesDataStore(name = "strava")

/** Strava tokens, encrypted with an AES key that never leaves the Android Keystore. */
@Singleton
class StravaTokenStore @Inject constructor(@ApplicationContext private val context: Context) {
    private val tokensKey = stringPreferencesKey("tokens")
    private val pendingStateKey = stringPreferencesKey("oauth_state")
    private val json = Json { ignoreUnknownKeys = true }

    val tokens: Flow<StravaTokens?> = context.stravaDataStore.data.map { p -> p[tokensKey]?.let(::decrypt) }

    suspend fun current(): StravaTokens? = tokens.first()

    suspend fun save(tokens: StravaTokens) {
        val blob = encrypt(json.encodeToString(StravaTokens.serializer(), tokens))
        context.stravaDataStore.edit { it[tokensKey] = blob }
    }

    suspend fun clear() {
        context.stravaDataStore.edit { it.remove(tokensKey) }
    }

    suspend fun setPendingState(state: String?) {
        context.stravaDataStore.edit { if (state == null) it.remove(pendingStateKey) else it[pendingStateKey] = state }
    }

    suspend fun pendingState(): String? = context.stravaDataStore.data.first()[pendingStateKey]

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val out = cipher.iv + cipher.doFinal(plain.toByteArray())
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    private fun decrypt(blob: String): StravaTokens? = try {
        val data = Base64.decode(blob, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data, 0, IV_SIZE))
        }
        val plain = cipher.doFinal(data, IV_SIZE, data.size - IV_SIZE)
        json.decodeFromString(StravaTokens.serializer(), String(plain))
    } catch (e: Exception) {
        Log.w("StravaTokenStore", "Stored Strava tokens unreadable – reconnect needed", e)
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
        const val KEY_ALIAS = "indoortrain_strava_tokens"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
    }
}
