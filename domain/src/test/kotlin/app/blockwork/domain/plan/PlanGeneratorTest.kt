package app.blockwork.domain.plan

import app.blockwork.domain.Segment
import app.blockwork.domain.io.WorkoutValidator
import app.blockwork.domain.training.WorkoutMetrics
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.time.DayOfWeek
import java.time.DayOfWeek.FRIDAY
import java.time.DayOfWeek.MONDAY
import java.time.DayOfWeek.SATURDAY
import java.time.DayOfWeek.SUNDAY
import java.time.DayOfWeek.THURSDAY
import java.time.DayOfWeek.TUESDAY
import java.time.DayOfWeek.WEDNESDAY
import java.time.LocalDate

class PlanGeneratorTest {

    private val start = LocalDate.of(2026, 11, 18) // a Wednesday

    private fun request(
        goal: PlanGoal = PlanGoal.FtpBuild,
        weeks: Int = 8,
        days: Set<DayOfWeek> = setOf(TUESDAY, THURSDAY, SATURDAY, SUNDAY),
        minutes: Int = 60,
    ) = PlanRequest(goal, weeks, days, minutes, start)

    private val daySets = listOf(
        setOf(TUESDAY, SATURDAY),
        setOf(MONDAY, TUESDAY),
        setOf(MONDAY, WEDNESDAY, FRIDAY),
        setOf(TUESDAY, THURSDAY, SATURDAY, SUNDAY),
        setOf(MONDAY, TUESDAY, WEDNESDAY, THURSDAY),
        setOf(MONDAY, TUESDAY, THURSDAY, SATURDAY, SUNDAY),
        setOf(MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY, SATURDAY),
    )

    /** All combinations a user can pick, generated and checked against the rules. */
    private fun allPlans() = sequence {
        for (goal in PlanGoal.entries) for (weeks in PlanRequest.WEEKS) for (days in daySets) for (minutes in listOf(30, 60, 120)) {
            yield(PlanGenerator.generate(request(goal, weeks, days, minutes), "p") to request(goal, weeks, days, minutes))
        }
    }

    @Test fun weekStructure() {
        assertThat(PlanGenerator.weekKinds(4)).containsExactly(WeekKind.Build, WeekKind.Build, WeekKind.Build, WeekKind.Recovery).inOrder()
        for (weeks in PlanRequest.WEEKS) {
            val kinds = PlanGenerator.weekKinds(weeks)
            assertThat(kinds).hasSize(weeks)
            assertThat(kinds.last()).isEqualTo(WeekKind.Recovery)
            // build runs of 2–4 weeks between recovery weeks
            val runs = kinds.joinToString("") { if (it == WeekKind.Build) "B" else "R" }.split("R").dropLast(1)
            runs.forEach { assertThat(it.length).isIn(2..4) }
        }
    }

    @Test fun planStartsOnMondayAndHasDates() {
        val plan = PlanGenerator.generate(request(), "p1")
        assertThat(plan.startDate).isEqualTo(LocalDate.of(2026, 11, 16))
        val first = plan.weeks[0].sessions.first()
        assertThat(first.day).isEqualTo(TUESDAY)
        assertThat(plan.dateOf(first)).isEqualTo(LocalDate.of(2026, 11, 17))
        assertThat(plan.endDate).isEqualTo(LocalDate.of(2027, 1, 10))
        assertThat(plan.weekIndexOf(LocalDate.of(2026, 11, 23))).isEqualTo(1)
        assertThat(plan.weekIndexOf(LocalDate.of(2026, 11, 15))).isNull()
    }

