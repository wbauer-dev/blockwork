package app.indoortrain.data.strava

import app.indoortrain.data.BuildConfig
import app.indoortrain.data.export.FitExporter
import app.indoortrain.data.rides.RideRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

sealed interface StravaState {
    /** No client id/secret in secrets.properties. */
    data object NotConfigured : StravaState
    data object Disconnected : StravaState
    data class Connected(val athleteName: String?) : StravaState
}

@Singleton
class StravaRepository @Inject constructor(
    private val tokenStore: StravaTokenStore,
    private val rides: RideRepository,
) {
    private val configured = BuildConfig.STRAVA_CLIENT_ID.isNotBlank() && BuildConfig.STRAVA_CLIENT_SECRET.isNotBlank()
    private val client = StravaClient(BuildConfig.STRAVA_CLIENT_ID, BuildConfig.STRAVA_CLIENT_SECRET)
    private val tokenMutex = Mutex()

    val state: Flow<StravaState> =
        if (!configured) {
            flowOf(StravaState.NotConfigured)
        } else {
            tokenStore.tokens.map { t -> if (t == null) StravaState.Disconnected else StravaState.Connected(t.athleteName) }
        }

    private val _uploading = MutableStateFlow<Set<Long>>(emptySet())
    /** Ride ids currently being uploaded. */
    val uploading: StateFlow<Set<Long>> = _uploading.asStateFlow()

    /** Starts OAuth: returns the URL to open in the browser / Strava app. */
    suspend fun beginAuthorization(): String {
        check(configured) { "Strava is not configured" }
        val state = UUID.randomUUID().toString()
        tokenStore.setPendingState(state)
        return client.authorizeUrl(StravaClient.REDIRECT_URI, state)
    }

    /** Handles the redirect `indoortrain://localhost/strava?code=…&state=…&scope=…`. */
    suspend fun completeAuthorization(code: String?, state: String?, scope: String?, error: String?): Result<Unit> {
        val expected = tokenStore.pendingState()
        tokenStore.setPendingState(null)
        return when {
            error != null -> Result.failure(StravaException(if (error == "access_denied") "Access was not granted" else "Strava: $error"))
            code == null || state == null || state != expected -> Result.failure(StravaException("Login could not be verified – please try again"))
            scope?.contains("activity:write") != true -> Result.failure(StravaException("Please allow \"Upload your activities\" when connecting"))
            else -> runCatching {
                val t = client.exchangeCode(code)
                tokenStore.save(
                    StravaTokens(
                        t.accessToken, t.refreshToken, t.expiresAt, t.athlete?.id,
                        listOfNotNull(t.athlete?.firstname, t.athlete?.lastname).joinToString(" ").ifBlank { null },
                    ),
                )
            }
        }
    }

    suspend fun disconnect() {
        tokenStore.current()?.let { client.deauthorize(it.accessToken) }
        tokenStore.clear()
    }

    /** Uploads a ride (or resumes waiting for a pending upload). Returns the Strava activity id. */
    suspend fun upload(rideId: Long): Result<Long> {
        if (!_uploading.compareAndSetAdd(rideId)) return Result.failure(StravaException("Upload already running"))
        return try {
            runCatching { uploadInternal(rideId) }.onFailure { e ->
                if (e is StravaException) rides.ride(rideId)?.let { rides.setStrava(rideId, it.stravaUploadId, null, e.message) }
            }
        } finally {
            _uploading.update { it - rideId }
        }
    }

    private suspend fun uploadInternal(rideId: Long): Long {
        var ride = rides.ride(rideId) ?: throw StravaException("Ride not found")
        ride.stravaActivityId?.let { return it }
        ride = rides.recoverIfUnfinished(ride)
        val token = validAccessToken()

        val uploadId = if (ride.stravaUploadId != null && ride.stravaError == STILL_PROCESSING) {
            ride.stravaUploadId // resume polling instead of uploading twice
        } else {
            val samples = rides.samples(rideId)
            if (samples.isEmpty()) throw StravaException("Ride has no data")
            val fit = FitExporter.export(FitExporter.RideInfo(ride.startedAt, ride.ftp), samples)
            val description = listOfNotNull(
                "Indoor ERG workout · IndoorTrain",
                ride.normalizedPower?.let { "NP $it W" },
                ride.tss?.let { "TSS $it" },
            ).joinToString(" · ")
            val upload = client.upload(token, fit, ride.workoutName, description, "indoortrain-${ride.id}-${ride.startedAt}")
            finishedOrNull(rideId, upload)?.let { return it }
            rides.setStrava(rideId, upload.id, null, null)
            upload.id
        }

        repeat(POLL_ATTEMPTS) {
            delay(POLL_INTERVAL_MS)
            finishedOrNull(rideId, client.uploadStatus(token, uploadId))?.let { return it }
        }
        rides.setStrava(rideId, uploadId, null, STILL_PROCESSING)
        throw StravaException(STILL_PROCESSING)
    }

    /** Activity id when Strava is done, null while processing; throws on upload errors. */
    private suspend fun finishedOrNull(rideId: Long, upload: StravaUpload): Long? {
        StravaClient.duplicateActivityId(upload.error)?.let {
            rides.setStrava(rideId, upload.id, it, null)
            return it
        }
        if (upload.error != null) {
            rides.setStrava(rideId, null, null, upload.error)
            throw StravaException(upload.error)
        }
        upload.activityId?.let {
            rides.setStrava(rideId, upload.id, it, null)
            return it
        }
        return null
    }

    /** Refreshes the access token if it expires within a minute. */
    private suspend fun validAccessToken(): String = tokenMutex.withLock {
        val tokens = tokenStore.current() ?: throw StravaException("Not connected to Strava")
        if (tokens.expiresAt - 60 > System.currentTimeMillis() / 1000) return tokens.accessToken
        val refreshed = try {
            client.refresh(tokens.refreshToken)
        } catch (e: StravaException) {
            if (e.isAuthError) tokenStore.clear()
            throw e
        }
        tokenStore.save(tokens.copy(refreshed.accessToken, refreshed.refreshToken, refreshed.expiresAt))
        refreshed.accessToken
    }

    private fun MutableStateFlow<Set<Long>>.compareAndSetAdd(id: Long): Boolean {
        while (true) {
            val current = value
            if (id in current) return false
            if (compareAndSet(current, current + id)) return true
        }
    }

    companion object {
        const val STILL_PROCESSING = "Strava is still processing the upload – tap Upload again later"
        private const val POLL_ATTEMPTS = 30
        private const val POLL_INTERVAL_MS = 2_000L
    }
}
