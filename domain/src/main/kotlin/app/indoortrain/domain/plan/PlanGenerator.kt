package app.indoortrain.domain.plan

import java.time.DayOfWeek
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Rule-based, deterministic plan generator.
 *
 * - Blocks of 2–4 build weeks + 1 recovery week (≈ 3:1); the plan always ends with a recovery week.
 * - FTP ramp test on the first day of week 1 and on the last day of every recovery week.
 * - Recovery weeks: one session less, ~70 % duration, no intervals → ~60 % load.
 * - Interval sessions progress with every build week (see [WorkoutTemplates]).
 * - Hard sessions are never on consecutive days (also across Sunday → Monday).
 * - The long ride goes to the weekend when possible.
 */
object PlanGenerator {

    fun generate(request: PlanRequest, planId: String): TrainingPlan {
        require(request.weeks in PlanRequest.WEEKS) { "Weeks must be ${PlanRequest.WEEKS}" }
        require(request.trainingDays.size in PlanRequest.DAYS) { "Choose ${PlanRequest.DAYS} training days" }
        require(request.sessionMinutes in PlanRequest.MINUTES) { "Session length must be ${PlanRequest.MINUTES} min" }

        val days = request.trainingDays.sorted()
        val kinds = weekKinds(request.weeks)
        var buildLevel = 0
        var block = 0
        var previous: List<Slot> = emptyList()
        val weeks = kinds.mapIndexed { index, kind ->
            // A hard Sunday (e.g. the FTP test) blocks a hard Monday in the following week.
            val mondayBlocked = previous.lastOrNull()?.let { it.day == DayOfWeek.SUNDAY && it.type.hard } == true
            val sessions = when (kind) {
                WeekKind.Build -> buildWeek(request, days, index, block, buildLevel, mondayBlocked).also { buildLevel++ }
                WeekKind.Recovery -> recoveryWeek(request, days, index, buildLevel).also { block++ }
            }
            previous = sessions
            PlanWeek(index, kind, sessions.map { (day, type, minutes, level) ->
                val id = "$planId-w${index + 1}-${day.name.lowercase().take(3)}"
                val context = "Week ${index + 1} of ${request.weeks} (${kind.name.lowercase()})."
                PlannedSession(id, index, day, type, WorkoutTemplates.create(type, level, minutes, id, context))
            })
        }
        return TrainingPlan(
            id = planId,
            name = "${request.goal.title} · ${request.weeks} weeks",
            goal = request.goal,
            startDate = TrainingPlan.mondayOf(request.startDate),
            weeks = weeks,
        )
    }

    /** Block lengths 3–5 weeks, each ending with a recovery week. */
    fun weekKinds(weeks: Int): List<WeekKind> {
        val blocks = max(1, (weeks / 4.0).roundToInt())
        val base = weeks / blocks
        val extra = weeks % blocks
        return (0 until blocks).flatMap { b ->
            val length = base + if (b < extra) 1 else 0
            List(length - 1) { WeekKind.Build } + WeekKind.Recovery
        }
    }

    private data class Slot(val day: DayOfWeek, val type: SessionType, val minutes: Int, val level: Int)

    private fun buildWeek(
        r: PlanRequest,
        days: List<DayOfWeek>,
        week: Int,
        block: Int,
        level: Int,
        mondayBlocked: Boolean,
    ): List<Slot> {
        val n = days.size
        var hardCount = hardSessions(r.goal, n, block)
        var types: List<SessionType>
        var assignment: List<SessionType>?
        while (true) {
            types = sessionTypes(r.goal, n, block, level, hardCount, testFirst = week == 0)
            assignment = arrange(types, days, firstMustBeTest = week == 0, mondayBlocked = mondayBlocked)
            if (assignment != null || hardCount == 0) break
            hardCount-- // no arrangement without back-to-back hard days: drop one hard session
        }
        val arranged = assignment ?: types
        return days.zip(arranged).map { (day, type) ->
            val minutes = when (type) {
                SessionType.LongRide -> (r.sessionMinutes * 1.5).roundTo5().coerceAtMost(180)
                SessionType.Recovery -> r.sessionMinutes.coerceAtMost(45)
                SessionType.FtpTest -> 0 // fixed-length built-in
                else -> r.sessionMinutes
            }
            Slot(day, type, minutes, level)
        }
    }

    private fun recoveryWeek(r: PlanRequest, days: List<DayOfWeek>, week: Int, level: Int): List<Slot> {
        val count = max(2, days.size - 1)
        // Spread the remaining days evenly over the selected ones.
        val picked = (0 until count).map { days[(it * days.size) / count] }.distinct()
        val minutes = (r.sessionMinutes * 0.7).roundTo5().coerceAtLeast(30)
        return picked.mapIndexed { i, day ->
            when {
                i == picked.lastIndex -> Slot(day, SessionType.FtpTest, 0, level)
                i == picked.lastIndex - 1 -> Slot(day, SessionType.Recovery, minutes.coerceAtMost(40), level) // fresh for the test
                else -> Slot(day, SessionType.Endurance, minutes, level)
            }
        }
    }

