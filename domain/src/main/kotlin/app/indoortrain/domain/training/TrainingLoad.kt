package app.indoortrain.domain.training

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import kotlin.math.exp

/** What the overview needs from one finished ride. Rides without power have no TSS. */
data class RideLoad(val date: LocalDate, val durationSec: Int, val tss: Int?, val energyKj: Int?)

data class PeriodTotals(val rides: Int = 0, val durationSec: Int = 0, val tss: Int = 0, val energyKj: Int = 0) {
    operator fun plus(r: RideLoad) = PeriodTotals(
        rides + 1,
        durationSec + r.durationSec,
        tss + (r.tss ?: 0),
        energyKj + (r.energyKj ?: 0),
    )
}

data class WeekLoad(val monday: LocalDate, val totals: PeriodTotals)

/**
 * Performance-management values, all in TSS per day:
 * fitness = 42-day and fatigue = 7-day exponentially weighted average of daily TSS (CTL / ATL),
 * form = fitness − fatigue (TSB). Positive form means fresh, strongly negative means tired.
 */
data class Fitness(val fitness: Double, val fatigue: Double) {
    val form: Double get() = fitness - fatigue

    val formLevel: FormLevel get() = when {
        form > 5 -> FormLevel.Fresh
        form >= -10 -> FormLevel.Neutral
        form >= -30 -> FormLevel.Productive
        else -> FormLevel.Overreaching
    }
}

enum class FormLevel(val title: String) {
    Fresh("Fresh"),
    Neutral("Neutral"),
    Productive("Building fitness"),
    Overreaching("Very tired – consider rest"),
}

data class TrainingOverview(
    val thisWeek: PeriodTotals,
    val lastWeek: PeriodTotals,
    /** Oldest first; the last entry is the current week. */
    val weeks: List<WeekLoad>,
    val allTime: PeriodTotals,
    /** null until there is at least one ride with TSS. */
    val fitness: Fitness?,
    /** Date of the earliest ride; fitness needs about [TrainingLoad.FITNESS_DAYS] days of history to be meaningful. */
    val firstRide: LocalDate?,
)

object TrainingLoad {
    const val FITNESS_DAYS = 42
    const val FATIGUE_DAYS = 7

    fun overview(rides: List<RideLoad>, today: LocalDate, weekCount: Int = 12): TrainingOverview {
        val thisMonday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val byWeek = rides.groupBy { it.date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)) }
        fun totals(monday: LocalDate) = byWeek[monday].orEmpty().fold(PeriodTotals(), PeriodTotals::plus)
        val weeks = (weekCount - 1 downTo 0).map { back ->
            val monday = thisMonday.minusWeeks(back.toLong())
            WeekLoad(monday, totals(monday))
        }
        return TrainingOverview(
            thisWeek = totals(thisMonday),
            lastWeek = totals(thisMonday.minusWeeks(1)),
            weeks = weeks,
            allTime = rides.fold(PeriodTotals(), PeriodTotals::plus),
            fitness = fitness(rides, today),
            firstRide = rides.minOfOrNull { it.date },
        )
    }

    /**
     * Fitness and fatigue at the end of [today], starting from zero on the first day with TSS.
     * Rides after [today] are ignored.
     */
    fun fitness(rides: List<RideLoad>, today: LocalDate): Fitness? {
        val daily = rides.filter { it.tss != null && !it.date.isAfter(today) }
            .groupBy { it.date }
            .mapValues { (_, r) -> r.sumOf { it.tss!! }.toDouble() }
        val first = daily.keys.minOrNull() ?: return null
        // Exponential decay per day: same as x += (tss − x) / N for large N, but exact for small N.
        val kFit = 1 - exp(-1.0 / FITNESS_DAYS)
        val kFat = 1 - exp(-1.0 / FATIGUE_DAYS)
        var fit = 0.0
        var fat = 0.0
        for (i in 0..ChronoUnit.DAYS.between(first, today)) {
            val tss = daily[first.plusDays(i)] ?: 0.0
            fit += (tss - fit) * kFit
            fat += (tss - fat) * kFat
        }
        return Fitness(fit, fat)
    }
}
