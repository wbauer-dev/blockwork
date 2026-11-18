package app.blockwork.domain.plan

import app.blockwork.domain.io.PlanJson
import app.blockwork.domain.io.WorkoutImportException
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.DayOfWeek.SATURDAY
import java.time.DayOfWeek.SUNDAY
import java.time.DayOfWeek.THURSDAY
import java.time.DayOfWeek.TUESDAY
import java.time.LocalDate

class PlanJsonProgressTest {
    private val plan = PlanGenerator.generate(
        PlanRequest(PlanGoal.FtpBuild, 4, setOf(TUESDAY, THURSDAY, SATURDAY), 60, LocalDate.of(2026, 11, 16)),
        "orig",
    )

    @Test fun roundTripRedatesAndReassignsIds() {
        assertRoundTrip(plan)
    }

    /** Polarized plans carry the newer session types and 30/15 sets (several repeat blocks per workout). */
    @Test fun roundTripPolarized() {
        val polarized = PlanGenerator.generate(
            PlanRequest(PlanGoal.Polarized, 12, setOf(TUESDAY, THURSDAY, SATURDAY, SUNDAY), 60, LocalDate.of(2026, 11, 16)),
            "orig",
        )
        assertThat(polarized.sessions.map { it.type })
            .containsAtLeast(SessionType.LongIntervals, SessionType.ShortIntervals, SessionType.Vo2Max)
        assertRoundTrip(polarized)
    }

    private fun assertRoundTrip(plan: TrainingPlan) {
        val text = PlanJson.encode(plan)
        assertThat(text).contains("\"format\":\"blockwork-plan\"")
        val copy = PlanJson.decode(text, "new", LocalDate.of(2027, 3, 3))
        assertThat(copy.startDate).isEqualTo(LocalDate.of(2027, 3, 1))
        assertThat(copy.goal).isEqualTo(plan.goal)
        assertThat(copy.weeks.map { it.kind }).isEqualTo(plan.weeks.map { it.kind })
        assertThat(copy.sessions.map { it.type }).isEqualTo(plan.sessions.map { it.type })
        assertThat(copy.sessions.map { it.workout.segments }).isEqualTo(plan.sessions.map { it.workout.segments })
        assertThat(copy.sessions.all { it.id.startsWith("new-") && it.workout.id == it.id }).isTrue()
    }

    @Test fun legacyPlanFormatStillImports() {
        val legacy = PlanJson.encode(plan).replace("\"blockwork-plan\"", "\"indoortrain-plan\"")
        assertThat(legacy).contains("\"indoortrain-plan\"")
        assertThat(PlanJson.looksLikePlan(legacy)).isTrue()
        assertThat(PlanJson.decode(legacy, "old", LocalDate.of(2027, 3, 3)).weeks).hasSize(plan.weeks.size)
    }

    @Test fun rejectsBadFiles() {
        assertThrows(WorkoutImportException::class.java) { PlanJson.decode("{}", "x", LocalDate.now()) }
        assertThrows(WorkoutImportException::class.java) {
            PlanJson.decode("""{"format":"blockwork-workout","name":"x","goal":"FtpBuild","weeks":[]}""", "x", LocalDate.now())
        }
        val dupDay = PlanJson.encode(plan).replace("\"THURSDAY\"", "\"TUESDAY\"")
        assertThat(assertThrows(WorkoutImportException::class.java) { PlanJson.decode(dupDay, "x", LocalDate.now()) })
            .hasMessageThat().contains("same day")
    }

    @Test fun statuses() {
        val first = plan.sessions[0]   // Tue 17 Nov
        val second = plan.sessions[1]  // Thu 19 Nov
        val today = LocalDate.of(2026, 11, 19)
        val partial = LinkedRide(first.id, 1, durationSec = 300, completed = false)
        assertThat(PlanProgress.status(plan, first, today, emptyList())).isEqualTo(SessionStatus.Missed)
        assertThat(PlanProgress.status(plan, first, today, listOf(partial))).isEqualTo(SessionStatus.Partial)
        assertThat(PlanProgress.status(plan, first, today, listOf(partial.copy(completed = true)))).isEqualTo(SessionStatus.Done)
        assertThat(PlanProgress.status(plan, second, today, emptyList())).isEqualTo(SessionStatus.Today)
        assertThat(PlanProgress.status(plan, plan.sessions[2], today, emptyList())).isEqualTo(SessionStatus.Upcoming)
        val done80 = LinkedRide(second.id, 2, durationSec = (second.workout.durationSec * 0.8).toInt(), completed = false)
        assertThat(PlanProgress.nextSession(plan, today, listOf(done80))).isEqualTo(plan.sessions[2])
        assertThat(PlanProgress.doneCount(plan, today, listOf(done80, partial))).isEqualTo(1)
    }
}
