package app.indoortrain.domain.training

import app.indoortrain.domain.Segment
import app.indoortrain.domain.Workout
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class WorkoutEngineTest {

    private val workout = Workout(
        id = "t",
        name = "Test",
        segments = listOf(
            Segment.Steady(10, 0.5, "Warmup"),
            Segment.Repeat(2, listOf(Segment.Steady(5, 1.2, "On"), Segment.Steady(5, 0.5, "Off"))),
        ),
    )

    private fun engine() = WorkoutEngine(workout, ftp = 200)

    private fun WorkoutEngine.tickN(n: Int) = (1..n).flatMap { tick() }

    @Test fun startEmitsFirstStepAndTarget() {
        val e = engine()
        assertThat(e.targetWatts).isNull()
        assertThat(e.start()).containsExactly(EngineEvent.StepStarted(0, e.steps[0]))
        assertThat(e.phase).isEqualTo(SessionPhase.Running)
        assertThat(e.targetWatts).isEqualTo(100)
    }

    @Test fun countdownThenStepChange() {
        val e = engine().apply { start() }
        val events = e.tickN(10)
        assertThat(events).containsExactly(
            EngineEvent.Countdown(3),
            EngineEvent.Countdown(2),
            EngineEvent.Countdown(1),
            EngineEvent.StepStarted(1, e.steps[1]),
        ).inOrder()
        assertThat(e.targetWatts).isEqualTo(240)
        assertThat(e.stepRemainingSec).isEqualTo(5)
        assertThat(e.remainingSec).isEqualTo(20)
    }

    @Test fun runsToCompletion() {
        val e = engine().apply { start() }
        val events = e.tickN(30)
        assertThat(events.last()).isEqualTo(EngineEvent.Finished(completed = true))
        assertThat(e.phase).isEqualTo(SessionPhase.Finished)
        assertThat(e.completed).isTrue()
        assertThat(e.elapsedSec).isEqualTo(30)
        assertThat(e.tick()).isEmpty()
        assertThat(e.targetWatts).isNull()
    }

    @Test fun pauseFreezesTimeAndLowersTarget() {
        val e = engine().apply { start() }
        e.tickN(4)
        e.pause()
        assertThat(e.tickN(5)).isEmpty()
        assertThat(e.elapsedSec).isEqualTo(4)
        assertThat(e.targetWatts).isEqualTo(80) // 40 % FTP
        e.resume()
        e.tick()
        assertThat(e.elapsedSec).isEqualTo(5)
        assertThat(e.targetWatts).isEqualTo(100)
    }

    @Test fun skipShortensRiddenStepAndKeepsTimelineContiguous() {
        val e = engine().apply { start() }
        e.tickN(4)
        assertThat(e.skipStep()).containsExactly(EngineEvent.StepStarted(1, e.steps[1]))
        assertThat(e.steps[0].durationSec).isEqualTo(4)
        assertThat(e.steps[1].startSec).isEqualTo(4)
        assertThat(e.totalDurationSec).isEqualTo(24)
    }

    @Test fun extendMovesFollowingSteps() {
        val e = engine().apply { start() }
        e.tickN(2)
        e.extendStep(60)
        assertThat(e.stepRemainingSec).isEqualTo(68)
        assertThat(e.steps[1].startSec).isEqualTo(70)
        assertThat(e.totalDurationSec).isEqualTo(90)
    }

    @Test fun intensityScalesTargetsAndIsBounded() {
        val e = engine().apply { start() }
        e.adjustIntensity(WorkoutEngine.INTENSITY_STEP)
        assertThat(e.intensity).isEqualTo(1.05)
        assertThat(e.targetWatts).isEqualTo(105)
        repeat(20) { e.adjustIntensity(-WorkoutEngine.INTENSITY_STEP) }
        assertThat(e.intensity).isEqualTo(0.5)
    }

    @Test fun stopEndsIncomplete() {
        val e = engine().apply { start() }
        e.tickN(3)
        assertThat(e.stop()).containsExactly(EngineEvent.Finished(completed = false))
        assertThat(e.completed).isFalse()
        assertThat(e.stop()).isEmpty()
    }

    @Test fun builtInWorkoutsAreValid() {
        BuiltInWorkouts.all.forEach { w ->
            val e = WorkoutEngine(w, 250)
            assertThat(e.totalDurationSec).isEqualTo(w.durationSec)
            assertThat(BuiltInWorkouts.byId(w.id)).isSameInstanceAs(w)
        }
        assertThat(BuiltInWorkouts.all.map { it.id }).containsNoDuplicates()
    }
}

class FreeRideEngineTest {
    @Test fun freeRideHasNoTarget() {
        val w = app.indoortrain.domain.Workout(
            "f", "Free",
            segments = listOf(app.indoortrain.domain.Segment.FreeRide(5), app.indoortrain.domain.Segment.Steady(5, 1.0)),
        )
        val e = WorkoutEngine(w, 200).apply { start() }
        assertThat(e.targetWatts).isNull()
        assertThat(e.startWattsFor(e.steps[1])).isEqualTo(200)
        repeat(5) { e.tick() }
        assertThat(e.targetWatts).isEqualTo(200)
    }

    @Test fun rampTestIsMarked() {
        assertThat(BuiltInWorkouts.rampTest.isFtpTest).isTrue()
        assertThat(BuiltInWorkouts.rampTest.flatten().last().fromFtp).isWithin(1e-9).of(0.50 + 0.06 * 24)
    }
}

class AnnouncementTest {
    @Test fun announcesNextStepTenSecondsBefore() {
        val w = app.indoortrain.domain.Workout(
            "a", "Announce",
            segments = listOf(app.indoortrain.domain.Segment.Steady(30, 0.5), app.indoortrain.domain.Segment.Steady(60, 1.0, "Hard")),
        )
        val e = WorkoutEngine(w, 200).apply { start() }
        val events = (1..30).flatMap { e.tick() }
        val upcoming = events.filterIsInstance<EngineEvent.Upcoming>().single()
        assertThat(upcoming.index).isEqualTo(1)
        assertThat(upcoming.step.label).isEqualTo("Hard")
        // announcement comes before the 3-2-1 countdown
        assertThat(events.indexOf(upcoming)).isLessThan(events.indexOfFirst { it is EngineEvent.Countdown })
    }

    @Test fun heartRateZones() {
        val max = 190
        assertThat(app.indoortrain.domain.HeartRateZone.forBpm(100, max).number).isEqualTo(1)
        assertThat(app.indoortrain.domain.HeartRateZone.forBpm(114, max).number).isEqualTo(2) // 60 %
        assertThat(app.indoortrain.domain.HeartRateZone.forBpm(160, max).number).isEqualTo(4)
        assertThat(app.indoortrain.domain.HeartRateZone.forBpm(185, max).number).isEqualTo(5)
        assertThat(app.indoortrain.domain.HeartRateZone.lowerBounds(max)[app.indoortrain.domain.HeartRateZone.Z3]).isEqualTo(133)
    }
}
