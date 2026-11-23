package app.blockwork.domain.plan

import app.blockwork.domain.Workout
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

enum class PlanGoal(val title: String, val description: String) {
    BaseEndurance("Base endurance", "Mostly zone 2 with some tempo – builds aerobic base and fatigue resistance."),
    FtpBuild("FTP build", "Sweet spot and threshold intervals to raise your FTP, VO2max added later."),
    Vo2Max("VO2max", "Short, hard intervals above threshold to raise your ceiling."),
    GeneralFitness("General fitness", "A balanced mix of endurance, sweet spot and short hard efforts."),
    Polarized(
        "Polarized (80/20)",
        "Mostly easy zone 2 with one or two very hard interval days (4×8, 30/15, VO2max) – no tempo or sweet spot.",
    ),
}

enum class WeekKind { Build, Recovery }

enum class SessionType(val hard: Boolean, val title: String) {
    Endurance(false, "Endurance"),
    LongRide(false, "Long ride"),
    Recovery(false, "Recovery"),
    Tempo(true, "Tempo"),
    SweetSpot(true, "Sweet spot"),
    Threshold(true, "Threshold"),
    Vo2Max(true, "VO2max"),
    /** 4×8-style intervals around FTP with short rests (Seiler et al., 2013). */
    LongIntervals(true, "Long intervals"),
    /** Sets of 30 s on / 15 s off (Rønnestad et al., 2015). */
    ShortIntervals(true, "30/15 intervals"),
    FtpTest(true, "FTP test"),
}

data class PlannedSession(
    val id: String,
    val week: Int,
    val day: DayOfWeek,
    val type: SessionType,
    val workout: Workout,
)

data class PlanWeek(val index: Int, val kind: WeekKind, val sessions: List<PlannedSession>)

/** A plan always starts on a Monday; session dates follow from week index and weekday. */
data class TrainingPlan(
    val id: String,
    val name: String,
    val goal: PlanGoal,
    val startDate: LocalDate,
    val weeks: List<PlanWeek>,
) {
    init {
        require(startDate.dayOfWeek == DayOfWeek.MONDAY) { "Plan must start on a Monday" }
    }

    val sessions: List<PlannedSession> get() = weeks.flatMap { it.sessions }
    val endDate: LocalDate get() = startDate.plusWeeks(weeks.size.toLong()).minusDays(1)

    fun dateOf(session: PlannedSession): LocalDate =
        startDate.plusDays(session.week * 7L + (session.day.value - 1))

    fun sessionsOn(date: LocalDate): List<PlannedSession> = sessions.filter { dateOf(it) == date }

    /** 0-based week containing [date], or null outside the plan. */
    fun weekIndexOf(date: LocalDate): Int? {
        val days = java.time.temporal.ChronoUnit.DAYS.between(startDate, date)
        return (days / 7).toInt().takeIf { days >= 0 && it < weeks.size }
    }

    fun session(id: String): PlannedSession? = sessions.firstOrNull { it.id == id }

    companion object {
        /** Upper bound for plan length (generator, import and rescheduling). */
        const val MAX_WEEKS = 52

        fun mondayOf(date: LocalDate): LocalDate = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    }
}

data class PlanRequest(
    val goal: PlanGoal,
    val weeks: Int,
    /** Days with a session in build weeks (2–6). */
    val trainingDays: Set<DayOfWeek>,
    /** Typical session length in minutes (30–120); the long ride is 1.5× this. */
    val sessionMinutes: Int,
    /** Any date; the plan starts on the Monday of that week. */
    val startDate: LocalDate,
) {
    companion object {
        val WEEKS = 4..12
        val DAYS = 2..6
        val MINUTES = 30..120
    }
}
