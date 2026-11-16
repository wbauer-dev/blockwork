package app.indoortrain.domain.devices

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

private class FakeDevice(var failuresBeforeSuccess: Int = 0) : SensorDevice {
    override val status = MutableStateFlow(DeviceStatus())
    var connectCalls = 0
    var disconnectCalls = 0

    override suspend fun connect(address: String): Boolean {
        connectCalls++
        if (failuresBeforeSuccess > 0) {
            failuresBeforeSuccess--
            return false
        }
        status.value = DeviceStatus(ConnectionState.Connected, address)
        return true
    }

    override suspend fun disconnect() {
        disconnectCalls++
        status.value = status.value.copy(connection = ConnectionState.Disconnected)
    }

    fun linkLoss() {
        status.value = status.value.copy(connection = ConnectionState.Disconnected)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionSupervisorTest {

    @Test fun backoffDoublesAndIsCapped() {
        val b = Backoff(initialMillis = 1_000, maxMillis = 15_000)
        assertThat((0..5).map(b::delayFor)).containsExactly(1_000L, 2_000L, 4_000L, 8_000L, 15_000L, 15_000L).inOrder()
    }

    @Test fun retriesWithBackoffUntilConnected() = runTest {
        val device = FakeDevice(failuresBeforeSuccess = 2)
        val job = launch { ConnectionSupervisor(device).maintain("AA") }

        runCurrent()
        assertThat(device.connectCalls).isEqualTo(1)
        advanceTimeBy(1_001) // 1st backoff: 1 s
        assertThat(device.connectCalls).isEqualTo(2)
        advanceTimeBy(2_001) // 2nd backoff: 2 s
        assertThat(device.connectCalls).isEqualTo(3)
        assertThat(device.status.value.connection).isEqualTo(ConnectionState.Connected)

        job.cancel()
    }

    @Test fun reconnectsAfterLinkLossAndDisconnectsOnCancel() = runTest {
        val device = FakeDevice()
        val job = launch { ConnectionSupervisor(device).maintain("AA") }
        runCurrent()
        assertThat(device.connectCalls).isEqualTo(1)

        device.linkLoss()
        runCurrent()
        advanceTimeBy(1_001)
        assertThat(device.connectCalls).isEqualTo(2)
        assertThat(device.status.value.connection).isEqualTo(ConnectionState.Connected)

        job.cancel()
        job.join()
        assertThat(device.disconnectCalls).isEqualTo(1)
        assertThat(device.status.value.connection).isEqualTo(ConnectionState.Disconnected)
    }
}
