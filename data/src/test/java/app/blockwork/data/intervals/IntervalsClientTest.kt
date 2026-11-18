package app.blockwork.data.intervals

import com.google.common.truth.Truth.assertThat
import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Test
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.Base64

/** Talks to a local stub server; synthetic payloads only. */
class IntervalsClientTest {
    private class Captured(val method: String, val path: String, val query: String?, val headers: Map<String, String>, val body: ByteArray)

    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private var reply: Pair<Int, String> = 201 to "{}"
    private val requests = mutableListOf<Captured>()

    init {
        server.createContext("/") { ex ->
            requests += Captured(
                ex.requestMethod,
                ex.requestURI.path,
                ex.requestURI.rawQuery,
                ex.requestHeaders.mapValues { it.value.first() }.mapKeys { it.key.lowercase() },
                ex.requestBody.readBytes(),
            )
            val bytes = reply.second.toByteArray()
            ex.sendResponseHeaders(reply.first, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            if (bytes.isNotEmpty()) ex.responseBody.use { it.write(bytes) }
            ex.close()
        }
        server.start()
    }

    private val client = IntervalsClient("Blockwork/test", "http://127.0.0.1:${server.address.port}")

    @After fun stop() = server.stop(0)

    private val fit = byteArrayOf(14, 16, 1, 2, 3, 0, -1)

    private fun upload() = client.uploadActivity("secret-key", fit, "blockwork-1.fit", "Sweet Spot 4×10", "blockwork-1")

    @Test fun uploadsMultipartWithBasicAuthAndReadsActivityId() {
        reply = 201 to """{"icu_athlete_id":"i1","id":"u9","activities":[{"icu_athlete_id":"i1","id":"i555"}]}"""
        assertThat(upload()).isEqualTo(UploadResult.Created("i555"))

        val r = requests.single()
        assertThat(r.method).isEqualTo("POST")
        assertThat(r.path).isEqualTo("/api/v1/athlete/0/activities")
        val query = r.query!!.split("&").associate { it.substringBefore("=") to URLDecoder.decode(it.substringAfter("="), Charsets.UTF_8) }
        assertThat(query).containsEntry("name", "Sweet Spot 4×10")
        assertThat(query).containsEntry("external_id", "blockwork-1")
        val expectedAuth = "Basic " + Base64.getEncoder().encodeToString("API_KEY:secret-key".toByteArray())
        assertThat(r.headers["authorization"]).isEqualTo(expectedAuth)
        assertThat(r.headers["user-agent"]).isEqualTo("Blockwork/test")

        val boundary = r.headers["content-type"]!!.substringAfter("boundary=")
        val body = r.body
        val head = "--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"blockwork-1.fit\"\r\n" +
            "Content-Type: application/octet-stream\r\n\r\n"
        assertThat(String(body.copyOfRange(0, head.length))).isEqualTo(head)
        assertThat(body.copyOfRange(head.length, head.length + fit.size)).isEqualTo(fit)
        assertThat(String(body.copyOfRange(head.length + fit.size, body.size))).isEqualTo("\r\n--$boundary--\r\n")
    }

    @Test fun mapsStatusCodes() {
        reply = 200 to "{}"
        assertThat(upload()).isEqualTo(UploadResult.Duplicate)
        reply = 201 to "not json"
        assertThat(upload()).isEqualTo(UploadResult.Created(null))
        reply = 401 to ""
        assertThat(upload()).isEqualTo(UploadResult.KeyRejected)
        reply = 403 to "{}"
        assertThat(upload()).isEqualTo(UploadResult.KeyRejected)
        reply = 429 to ""
        assertThat((upload() as UploadResult.Failed).retryable).isTrue()
        reply = 503 to ""
        assertThat((upload() as UploadResult.Failed).retryable).isTrue()
        reply = 422 to "bad file"
        val failed = upload() as UploadResult.Failed
        assertThat(failed.retryable).isFalse()
        assertThat(failed.message).contains("422")
    }

    @Test fun connectionProblemIsRetryable() {
        server.stop(0)
        val r = upload() as UploadResult.Failed
        assertThat(r.retryable).isTrue()
    }

    @Test fun checksKeyAndReadsAthleteName() {
        reply = 200 to """{"id":"i1","name":"Test Rider","sportSettings":[]}"""
        assertThat(client.checkKey("k")).isEqualTo(KeyCheck.Valid("Test Rider"))
        assertThat(requests.last().method).isEqualTo("GET")
        assertThat(requests.last().path).isEqualTo("/api/v1/athlete/0")
        reply = 403 to ""
        assertThat(client.checkKey("k")).isEqualTo(KeyCheck.Rejected)
        reply = 500 to ""
        assertThat(client.checkKey("k")).isInstanceOf(KeyCheck.Failed::class.java)
    }
}
