package app.blockwork.domain.plan

import app.blockwork.domain.io.PlanJson
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.DayOfWeek.MONDAY
import java.time.DayOfWeek.SATURDAY
import java.time.DayOfWeek.THURSDAY
import java.time.DayOfWeek.TUESDAY
import java.time.DayOfWeek.WEDNESDAY
import java.time.LocalDate
import app.blockwork.domain.plan.PlanEdit.Reason
import app.blockwork.domain.plan.WeekKind.Build
import app.blockwork.domain.plan.WeekKind.Recovery

/**
 * Plan used throughout (starts Mon 16 Nov 2026, ends Sun 13 Dec):
 * week 1–3 (build) Tue/Thu/Sat, week 4 (recovery) Tue 8 Dec / Thu 10 Dec.
 */
class PlanEditorTest {
    private val plan = PlanGenerator.generate(
        PlanRequest(PlanGoal.FtpBuild, 4, setOf(TUESDAY, THURSDAY, SATURDAY), 60, LocalDate.of(2026, 11, 16)),
        "p",
    )
    private val monday = LocalDate.of(2026, 11, 16)
    private fun day(offset: Int): LocalDate = monday.plusDays(offset.toLong())
    private fun done(id: String) = LinkedRide(id, rideId = 1, durationSec = 3600, completed = true)

    private fun PlanEdit.ok(): TrainingPlan {
        assertThat(this).isInstanceOf(PlanEdit.Ok::class.java)
        return (this as PlanEdit.Ok).plan
    }

    private fun TrainingPlan.dates(): Map<String, LocalDate> = sessions.associate { it.id to dateOf(it) }

    /** Week indices, weekdays and the one-session-per-day rule hold after every edit. */
    private fun assertConsistent(p: TrainingPlan) {
        assertThat(p.startDate.dayOfWeek).isEqualTo(MONDAY)
        p.weeks.forEachIndexed { i, w ->
            assertThat(w.index).isEqualTo(i)
            assertThat(w.sessions.all { it.week == i }).isTrue()
            assertThat(w.sessions.map { it.day }).isInOrder()
        }
        assertThat(p.sessions.map { p.dateOf(it) }).containsNoDuplicates()
        assertThat(p.sessions.map { it.id }).containsExactlyElementsIn(plan.sessions.map { it.id })
    }

    @Test fun moveWithinWeekKeepsIdAndWorkout() {
        val edited = PlanEditor.move(plan, "p-w1-thu", day(2), today = monday, rides = emptyList()).ok()
        assertConsistent(edited)
        val moved = edited.session("p-w1-thu")!!
        assertThat(moved.day).isEqualTo(WEDNESDAY)
        assertThat(edited.dateOf(moved)).isEqualTo(day(2))
        assertThat(moved.workout).isEqualTo(plan.session("p-w1-thu")!!.workout)
        assertThat(edited.dates() - "p-w1-thu").isEqualTo(plan.dates() - "p-w1-thu")
        assertThat(edited.weeks.map { it.kind }).isEqualTo(plan.weeks.map { it.kind })
    }

    @Test fun moveAcrossWeekBoundary() {
        val edited = PlanEditor.move(plan, "p-w1-sat", day(7), today = monday, rides = emptyList()).ok()
        assertConsistent(edited)
        assertThat(edited.weeks[0].sessions.map { it.id }).containsExactly("p-w1-tue", "p-w1-thu").inOrder()
        assertThat(edited.weeks[1].sessions.map { it.id }).containsExactly("p-w1-sat", "p-w2-tue", "p-w2-thu", "p-w2-sat").inOrder()
        assertThat(edited.session("p-w1-sat")!!.week).isEqualTo(1)
    }

    @Test fun missedSessionCanBeMovedToToday() {
        val thursday = day(3)
        val edited = PlanEditor.move(plan, "p-w1-tue", day(4), today = thursday, rides = emptyList()).ok()
        assertThat(edited.dateOf(edited.session("p-w1-tue")!!)).isEqualTo(day(4))
        assertThat(PlanProgress.status(edited, edited.session("p-w1-tue")!!, thursday, emptyList()))
            .isEqualTo(SessionStatus.Upcoming)
    }

