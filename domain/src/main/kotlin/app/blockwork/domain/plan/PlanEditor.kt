package app.blockwork.domain.plan

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

/** Outcome of a [PlanEditor] operation. */
sealed interface PlanEdit {
    /** The edited plan (the unchanged plan if there was nothing to do). */
    data class Ok(val plan: TrainingPlan) : PlanEdit

    /** [sessionId] names the session the rejection is about, e.g. the one already on the target day. */
    data class Rejected(val reason: Reason, val sessionId: String? = null) : PlanEdit

    enum class Reason {
        UnknownSession,

        /** Done sessions (see [PlanProgress]) stay where they were ridden. */
        AlreadyDone,

        /** A session would land before today. */
        InThePast,

        /** The target day already has a session ([Rejected.sessionId]); a plan has at most one per day. */
        DayTaken,

        /** The plan would grow beyond [TrainingPlan.MAX_WEEKS]. */
        TooLong,
    }
}

/**
 * Rescheduling of an existing plan. Pure: every operation returns the edited plan or why it was rejected.
 *
 * - Sessions keep their ids (and workouts), so recorded rides stay linked to them.
 * - Done sessions never move, and nothing moves to a day before `today`. Missed and partially ridden
 *   sessions can be moved.
 * - The plan grows when a session leaves its range: weeks are appended, or prepended with an earlier
 *   start Monday. It never shrinks, so emptied weeks at the end stay part of the plan.
 */
object PlanEditor {

    /** Moves one session to [to]. */
    fun move(plan: TrainingPlan, sessionId: String, to: LocalDate, today: LocalDate, rides: List<LinkedRide>): PlanEdit {
        val session = plan.session(sessionId) ?: return rejected(PlanEdit.Reason.UnknownSession, sessionId)
        if (isDone(plan, session, today, rides)) return rejected(PlanEdit.Reason.AlreadyDone, sessionId)
        if (to == plan.dateOf(session)) return PlanEdit.Ok(plan)
        if (to.isBefore(today)) return rejected(PlanEdit.Reason.InThePast, sessionId)
        plan.sessionsOn(to).firstOrNull()?.let { return rejected(PlanEdit.Reason.DayTaken, it.id) }
        return rebuild(plan, mapOf(sessionId to to)) { kindAt(plan, it) }
    }

    /** Exchanges the days of two sessions; both days must be today or later. */
    fun swap(plan: TrainingPlan, firstId: String, secondId: String, today: LocalDate, rides: List<LinkedRide>): PlanEdit {
        val a = plan.session(firstId) ?: return rejected(PlanEdit.Reason.UnknownSession, firstId)
        val b = plan.session(secondId) ?: return rejected(PlanEdit.Reason.UnknownSession, secondId)
        if (a.id == b.id) return PlanEdit.Ok(plan)
        listOf(a, b).firstOrNull { isDone(plan, it, today, rides) }?.let { return rejected(PlanEdit.Reason.AlreadyDone, it.id) }
        listOf(a, b).firstOrNull { plan.dateOf(it).isBefore(today) }?.let { return rejected(PlanEdit.Reason.InThePast, it.id) }
        return rebuild(plan, mapOf(a.id to plan.dateOf(b), b.id to plan.dateOf(a))) { kindAt(plan, it) }
    }

    /**
     * Moves every session on or after [from] that is not done by [days] (negative = earlier).
     *
     * Build/recovery labels of the weeks from [from] onwards move along by whole weeks ([days] / 7, rounded),
     * so a one-week shift (e.g. a pause after illness) keeps the recovery week with its sessions; the
     * vacated weeks keep their label. Shifts to earlier days leave the labels as they are.
     */
    fun shift(plan: TrainingPlan, from: LocalDate, days: Int, today: LocalDate, rides: List<LinkedRide>): PlanEdit {
        if (days == 0) return PlanEdit.Ok(plan)
        val moving = plan.sessions.filter { !plan.dateOf(it).isBefore(from) && !isDone(plan, it, today, rides) }
        if (moving.isEmpty()) return PlanEdit.Ok(plan)
        val targets = moving.associate { it.id to plan.dateOf(it).plusDays(days.toLong()) }
        moving.firstOrNull { targets.getValue(it.id).isBefore(today) }?.let { return rejected(PlanEdit.Reason.InThePast, it.id) }
        val staying = plan.sessions.filter { it.id !in targets }.associateBy { plan.dateOf(it) }
        targets.values.firstNotNullOfOrNull { staying[it] }?.let { return rejected(PlanEdit.Reason.DayTaken, it.id) }

        val weekShift = (days / 7.0).roundToInt().coerceAtLeast(0)
        val fromWeek = weeksBetween(plan.startDate, TrainingPlan.mondayOf(from))
        return rebuild(plan, targets) { old ->
            if (weekShift > 0 && old >= fromWeek + weekShift) kindAt(plan, old - weekShift) else kindAt(plan, old)
        }
    }

    /**
     * Lays the plan out again with [newDates] (by session id; other sessions keep their date).
     * [kindOf] gets the week index relative to the *old* start Monday (negative for prepended weeks).
     */
    private fun rebuild(plan: TrainingPlan, newDates: Map<String, LocalDate>, kindOf: (Int) -> WeekKind): PlanEdit {
        val dated = plan.sessions.map { it to (newDates[it.id] ?: plan.dateOf(it)) }
        check(dated.map { it.second }.distinct().size == dated.size) { "Two sessions on the same day" }
        val start = minOf(plan.startDate, TrainingPlan.mondayOf(dated.minOf { it.second }))
        val lastMonday = maxOf(TrainingPlan.mondayOf(plan.endDate), TrainingPlan.mondayOf(dated.maxOf { it.second }))
        val count = weeksBetween(start, lastMonday) + 1
        if (count > TrainingPlan.MAX_WEEKS) return rejected(PlanEdit.Reason.TooLong, null)
        val offset = weeksBetween(start, plan.startDate)
        val byWeek = dated.groupBy { (_, date) -> weeksBetween(start, TrainingPlan.mondayOf(date)) }
        val weeks = (0 until count).map { index ->
            val sessions = byWeek[index].orEmpty()
                .map { (session, date) -> session.copy(week = index, day = date.dayOfWeek) }
                .sortedBy { it.day }
            PlanWeek(index, kindOf(index - offset), sessions)
        }
        return PlanEdit.Ok(plan.copy(startDate = start, weeks = weeks))
    }

    /** Kind of old week [index]; prepended weeks are build weeks, appended ones repeat the last week's kind. */
    private fun kindAt(plan: TrainingPlan, index: Int): WeekKind =
        plan.weeks.getOrNull(index)?.kind ?: if (index < 0) WeekKind.Build else plan.weeks.last().kind

    private fun isDone(plan: TrainingPlan, session: PlannedSession, today: LocalDate, rides: List<LinkedRide>) =
        PlanProgress.status(plan, session, today, rides) == SessionStatus.Done

    /** Whole weeks between two Mondays. */
    private fun weeksBetween(from: LocalDate, to: LocalDate): Int = Math.floorDiv(ChronoUnit.DAYS.between(from, to), 7L).toInt()

    private fun rejected(reason: PlanEdit.Reason, sessionId: String?) = PlanEdit.Rejected(reason, sessionId)
}
