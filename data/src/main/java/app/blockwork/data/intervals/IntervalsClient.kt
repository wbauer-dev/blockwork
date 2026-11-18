package app.blockwork.data.intervals

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.util.Base64
import java.util.UUID

sealed interface UploadResult {
    /** [activityId] e.g. "i55751783"; null if the response did not name it. */
    data class Created(val activityId: String?) : UploadResult

    /** intervals.icu already has this file (it de-duplicates by file hash). */
    data object Duplicate : UploadResult

    data object KeyRejected : UploadResult

    data class Failed(val message: String, val retryable: Boolean) : UploadResult
}

sealed interface KeyCheck {
    data class Valid(val athleteName: String?) : KeyCheck
    data object Rejected : KeyCheck
    data class Failed(val message: String) : KeyCheck
}

/**
 * Minimal client for the intervals.icu REST API with a personal API key (HTTP Basic, user
 * "API_KEY"). Athlete id "0" stands for the owner of the key. Blocking; call on an IO thread.
 * Only rides are sent: nothing is read from intervals.icu except the athlete's name.
 */
class IntervalsClient(
    private val userAgent: String,
    private val baseUrl: String = "https://intervals.icu",
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** POST /api/v1/athlete/0/activities: 201 = created, 200 = all duplicates. */
    fun uploadActivity(apiKey: String, fit: ByteArray, fileName: String, name: String, externalId: String): UploadResult {
        val query = mapOf("name" to name, "external_id" to externalId, "device_name" to "Blockwork")
            .entries.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
        val boundary = "blockwork-" + UUID.randomUUID()
        val body = buildMultipart(boundary, fileName, fit)
        val response = try {
            request("POST", "/api/v1/athlete/0/activities?$query", apiKey, body, "multipart/form-data; boundary=$boundary")
        } catch (e: IOException) {
            return UploadResult.Failed("No connection to intervals.icu (${e.javaClass.simpleName})", retryable = true)
        }
        return when (response.code) {
            201 -> UploadResult.Created(activityId(response.body))
            200 -> UploadResult.Duplicate
            401, 403 -> UploadResult.KeyRejected
            429 -> UploadResult.Failed("intervals.icu rate limit reached", retryable = true)
            in 500..599 -> UploadResult.Failed("intervals.icu server error ${response.code}", retryable = true)
            else -> UploadResult.Failed("intervals.icu refused the file (HTTP ${response.code}) ${response.body.take(200)}".trim(), retryable = false)
        }
    }

    /** GET /api/v1/athlete/0: verifies the key and fetches the athlete's display name. */
    fun checkKey(apiKey: String): KeyCheck {
        val response = try {
            request("GET", "/api/v1/athlete/0", apiKey, null, null)
        } catch (e: IOException) {
            return KeyCheck.Failed("No connection to intervals.icu (${e.javaClass.simpleName})")
        }
        return when (response.code) {
            200 -> KeyCheck.Valid(runCatching { stringField(json.parseToJsonElement(response.body).jsonObject, "name") }.getOrNull())
            401, 403 -> KeyCheck.Rejected
            else -> KeyCheck.Failed("intervals.icu answered HTTP ${response.code}")
        }
    }

    private class Response(val code: Int, val body: String)

    private fun request(method: String, path: String, apiKey: String, body: ByteArray?, contentType: String?): Response {
        val conn = URI(baseUrl + path).toURL().openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            val auth = Base64.getEncoder().encodeToString("API_KEY:$apiKey".toByteArray())
            conn.setRequestProperty("Authorization", "Basic $auth")
            conn.setRequestProperty("User-Agent", userAgent)
            conn.setRequestProperty("Accept", "application/json")
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", contentType)
                conn.setFixedLengthStreamingMode(body.size)
                conn.outputStream.use { it.write(body) }
            }
            val code = conn.responseCode
            val stream = if (code >= 400) conn.errorStream else conn.inputStream
            return Response(code, stream?.use { String(it.readBytes()) }.orEmpty())
        } finally {
            conn.disconnect()
        }
    }

    private fun buildMultipart(boundary: String, fileName: String, data: ByteArray): ByteArray {
        val head = "--$boundary\r\n" +
            "Content-Disposition: form-data; name=\"file\"; filename=\"$fileName\"\r\n" +
            "Content-Type: application/octet-stream\r\n\r\n"
        val tail = "\r\n--$boundary--\r\n"
        return head.toByteArray() + data + tail.toByteArray()
    }

    private fun activityId(body: String): String? = runCatching {
        val obj = json.parseToJsonElement(body).jsonObject
        obj["activities"]?.jsonArray?.firstOrNull()?.jsonObject?.let { stringField(it, "id") }
    }.getOrNull()

    private fun stringField(obj: JsonObject, key: String): String? =
        obj[key]?.jsonPrimitive?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
}