    @Test fun moveRejections() {
        assertThat(PlanEditor.move(plan, "nope", day(2), monday, emptyList()))
            .isEqualTo(PlanEdit.Rejected(Reason.UnknownSession, "nope"))
        assertThat(PlanEditor.move(plan, "p-w1-thu", day(5), monday, emptyList()))
            .isEqualTo(PlanEdit.Rejected(Reason.DayTaken, "p-w1-sat"))
        assertThat(PlanEditor.move(plan, "p-w1-thu", day(2), today = day(3), rides = emptyList()))
            .isEqualTo(PlanEdit.Rejected(Reason.InThePast, "p-w1-thu"))
        assertThat(PlanEditor.move(plan, "p-w1-tue", day(4), today = day(3), rides = listOf(done("p-w1-tue"))))
            .isEqualTo(PlanEdit.Rejected(Reason.AlreadyDone, "p-w1-tue"))
        // Moving to its own day is a no-op, even in the past.
        assertThat(PlanEditor.move(plan, "p-w1-tue", day(1), today = day(3), rides = emptyList()).ok()).isSameInstanceAs(plan)
    }

    @Test fun partiallyRiddenSessionCanBeMoved() {
        val partial = LinkedRide("p-w1-thu", rideId = 7, durationSec = 300, completed = false)
        val edited = PlanEditor.move(plan, "p-w1-thu", day(4), today = day(3), rides = listOf(partial)).ok()
        assertThat(PlanProgress.status(edited, edited.session("p-w1-thu")!!, day(3), listOf(partial)))
            .isEqualTo(SessionStatus.Partial)
    }

    @Test fun moveBeyondEndAppendsWeekWithLastKind() {
        val edited = PlanEditor.move(plan, "p-w4-thu", LocalDate.of(2026, 12, 15), monday, emptyList()).ok()
        assertConsistent(edited)
        assertThat(edited.weeks.map { it.kind }).containsExactly(Build, Build, Build, Recovery, Recovery).inOrder()
        assertThat(edited.weeks[4].sessions.map { it.id }).containsExactly("p-w4-thu")
        assertThat(edited.endDate).isEqualTo(LocalDate.of(2026, 12, 20))
    }

    @Test fun moveBeforeStartPrependsBuildWeek() {
        val today = LocalDate.of(2026, 11, 12) // Thursday before the plan starts
        val edited = PlanEditor.move(plan, "p-w1-tue", LocalDate.of(2026, 11, 13), today, emptyList()).ok()
        assertConsistent(edited)
        assertThat(edited.startDate).isEqualTo(LocalDate.of(2026, 11, 9))
        assertThat(edited.weeks.map { it.kind }).containsExactly(Build, Build, Build, Build, Recovery).inOrder()
        assertThat(edited.dates() - "p-w1-tue").isEqualTo(plan.dates() - "p-w1-tue")
        assertThat(edited.endDate).isEqualTo(plan.endDate)
    }

    @Test fun moveEmptyingLastWeekShortensPlan() {
        // Both recovery-week sessions move into week 3 (Wed 2 Dec, Sun 6 Dec).
        val edited = PlanEditor.move(plan, "p-w4-tue", day(16), monday, emptyList())
            .ok().let { PlanEditor.move(it, "p-w4-thu", day(20), monday, emptyList()).ok() }
        assertConsistent(edited)
        assertThat(edited.weeks).hasSize(3)
        assertThat(edited.endDate).isEqualTo(day(20))
        assertThat(edited.weeks.map { it.kind }).isEqualTo(plan.weeks.take(3).map { it.kind })
    }

    @Test fun pauseAndBackRestoresPlan() {
        val paused = PlanEditor.shift(plan, day(7), 7, today = day(5), rides = emptyList()).ok()
        val back = PlanEditor.shift(paused, day(7), -7, today = day(5), rides = emptyList()).ok()
        assertThat(back.dates()).isEqualTo(plan.dates())
        assertThat(back.weeks.map { it.kind }).isEqualTo(plan.weeks.map { it.kind })
    }

    @Test fun swapExchangesDays() {
        val edited = PlanEditor.swap(plan, "p-w1-thu", "p-w2-tue", monday, emptyList()).ok()
        assertConsistent(edited)
        assertThat(edited.dateOf(edited.session("p-w1-thu")!!)).isEqualTo(day(8))
        assertThat(edited.dateOf(edited.session("p-w2-tue")!!)).isEqualTo(day(3))
    }

    @Test fun swapRejections() {
        assertThat(PlanEditor.swap(plan, "p-w1-tue", "p-w1-thu", today = day(3), rides = emptyList()))
            .isEqualTo(PlanEdit.Rejected(Reason.InThePast, "p-w1-tue"))
        assertThat(PlanEditor.swap(plan, "p-w1-thu", "p-w1-sat", today = day(3), rides = listOf(done("p-w1-thu"))))
            .isEqualTo(PlanEdit.Rejected(Reason.AlreadyDone, "p-w1-thu"))
        assertThat(PlanEditor.swap(plan, "p-w1-thu", "x", monday, emptyList()))
            .isEqualTo(PlanEdit.Rejected(Reason.UnknownSession, "x"))
    }