    @Test fun rulesHoldForAllInputs() {
        var count = 0
        for ((plan, req) in allPlans()) {
            count++
            val label = "${req.goal} ${req.weeks}w ${req.trainingDays} ${req.sessionMinutes}min"
            // FTP test first in week 1 and last in every recovery week
            assertWithMessage(label).that(plan.weeks[0].sessions.first().type).isEqualTo(SessionType.FtpTest)
            plan.weeks.filter { it.kind == WeekKind.Recovery }.forEach {
                assertWithMessage(label).that(it.sessions.last().type).isEqualTo(SessionType.FtpTest)
                assertWithMessage(label).that(it.sessions.count { s -> s.type.hard }).isEqualTo(1)
            }
            plan.weeks.forEach { week ->
                assertWithMessage(label).that(req.trainingDays).containsAtLeastElementsIn(week.sessions.map { it.day })
                if (week.kind == WeekKind.Build) assertWithMessage(label).that(week.sessions).hasSize(req.trainingDays.size)
            }
            // no hard sessions on consecutive calendar days, across week boundaries too
            val hardDates = plan.sessions.filter { it.type.hard }.map { plan.dateOf(it) }.sorted()
            hardDates.zipWithNext().forEach { (a, b) -> assertWithMessage(label).that(b).isGreaterThan(a.plusDays(1)) }
            // all workouts valid, ids unique
            plan.sessions.forEach { WorkoutValidator.validate(it.workout) }
            assertWithMessage(label).that(plan.sessions.map { it.id }).containsNoDuplicates()
            // interval and endurance sessions last exactly the requested time
            plan.weeks.filter { it.kind == WeekKind.Build }.flatMap { it.sessions }
                .filter { it.type in setOf(
                    SessionType.Endurance, SessionType.SweetSpot, SessionType.Threshold, SessionType.Vo2Max, SessionType.Tempo,
                    SessionType.LongIntervals, SessionType.ShortIntervals,
                ) }
                .forEach { assertWithMessage("$label ${it.workout.name}").that(it.workout.durationSec).isEqualTo(req.sessionMinutes * 60) }
        }
        assertThat(count).isEqualTo(PlanGoal.entries.size * PlanRequest.WEEKS.count() * daySets.size * 3)
    }

    @Test fun recoveryWeeksAreLighter() {
        for ((plan, req) in allPlans()) {
            if (req.trainingDays.size < 3) continue
            val load = plan.weeks.map { w -> w.sessions.filter { it.type != SessionType.FtpTest }.sumOf { WorkoutMetrics.estimate(it.workout, 250).tss ?: 0 } }
            plan.weeks.forEachIndexed { i, w ->
                if (w.kind == WeekKind.Recovery && i > 0) {
                    assertWithMessage("${req.goal} ${req.trainingDays} week ${i + 1}").that(load[i].toDouble()).isAtMost(load[i - 1] * 0.7)
                }
            }
        }
    }

    @Test fun ftpBuildProgresses() {
        val plan = PlanGenerator.generate(request(weeks = 12, minutes = 90), "p")
        // work above 85 % FTP (seconds × fraction) per build week grows within each block
        fun work(week: PlanWeek) = week.sessions.filter { it.type.hard && it.type != SessionType.FtpTest }.sumOf { s ->
            s.workout.flatten().filter { it.fromFtp >= 0.85 }.sumOf { it.durationSec * it.fromFtp }
        }
        val builds = plan.weeks.filter { it.kind == WeekKind.Build }.map(::work)
        assertThat(builds.drop(1)).isNotEmpty()
        val firstBlock = plan.weeks.takeWhile { it.kind == WeekKind.Build }.drop(1).map(::work) // week 1 contains the test
        assertThat(firstBlock).isInOrder()
        // later blocks alternate VO2max and sweet spot next to threshold
        val laterBlocks = plan.weeks.dropWhile { it.kind == WeekKind.Build }.filter { it.kind == WeekKind.Build }
        assertThat(laterBlocks.flatMap { it.sessions }.map { it.type }).containsAtLeast(SessionType.Vo2Max, SessionType.SweetSpot, SessionType.Threshold)
        assertThat(plan.weeks.first().sessions.map { it.type }).doesNotContain(SessionType.Vo2Max)
    }

    @Test fun longRideOnWeekendAndGoalMix() {
        val plan = PlanGenerator.generate(request(goal = PlanGoal.BaseEndurance, days = setOf(TUESDAY, THURSDAY, SATURDAY)), "p")
        val week2 = plan.weeks[1].sessions
        assertThat(week2.single { it.type == SessionType.LongRide }.day).isEqualTo(SATURDAY)
        assertThat(week2.single { it.type == SessionType.LongRide }.workout.durationSec).isEqualTo(90 * 60)
        assertThat(week2.count { it.type.hard }).isEqualTo(1)
        assertThat(week2.single { it.type.hard }.type).isEqualTo(SessionType.Tempo)

        val vo2 = PlanGenerator.generate(request(goal = PlanGoal.Vo2Max), "p")
        assertThat(vo2.weeks[1].sessions.map { it.type }).contains(SessionType.Vo2Max)
    }

