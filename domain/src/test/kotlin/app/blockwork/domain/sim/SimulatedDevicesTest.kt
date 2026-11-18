package app.blockwork.domain.sim

import app.blockwork.domain.devices.ConnectionState
import app.blockwork.domain.devices.ControlResult
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.random.Random

@OptIn(ExperimentalCoroutinesApi::class)
class SimulatedDevicesTest {

    @Test fun trainerFollowsErgTarget() = runTest {
        val trainer = SimulatedSmartTrainer(backgroundScope, Random(1))
        assertThat(trainer.setTargetPower(250)).isEqualTo(ControlResult.NotConnected)

        trainer.connect("SIM")
        assertThat(trainer.setTargetPower(250)).isEqualTo(ControlResult.Success)
        advanceTimeBy(10_000)

        val sample = trainer.bikeData.value!!
        assertThat(sample.powerWatts!!).isIn(com.google.common.collect.Range.closed(240, 260))
        assertThat(sample.cadenceRpm!!).isWithin(3.0).of(90.0)
        assertThat(sample.speedKmh!!).isGreaterThan(20.0)
    }

    @Test fun heartRateRisesWithPowerAndClearsOnDisconnect() = runTest {
        var power = 0
        val hr = SimulatedHeartRateMonitor(backgroundScope, Random(1)) { power }
        hr.connect("SIM")
        advanceTimeBy(1_000)
        val resting = hr.heartRate.value!!.bpm

        power = 250
        advanceTimeBy(120_000)
        assertThat(hr.heartRate.value!!.bpm).isGreaterThan(resting + 60)

        hr.disconnect()
        assertThat(hr.heartRate.value).isNull()
        assertThat(hr.status.value.connection).isEqualTo(ConnectionState.Disconnected)
    }
}