    @Test fun shiftByDaysMovesRestOfPlan() {
        val from = day(3)
        val edited = PlanEditor.shift(plan, from, 3, today = day(2), rides = emptyList()).ok()
        assertConsistent(edited)
        plan.sessions.forEach { s ->
            val before = plan.dateOf(s)
            val expected = if (before.isBefore(from)) before else before.plusDays(3)
            assertThat(edited.dateOf(edited.session(s.id)!!)).isEqualTo(expected)
        }
        // Thu 10 Dec + 3 = Sun 13 Dec still fits; labels stay for a sub-week shift.
        assertThat(edited.weeks.map { it.kind }).isEqualTo(plan.weeks.map { it.kind })
    }

    @Test fun shiftSpillingPastEndAppendsWeek() {
        val edited = PlanEditor.shift(plan, day(3), 5, today = day(2), rides = emptyList()).ok()
        assertConsistent(edited)
        assertThat(edited.weeks).hasSize(5)
        assertThat(edited.dateOf(edited.session("p-w4-thu")!!)).isEqualTo(LocalDate.of(2026, 12, 15))
    }

    @Test fun pauseWeekMovesRecoveryLabelAlong() {
        val edited = PlanEditor.shift(plan, day(7), 7, today = day(5), rides = emptyList()).ok()
        assertConsistent(edited)
        assertThat(edited.weeks.map { it.kind }).containsExactly(Build, Build, Build, Build, Recovery).inOrder()
        assertThat(edited.weeks[1].sessions).isEmpty()
        assertThat(edited.weeks[4].sessions.map { it.id }).containsExactly("p-w4-tue", "p-w4-thu").inOrder()
    }

    @Test fun shiftLeavesDoneSessionsAndChecksCollisions() {
        val rides = listOf(done("p-w1-thu"))
        // Thursday was ridden in the morning; the rest moves one day later from Thursday on.
        val edited = PlanEditor.shift(plan, day(3), 1, today = day(3), rides = rides).ok()
        assertConsistent(edited)
        assertThat(edited.dateOf(edited.session("p-w1-thu")!!)).isEqualTo(day(3))
        assertThat(edited.dateOf(edited.session("p-w1-sat")!!)).isEqualTo(day(6))
        // Shifting two days earlier would put Saturday on the done Thursday.
        assertThat(PlanEditor.shift(plan, day(4), -2, today = day(3), rides = rides))
            .isEqualTo(PlanEdit.Rejected(Reason.DayTaken, "p-w1-thu"))
    }

    @Test fun shiftRejections() {
        assertThat(PlanEditor.shift(plan, day(3), -1, today = day(3), rides = emptyList()))
            .isEqualTo(PlanEdit.Rejected(Reason.InThePast, "p-w1-thu"))
        assertThat(PlanEditor.shift(plan, monday, 7 * TrainingPlan.MAX_WEEKS, today = monday, rides = emptyList()))
            .isEqualTo(PlanEdit.Rejected(Reason.TooLong, null))
        assertThat(PlanEditor.shift(plan, monday, 0, monday, emptyList()).ok()).isSameInstanceAs(plan)
        assertThat(PlanEditor.shift(plan, LocalDate.of(2027, 1, 1), 3, monday, emptyList()).ok()).isSameInstanceAs(plan)
    }

    /** The point of stable ids: after storing and reloading, a rescheduled session is still linked to its ride. */
    @Test fun rideLinksSurviveStorageAfterReschedule() {
        val partial = LinkedRide("p-w1-thu", rideId = 3, durationSec = 600, completed = false)
        val edited = PlanEditor.move(plan, "p-w1-thu", day(4), today = day(3), rides = listOf(partial)).ok()
            .let { PlanEditor.move(it, "p-w1-tue", day(3), today = day(3), rides = listOf(partial)).ok() }
        val stored = PlanJson.decode(PlanJson.encode(edited, includeIds = true), "p", edited.startDate, keepIds = true)
        assertThat(stored.sessions.map { Triple(it.id, stored.dateOf(it), it.workout.segments) })
            .isEqualTo(edited.sessions.map { Triple(it.id, edited.dateOf(it), it.workout.segments) })
        // Without stored ids, Tuesday's session now sitting on Thursday would have inherited the ride.
        assertThat(stored.sessionsOn(day(3)).single().id).isEqualTo("p-w1-tue")
        assertThat(PlanProgress.status(stored, stored.session("p-w1-tue")!!, day(3), listOf(partial))).isEqualTo(SessionStatus.Today)
        assertThat(PlanProgress.status(stored, stored.session("p-w1-thu")!!, day(3), listOf(partial))).isEqualTo(SessionStatus.Partial)
    }
}
