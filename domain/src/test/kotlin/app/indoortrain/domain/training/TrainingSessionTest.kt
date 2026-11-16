package app.indoortrain.domain.training

import app.indoortrain.domain.Segment
import app.indoortrain.domain.Workout
import app.indoortrain.domain.sim.SimulatedHeartRateMonitor
import app.indoortrain.domain.sim.SimulatedSmartTrainer
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.random.Random

private class FakeRecorder : RideRecorder {
    val samples = mutableListOf<RideSample>()
    var finished: Pair<RideSummary, Boolean>? = null

    override suspend fun startRide(start: RideStart) = 1L
    override suspend fun addSample(rideId: Long, sample: RideSample) { samples += sample }
    override suspend fun finishRide(rideId: Long, summary: RideSummary, completed: Boolean) {
        finished = summary to completed
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class TrainingSessionTest {

    private val workout = Workout(
        id = "w",
        name = "Session test",
        segments = listOf(Segment.Steady(20, 0.5), Segment.Steady(20, 1.0)),
    )

    private inner class Rig(scope: TestScope) {
        val trainer = SimulatedSmartTrainer(scope.backgroundScope, Random(3))
        val hr = SimulatedHeartRateMonitor(scope.backgroundScope, Random(3)) { trainer.bikeData.value?.powerWatts }
        val recorder = FakeRecorder()
        val session = TrainingSession(
            workout, ftp = 200, trainer, hr, recorder, scope.backgroundScope,
            timeSource = scope.testScheduler.timeSource, wallClock = { scope.testScheduler.currentTime },
        )
    }

    @Test fun completesWorkoutControllingTrainerAndRecording() = runTest {
        val rig = Rig(this)
        rig.trainer.connect("SIM")
        rig.hr.connect("SIM")
        rig.session.start()
        runCurrent()
        assertThat(rig.trainer.targetWatts.value).isEqualTo(100)

        advanceTimeBy(25_000)
        assertThat(rig.trainer.targetWatts.value).isEqualTo(200)
        assertThat(rig.session.state.value.stepIndex).isEqualTo(1)

        advanceTimeBy(20_000)
        runCurrent()
        val state = rig.session.state.value
        assertThat(state.phase).isEqualTo(SessionPhase.Finished)
        assertThat(state.completed).isTrue()
        assertThat(rig.recorder.samples).hasSize(40)
        // live histories mirror the recorded samples, one entry per second
        assertThat(state.powerHistory).isEqualTo(rig.recorder.samples.map { it.powerWatts })
        assertThat(state.heartRateHistory).isEqualTo(rig.recorder.samples.map { it.heartRateBpm })
        assertThat(state.heartRateHistory.filterNotNull()).isNotEmpty()
        assertThat(rig.recorder.finished!!.second).isTrue()
        assertThat(rig.recorder.finished!!.first.avgHeartRate).isNotNull()
        assertThat(state.summary).isEqualTo(rig.recorder.finished!!.first)
        // control released at the end
        assertThat(rig.trainer.targetWatts.value).isNull()
    }

    @Test fun resendsTargetAfterTrainerReconnect() = runTest {
        val rig = Rig(this)
        rig.trainer.connect("SIM")
        rig.session.start()
        advanceTimeBy(3_000)
        assertThat(rig.trainer.targetWatts.value).isEqualTo(100)

        rig.trainer.disconnect() // simulator forgets the target, like a real trainer
        advanceTimeBy(2_000)
        assertThat(rig.session.state.value.trainerConnected).isFalse()
        assertThat(rig.session.state.value.phase).isEqualTo(SessionPhase.Running) // ride continues

        rig.trainer.connect("SIM")
        advanceTimeBy(1_100)
        assertThat(rig.trainer.targetWatts.value).isEqualTo(100)
        rig.session.stop()
        advanceTimeBy(2_000)
    }

    @Test fun pauseAndStopEarly() = runTest {
        val rig = Rig(this)
        rig.trainer.connect("SIM")
        rig.session.start()
        advanceTimeBy(5_000)
        rig.session.pause()
        advanceTimeBy(10_000)
        assertThat(rig.session.state.value.phase).isEqualTo(SessionPhase.Paused)
        assertThat(rig.session.state.value.elapsedSec).isEqualTo(5)
        assertThat(rig.trainer.targetWatts.value).isEqualTo(80)

        rig.session.stop()
        advanceTimeBy(2_000)
        assertThat(rig.session.state.value.phase).isEqualTo(SessionPhase.Finished)
        assertThat(rig.recorder.finished!!.second).isFalse()
        assertThat(rig.recorder.samples).hasSize(5)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class FreeRideSessionTest {
    @Test fun releasesTrainerDuringFreeRideAndRetakesControl() = runTest {
        val trainer = SimulatedSmartTrainer(backgroundScope, Random(1))
        val hr = SimulatedHeartRateMonitor(backgroundScope, Random(1)) { null }
        val workout = Workout(
            "fr", "Free ride test",
            segments = listOf(Segment.Steady(5, 0.5), Segment.FreeRide(5), Segment.Steady(5, 1.0)),
        )
        val session = TrainingSession(
            workout, 200, trainer, hr, FakeRecorder(), backgroundScope,
            timeSource = testScheduler.timeSource, wallClock = { testScheduler.currentTime },
        )
        trainer.connect("SIM")
        session.start()
        runCurrent()
        assertThat(trainer.targetWatts.value).isEqualTo(100)
        advanceTimeBy(6_000)
        assertThat(session.state.value.targetWatts).isNull()
        assertThat(trainer.targetWatts.value).isNull() // released
        advanceTimeBy(5_000)
        assertThat(trainer.targetWatts.value).isEqualTo(200)
        session.stop()
        advanceTimeBy(2_000)
    }
}