    @Test fun polarizedIsMostlyEasy() {
        for ((plan, req) in allPlans()) {
            if (req.goal != PlanGoal.Polarized) continue
            val label = "${req.weeks}w ${req.trainingDays} ${req.sessionMinutes}min"
            val types = plan.sessions.map { it.type }
            assertWithMessage(label).that(types).containsNoneOf(SessionType.Tempo, SessionType.SweetSpot, SessionType.Threshold)
            plan.weeks.forEach { week ->
                assertWithMessage(label).that(week.sessions.count { it.type.hard }).isAtMost(2)
            }
        }
        val plan = PlanGenerator.generate(request(goal = PlanGoal.Polarized, weeks = 12), "p")
        val types = plan.sessions.map { it.type }
        assertThat(types).containsAtLeast(SessionType.LongIntervals, SessionType.ShortIntervals, SessionType.Vo2Max)
        // four days a week: two hard, two easy in every build week
        plan.weeks.filter { it.kind == WeekKind.Build }.forEach { week ->
            assertThat(week.sessions.count { it.type.hard }).isEqualTo(2)
        }
    }

    @Test fun shortIntervalWorkoutShape() {
        val w = WorkoutTemplates.create(SessionType.ShortIntervals, level = 5, minutes = 75, id = "s", context = "Week 6.")
        assertThat(w.name).isEqualTo("30/15 intervals 3×13")
        assertThat(w.durationSec).isEqualTo(75 * 60)
        assertThat(w.description).contains("3 sets of 13 × (30 s at 120 % / 15 s easy), 3 min easy between sets.")
        assertThat(w.segments.filterIsInstance<Segment.Repeat>()).hasSize(3)
        // 30-min session leaves 17 min (1020 s): 2×9 = 18 × 45 s + 3 min set rest = 990 s fits, 2×10 does not
        val short = WorkoutTemplates.create(SessionType.ShortIntervals, level = 5, minutes = 30, id = "t", context = "")
        assertThat(short.durationSec).isEqualTo(1800)
        assertThat(short.name).isEqualTo("30/15 intervals 2×9")
        val long = WorkoutTemplates.create(SessionType.LongIntervals, level = 4, minutes = 60, id = "l", context = "")
        assertThat(long.name).isEqualTo("Long intervals 4×8")
        assertThat(long.durationSec).isEqualTo(3600)
    }

    @Test fun backToBackDaysGetOneHardSession() {
        val plan = PlanGenerator.generate(request(days = setOf(MONDAY, TUESDAY)), "p")
        plan.weeks.filter { it.kind == WeekKind.Build }.drop(1).forEach { assertThat(it.sessions.count { s -> s.type.hard }).isEqualTo(1) }
    }

    @Test fun intervalWorkoutShape() {
        val w = WorkoutTemplates.create(SessionType.SweetSpot, level = 2, minutes = 60, id = "x", context = "Week 3.")
        assertThat(w.name).isEqualTo("Sweet spot 3×12")
        assertThat(w.durationSec).isEqualTo(3600)
        assertThat(w.segments.first()).isInstanceOf(Segment.Ramp::class.java)
        assertThat(w.description).contains("3 × 12 min at 89 % FTP")
        // 3×12 + 2×5 min rest needs 46 min, 60-min session has 45: keep the most work (3×11, not 2×12)
        assertThat(WorkoutTemplates.create(SessionType.Threshold, level = 5, minutes = 60, id = "t", context = "").name)
            .isEqualTo("Threshold 3×11")
        val short = WorkoutTemplates.create(SessionType.Threshold, level = 8, minutes = 30, id = "y", context = "")
        assertThat(short.durationSec).isEqualTo(1800)
    }

    @Test fun deterministic() {
        assertThat(PlanGenerator.generate(request(), "same")).isEqualTo(PlanGenerator.generate(request(), "same"))
    }
}
