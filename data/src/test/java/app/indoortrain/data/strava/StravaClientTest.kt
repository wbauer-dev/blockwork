package app.indoortrain.data.strava

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

class StravaClientTest {
    private val server = MockWebServer()
    private lateinit var client: StravaClient

    @Before fun setUp() {
        server.start()
        client = StravaClient("123", "secret", baseUrl = server.url("/"))
    }

    @After fun tearDown() = server.close()

    @Test fun authorizeUrl() {
        val url = client.authorizeUrl(StravaClient.REDIRECT_URI, "abc").toHttpUrl()
        assertThat(url.encodedPath).isEqualTo("/oauth/mobile/authorize")
        assertThat(url.queryParameter("client_id")).isEqualTo("123")
        assertThat(url.queryParameter("redirect_uri")).isEqualTo("indoortrain://localhost/strava")
        assertThat(url.queryParameter("scope")).isEqualTo("read,activity:write")
        assertThat(url.queryParameter("state")).isEqualTo("abc")
    }

    @Test fun exchangeCode() = runTest {
        server.enqueue(
            MockResponse.Builder().body(
                """{"token_type":"Bearer","expires_at":1900000000,"expires_in":21600,"refresh_token":"r1",
                   "access_token":"a1","athlete":{"id":7,"firstname":"Test","lastname":"Rider","city":"x"}}""",
            ).build(),
        )
        val t = client.exchangeCode("the-code")
        assertThat(t.accessToken).isEqualTo("a1")
        assertThat(t.refreshToken).isEqualTo("r1")
        assertThat(t.expiresAt).isEqualTo(1_900_000_000L)
        assertThat(t.athlete?.firstname).isEqualTo("Test")

        val req = server.takeRequest()
        assertThat(req.method).isEqualTo("POST")
        assertThat(req.target).isEqualTo("/oauth/token")
        val form = req.body!!.utf8()
        assertThat(form).contains("code=the-code")
        assertThat(form).contains("grant_type=authorization_code")
        assertThat(form).contains("client_secret=secret")
    }

    @Test fun uploadSendsMultipartFit() = runTest {
        server.enqueue(MockResponse.Builder().code(201).body("""{"id":55,"status":"Your activity is still being processed.","error":null,"activity_id":null}""").build())
        val up = client.upload("tok", byteArrayOf(1, 2, 3), "4x4", "desc", "indoortrain-1-2")
        assertThat(up.id).isEqualTo(55)
        assertThat(up.activityId).isNull()

        val req = server.takeRequest()
        assertThat(req.target).isEqualTo("/api/v3/uploads")
        assertThat(req.headers["Authorization"]).isEqualTo("Bearer tok")
        val body = req.body!!.utf8()
        assertThat(body).contains("name=\"data_type\"")
        assertThat(body).contains("fit")
        assertThat(body).contains("name=\"trainer\"")
        assertThat(body).contains("filename=\"indoortrain-1-2.fit\"")
    }

    @Test fun errorsAreMapped() = runTest {
        server.enqueue(MockResponse.Builder().code(401).body("""{"message":"Authorization Error"}""").build())
        val e = assertThrows(StravaException::class.java) { kotlinx.coroutines.runBlocking { client.uploadStatus("t", 1) } }
        assertThat(e.isAuthError).isTrue()
        server.enqueue(MockResponse.Builder().code(500).body("""{"message":"boom"}""").build())
        val e2 = assertThrows(StravaException::class.java) { kotlinx.coroutines.runBlocking { client.uploadStatus("t", 1) } }
        assertThat(e2).hasMessageThat().isEqualTo("Strava error 500: boom")
    }

    @Test fun duplicateDetection() {
        assertThat(StravaClient.duplicateActivityId("ride.fit duplicate of <a href='/activities/987654'>Morning Ride</a>")).isEqualTo(987654L)
        assertThat(StravaClient.duplicateActivityId("Improperly formatted data")).isNull()
        assertThat(StravaClient.duplicateActivityId(null)).isNull()
    }
}
