package app.indoortrain.data.strava

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

class StravaException(message: String, val httpCode: Int? = null, cause: Throwable? = null) : Exception(message, cause) {
    /** Token rejected – the user must connect again. */
    val isAuthError: Boolean get() = httpCode == 400 || httpCode == 401
}

@Serializable
data class StravaTokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("expires_at") val expiresAt: Long,
    val athlete: StravaAthlete? = null,
)

@Serializable
data class StravaAthlete(val id: Long, val firstname: String? = null, val lastname: String? = null)

@Serializable
data class StravaUpload(
    val id: Long,
    val error: String? = null,
    val status: String? = null,
    @SerialName("activity_id") val activityId: Long? = null,
)

/** Thin Strava API v3 client (OAuth token exchange, FIT upload). */
class StravaClient(
    private val clientId: String,
    private val clientSecret: String,
    private val http: OkHttpClient = OkHttpClient(),
    private val baseUrl: HttpUrl = "https://www.strava.com/".toHttpUrl(),
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** Mobile authorize URL: opens the Strava app if installed, otherwise the browser. */
    fun authorizeUrl(redirectUri: String, state: String): String = baseUrl.newBuilder()
        .addPathSegments("oauth/mobile/authorize")
        .addQueryParameter("client_id", clientId)
        .addQueryParameter("redirect_uri", redirectUri)
        .addQueryParameter("response_type", "code")
        .addQueryParameter("approval_prompt", "auto")
        .addQueryParameter("scope", SCOPE)
        .addQueryParameter("state", state)
        .build().toString()

    suspend fun exchangeCode(code: String): StravaTokenResponse = tokenRequest(
        FormBody.Builder()
            .add("client_id", clientId)
            .add("client_secret", clientSecret)
            .add("code", code)
            .add("grant_type", "authorization_code")
            .build(),
    )

    suspend fun refresh(refreshToken: String): StravaTokenResponse = tokenRequest(
        FormBody.Builder()
            .add("client_id", clientId)
            .add("client_secret", clientSecret)
            .add("refresh_token", refreshToken)
            .add("grant_type", "refresh_token")
            .build(),
    )

    suspend fun upload(
        accessToken: String,
        fit: ByteArray,
        name: String,
        description: String,
        externalId: String,
    ): StravaUpload {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", "$externalId.fit", fit.toRequestBody("application/octet-stream".toMediaType()))
            .addFormDataPart("data_type", "fit")
            .addFormDataPart("name", name)
            .addFormDataPart("description", description)
            .addFormDataPart("trainer", "1")
            .addFormDataPart("external_id", externalId)
            .build()
        return call(
            Request.Builder().url(baseUrl.resolve("api/v3/uploads")!!).bearer(accessToken).post(body).build(),
            StravaUpload.serializer(),
        )
    }

    suspend fun uploadStatus(accessToken: String, uploadId: Long): StravaUpload = call(
        Request.Builder().url(baseUrl.resolve("api/v3/uploads/$uploadId")!!).bearer(accessToken).get().build(),
        StravaUpload.serializer(),
    )

    suspend fun deauthorize(accessToken: String) {
        withContext(Dispatchers.IO) {
            val request = Request.Builder().url(baseUrl.resolve("oauth/deauthorize")!!)
                .post(FormBody.Builder().add("access_token", accessToken).build()).build()
            runCatching { http.newCall(request).execute().close() } // best effort
        }
    }

    private suspend fun tokenRequest(form: FormBody) = call(
        Request.Builder().url(baseUrl.resolve("oauth/token")!!).post(form).build(),
        StravaTokenResponse.serializer(),
    )

    private suspend fun <T> call(request: Request, serializer: kotlinx.serialization.KSerializer<T>): T =
        withContext(Dispatchers.IO) {
            try {
                http.newCall(request).execute().use { response ->
                    val text = response.body.string()
                    if (!response.isSuccessful) {
                        throw StravaException(errorMessage(response.code, text), response.code)
                    }
                    json.decodeFromString(serializer, text)
                }
            } catch (e: IOException) {
                throw StravaException("Network error: ${e.message ?: e.javaClass.simpleName}", cause = e)
            } catch (e: SerializationException) {
                throw StravaException("Unexpected response from Strava", cause = e)
            }
        }

    private fun errorMessage(code: Int, body: String): String {
        val message = runCatching { json.decodeFromString(ErrorBody.serializer(), body).message }.getOrNull()
        return when (code) {
            401 -> "Strava rejected the login – please connect again"
            429 -> "Strava rate limit reached – try again in 15 minutes"
            else -> "Strava error $code${message?.let { ": $it" } ?: ""}"
        }
    }

    private fun Request.Builder.bearer(token: String) = header("Authorization", "Bearer $token")

    @Serializable
    private data class ErrorBody(val message: String? = null)

    companion object {
        const val SCOPE = "read,activity:write"
        const val REDIRECT_URI = "indoortrain://localhost/strava"

        /** Strava reports duplicates as an error containing the existing activity link. */
        fun duplicateActivityId(error: String?): Long? =
            error?.let { Regex("activities/(\\d+)").find(it)?.groupValues?.get(1)?.toLongOrNull() }
                ?.takeIf { error.contains("duplicate", ignoreCase = true) }
    }
}
