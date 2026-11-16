package app.indoortrain.domain.devices

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.math.min

/** Exponential backoff for reconnect attempts. */
class Backoff(
    private val initialMillis: Long = 1_000,
    private val maxMillis: Long = 15_000,
) {
    fun delayFor(attempt: Int): Long = min(maxMillis, initialMillis shl attempt.coerceIn(0, 16))
}

/**
 * Keeps a [SensorDevice] connected: connects, waits for a disconnect, reconnects with
 * backoff. Runs until the calling coroutine is cancelled, then disconnects.
 */
class ConnectionSupervisor(
    private val device: SensorDevice,
    private val backoff: Backoff = Backoff(),
) {
    suspend fun maintain(address: String) {
        var failedAttempts = 0
        try {
            while (currentCoroutineContext().isActive) {
                val connected = device.connect(address) &&
                    device.status.value.connection == ConnectionState.Connected
                if (connected) {
                    failedAttempts = 0
                    device.status.first { it.connection == ConnectionState.Disconnected }
                    delay(backoff.delayFor(0))
                } else {
                    delay(backoff.delayFor(failedAttempts++))
                }
            }
        } finally {
            withContext(NonCancellable) { device.disconnect() }
        }
    }
}
