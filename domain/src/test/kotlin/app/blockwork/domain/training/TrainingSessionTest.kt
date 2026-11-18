package app.blockwork.domain.training

import app.blockwork.domain.BikeSample
import app.blockwork.domain.Segment
import app.blockwork.domain.Workout
import app.blockwork.domain.sim.SimulatedHeartRateMonitor
import app.blockwork.domain.sim.SimulatedSmartTrainer
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
    val rr = mutableListOf<RrInterval>()
    var finished: Pair<RideSummary, Boolean>? = null
    var started: RideStart? = null

    override suspend fun startRide(start: RideStart): Long {
        check(started == null) { "ride started twice" }
        started = start
        return 1L
    }
    override suspend fun addSample(rideId: Long, sample: RideSample) { samples += sample }
    override suspend fun addRrIntervals(rideId: Long, intervals: List<RrInterval>) { rr += intervals }
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

    @Test fun recordsEveryRrIntervalWhileRunningOnly() = runTest {
        val rig = Rig(this)
        rig.trainer.connect("SIM")
        rig.hr.connect("SIM")
        rig.session.start()
        advanceTimeBy(10_500)
        rig.session.pause()
        advanceTimeBy(10_000)
        rig.session.resume()
        advanceTimeBy(5_000)
        rig.session.stop()
        advanceTimeBy(2_000)

        val rr = rig.recorder.rr
        // The simulated strap sends one interval per second; none are recorded during the pause.
        assertThat(rr.size).isAtLeast(13)
        assertThat(rr.size).isAtMost(16)
        assertThat(rr.map { it.index }).isEqualTo(rr.indices.toList())
        assertThat(rr.map { it.elapsedSec }).isInOrder()
        assertThat(rr.all { it.elapsedSec in 0..rig.recorder.samples.size }).isTrue()
        assertThat(rr.all { it.rrMs in 300..2000 }).isTrue()
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

@OptIn(ExperimentalCoroutinesApi::class)
class AutoStartPauseSessionTest {
    private val workout = Workout(
        "auto", "Auto start test",
        segments = listOf(Segment.Steady(60, 0.5), Segment.Steady(60, 1.0)),
    )

    private class Rig(scope: TestScope, workout: Workout, autoStart: Boolean, autoPause: Boolean) {
        val trainer = SimulatedSmartTrainer(scope.backgroundScope, Random(5))
        val hr = SimulatedHeartRateMonitor(scope.backgroundScope, Random(5)) { trainer.bikeData.value?.powerWatts }
        val recorder = FakeRecorder()
        val session = TrainingSession(
            workout, ftp = 200, trainer, hr, recorder, scope.backgroundScope,
            timeSource = scope.testScheduler.timeSource, wallClock = { scope.testScheduler.currentTime },
            autoStart = autoStart, autoPause = autoPause,
        )
        val state get() = session.state.value
    }

    @Test fun autoStartWaitsForPedalingThenStartsRide() = runTest {
        val rig = Rig(this, workout, autoStart = true, autoPause = false)
        rig.trainer.pedaling = false
        rig.trainer.connect("SIM")
        rig.session.start()
        advanceTimeBy(10_000)
        assertThat(rig.state.phase).isEqualTo(SessionPhase.Ready)
        assertThat(rig.state.waitingForPedaling).isTrue()
        assertThat(rig.state.elapsedSec).isEqualTo(0)
        assertThat(rig.recorder.started).isNull()
        assertThat(rig.recorder.samples).isEmpty()
        assertThat(rig.trainer.targetWatts.value).isNull() // no ERG while waiting

        rig.trainer.pedaling = true
        advanceTimeBy(2_500)
        assertThat(rig.state.phase).isEqualTo(SessionPhase.Running)
        assertThat(rig.state.waitingForPedaling).isFalse()
        assertThat(rig.recorder.started!!.startedAtMillis).isAtLeast(10_000L)
        assertThat(rig.trainer.targetWatts.value).isEqualTo(100)
        assertThat(rig.state.elapsedSec).isAtMost(2)
        rig.session.stop()
        advanceTimeBy(2_000)
    }

    @Test fun resumeStartsWaitingWorkoutByHand() = runTest {
        val rig = Rig(this, workout, autoStart = true, autoPause = false)
        rig.trainer.pedaling = false
        rig.trainer.connect("SIM")
        rig.session.start()
        advanceTimeBy(3_000)
        rig.session.resume()
        runCurrent()
        assertThat(rig.state.phase).isEqualTo(SessionPhase.Running)
        assertThat(rig.recorder.started).isNotNull()
        rig.session.stop()
        advanceTimeBy(2_000)
    }

    @Test fun stopWhileWaitingRecordsNothing() = runTest {
        val rig = Rig(this, workout, autoStart = true, autoPause = false)
        rig.trainer.pedaling = false
        rig.trainer.connect("SIM")
        rig.session.start()
        advanceTimeBy(3_000)
        rig.session.stop()
        advanceTimeBy(2_000)
        assertThat(rig.state.phase).isEqualTo(SessionPhase.Finished)
        assertThat(rig.state.rideId).isNull()
        assertThat(rig.recorder.started).isNull()
        assertThat(rig.recorder.finished).isNull()
    }

    @Test fun withoutAutoStartRunsImmediatelyEvenWhenNotPedaling() = runTest {
        val rig = Rig(this, workout, autoStart = false, autoPause = false)
        rig.trainer.pedaling = false
        rig.trainer.connect("SIM")
        rig.session.start()
        advanceTimeBy(10_000)
        runCurrent()
        assertThat(rig.state.phase).isEqualTo(SessionPhase.Running)
        assertThat(rig.state.elapsedSec).isEqualTo(10)
        rig.session.stop()
        advanceTimeBy(2_000)
    }

    @Test fun autoPausesWhenPedalingStopsAndResumesWhenItStarts() = runTest {
        val rig = Rig(this, workout, autoStart = false, autoPause = true)
        rig.trainer.connect("SIM")
        rig.session.start()
        advanceTimeBy(10_000)
        assertThat(rig.state.phase).isEqualTo(SessionPhase.Running)

        rig.trainer.pedaling = false
        advanceTimeBy(6_000)
        assertThat(rig.state.phase).isEqualTo(SessionPhase.Paused)
        assertThat(rig.state.autoPaused).isTrue()
        val pausedAt = rig.state.elapsedSec
        // pedaling stopped at ~9 s; three idle ticks later the clock stops
        assertThat(pausedAt).isIn(11..13)
        assertThat(rig.trainer.targetWatts.value).isEqualTo(80) // easy pause resistance

        advanceTimeBy(20_000)
        assertThat(rig.state.elapsedSec).isEqualTo(pausedAt) // clock stands still

        rig.trainer.pedaling = true
        advanceTimeBy(2_500)
        assertThat(rig.state.phase).isEqualTo(SessionPhase.Running)
        assertThat(rig.state.autoPaused).isFalse()
        assertThat(rig.trainer.targetWatts.value).isEqualTo(100)
        rig.session.stop()
        advanceTimeBy(2_000)
    }

    @Test fun manualPauseIsNotResumedByPedaling() = runTest {
        val rig = Rig(this, workout, autoStart = true, autoPause = true)
        rig.trainer.connect("SIM")
        rig.session.start()
        advanceTimeBy(5_000)
        assertThat(rig.state.phase).isEqualTo(SessionPhase.Running)
        rig.session.pause()
        advanceTimeBy(10_000) // still pedaling
        assertThat(rig.state.phase).isEqualTo(SessionPhase.Paused)
        assertThat(rig.state.autoPaused).isFalse()
        rig.session.stop()
        advanceTimeBy(2_000)
    }

    @Test fun noAutoPauseWhileTrainerDisconnected() = runTest {
        val rig = Rig(this, workout, autoStart = false, autoPause = true)
        rig.trainer.connect("SIM")
        rig.session.start()
        advanceTimeBy(3_000)
        rig.trainer.disconnect()
        advanceTimeBy(10_000)
        assertThat(rig.state.phase).isEqualTo(SessionPhase.Running)
        rig.session.stop()
        advanceTimeBy(2_000)
    }
}

class PedalingTest {
    @Test fun detectsFromCadenceThenPower() {
        assertThat(Pedaling.detect(null)).isNull()
        assertThat(Pedaling.detect(BikeSample())).isNull()
        assertThat(Pedaling.detect(BikeSample(powerWatts = 0, cadenceRpm = 85.5))).isTrue()
        assertThat(Pedaling.detect(BikeSample(powerWatts = 150, cadenceRpm = 0.0))).isFalse()
        assertThat(Pedaling.detect(BikeSample(cadenceRpm = 19.5))).isFalse()
        assertThat(Pedaling.detect(BikeSample(powerWatts = 120))).isTrue()
        assertThat(Pedaling.detect(BikeSample(powerWatts = 5))).isFalse()
    }
}