    private fun hardSessions(goal: PlanGoal, n: Int, block: Int): Int = when (goal) {
        PlanGoal.BaseEndurance -> if (block >= 1 && n >= 5) 2 else 1
        PlanGoal.GeneralFitness -> if (n <= 3) 1 else 2
        // 80/20 by time in zone, not by session count: at most two hard days (as in Seiler et al., 2013),
        // whose intervals are only part of the session, so most of the weekly time stays easy.
        PlanGoal.Polarized -> if (n <= 3) 1 else 2
        PlanGoal.FtpBuild, PlanGoal.Vo2Max -> when {
            n <= 2 -> 1
            n >= 5 && block >= 1 -> 3
            else -> 2
        }
    }

    private fun hardPool(goal: PlanGoal, block: Int, level: Int): List<SessionType> = when (goal) {
        PlanGoal.BaseEndurance -> if (block == 0) listOf(SessionType.Tempo, SessionType.Tempo) else listOf(SessionType.Tempo, SessionType.SweetSpot)
        PlanGoal.FtpBuild -> if (block == 0) {
            listOf(SessionType.SweetSpot, SessionType.Threshold, SessionType.SweetSpot)
        } else if (level % 2 == 0) {
            // Later blocks: VO2max and sweet spot alternate as the second key session.
            listOf(SessionType.Threshold, SessionType.Vo2Max, SessionType.SweetSpot)
        } else {
            listOf(SessionType.Threshold, SessionType.SweetSpot, SessionType.Vo2Max)
        }
        PlanGoal.Vo2Max -> if (block == 0) {
            listOf(SessionType.Vo2Max, SessionType.Threshold, SessionType.SweetSpot)
        } else {
            listOf(SessionType.Vo2Max, SessionType.Threshold, SessionType.ShortIntervals)
        }
        PlanGoal.GeneralFitness ->
            listOf(SessionType.SweetSpot, if (level % 2 == 0) SessionType.Vo2Max else SessionType.ShortIntervals)
        // Long intervals first, short/VO2max work rotates in as the second key session.
        PlanGoal.Polarized -> when {
            block == 0 -> listOf(SessionType.LongIntervals, SessionType.ShortIntervals)
            level % 2 == 0 -> listOf(SessionType.LongIntervals, SessionType.Vo2Max)
            else -> listOf(SessionType.ShortIntervals, SessionType.LongIntervals)
        }
    }

    private fun sessionTypes(goal: PlanGoal, n: Int, block: Int, level: Int, hardCount: Int, testFirst: Boolean): List<SessionType> {
        val hard = hardPool(goal, block, level).take(hardCount).toMutableList()
        if (testFirst && hard.isNotEmpty()) hard[0] = SessionType.FtpTest else if (testFirst) hard += SessionType.FtpTest
        val easyCount = n - hard.size
        val easy = (0 until easyCount).map { i ->
            when {
                i == 0 && n >= 3 -> SessionType.LongRide
                i == easyCount - 1 && n >= 6 -> SessionType.Recovery
                else -> SessionType.Endurance
            }
        }
        return hard + easy
    }

    /**
     * Assigns [types] to [days] (sorted). Returns null if hard sessions cannot be kept off
     * consecutive days. Among valid arrangements, prefers the long ride on Sat/Sun and hard
     * sessions spread evenly; ties are broken deterministically.
     */
    private fun arrange(
        types: List<SessionType>,
        days: List<DayOfWeek>,
        firstMustBeTest: Boolean,
        mondayBlocked: Boolean,
    ): List<SessionType>? {
        val mondayIndex = days.indexOf(DayOfWeek.MONDAY)
        var best: List<SessionType>? = null
        var bestScore = Int.MIN_VALUE
        for (perm in permutations(types).distinct()) {
            if (firstMustBeTest && SessionType.FtpTest in types && perm[0] != SessionType.FtpTest) continue
            if (hasBackToBackHard(perm, days)) continue
            if (mondayBlocked && mondayIndex >= 0 && perm[mondayIndex].hard) continue
            val score = score(perm, days)
            if (score > bestScore) {
                bestScore = score
                best = perm
            }
        }
        return best
    }

    private fun hasBackToBackHard(types: List<SessionType>, days: List<DayOfWeek>): Boolean {
        val hardDays = days.filterIndexed { i, _ -> types[i].hard }.map { it.value }
        return hardDays.any { d -> (d % 7 + 1) in hardDays } // includes Sunday(7) → Monday(1)
    }

    private fun score(types: List<SessionType>, days: List<DayOfWeek>): Int {
        var s = 0
        val longIndex = types.indexOf(SessionType.LongRide)
        if (longIndex >= 0 && days[longIndex].value >= 6) s += 100
        val hardDays = days.filterIndexed { i, _ -> types[i].hard }.map { it.value }.sorted()
        if (hardDays.size >= 2) {
            val gaps = hardDays.zipWithNext { a, b -> b - a } + (hardDays.first() + 7 - hardDays.last())
            s += 10 * gaps.min()
        }
        // Stable tie-break: keep the original pool order (most important hard session first).
        types.forEachIndexed { i, t -> if (t.hard) s -= i }
        return s
    }

    private fun <T> permutations(list: List<T>): List<List<T>> =
        if (list.size <= 1) listOf(list) else list.indices.flatMap { i ->
            permutations(list.take(i) + list.drop(i + 1)).map { listOf(list[i]) + it }
        }

    private fun Double.roundTo5(): Int = ((this / 5).roundToInt() * 5)
    private fun Int.roundTo5(): Int = toDouble().roundTo5()
}
