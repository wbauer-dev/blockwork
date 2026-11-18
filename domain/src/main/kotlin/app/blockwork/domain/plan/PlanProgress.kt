package app.blockwork.domain.plan

import java.time.LocalDate

enum class SessionStatus { Done, Partial, Missed, Today, Upcoming }

/** A recorded ride linked to a planned session. */
data class LinkedRide(val plannedSessionId: String, val rideId: Long, val durationSec: Int, val completed: Boolean)

object PlanProgress {
    /** A ride counts as done if the workout ran to the end or at least 80 % of its duration. */
    private const val DONE_FRACTION = 0.8

    fun status(plan: TrainingPlan, session: PlannedSession, today: LocalDate, rides: List<LinkedRide>): SessionStatus {
        val linked = rides.filter { it.plannedSessionId == session.id }
        val date = plan.dateOf(session)
        return when {
            linked.any { it.completed || it.durationSec >= session.workout.durationSec * DONE_FRACTION } -> SessionStatus.Done
            linked.isNotEmpty() -> SessionStatus.Partial
            date.isBefore(today) -> SessionStatus.Missed
            date.isEqual(today) -> SessionStatus.Today
            else -> SessionStatus.Upcoming
        }
    }

    /** First session today or later that is not done yet. */
    fun nextSession(plan: TrainingPlan, today: LocalDate, rides: List<LinkedRide>): PlannedSession? =
        plan.sessions.firstOrNull {
            !plan.dateOf(it).isBefore(today) && status(plan, it, today, rides) != SessionStatus.Done
        }

    fun doneCount(plan: TrainingPlan, today: LocalDate, rides: List<LinkedRide>): Int =
        plan.sessions.count { status(plan, it, today, rides) == SessionStatus.Done }
}
