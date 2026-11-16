package app.indoortrain.domain.training

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate

/** Synthetic rides only. */
class TrainingLoadTest {
    private val wed = LocalDate.of(2026, 11, 18)

    private fun ride(date: LocalDate, tss: Int?, min: Int = 60, kj: Int? = 600) = RideLoad(date, min * 60, tss, kj)

    @Test fun weeklyTotalsStartOnMonday() {
        val rides = listOf(
            ride(LocalDate.of(2026, 11, 16), 50),           // Monday this week
            ride(LocalDate.of(2026, 11, 15), 40),           // Sunday last week
            ride(LocalDate.of(2026, 11, 10), 30, min = 45), // Tuesday last week
            ride(LocalDate.of(2026, 11, 11), null, kj = null), // no power: counts for time only
        )
        val o = TrainingLoad.overview(rides, wed)
        assertThat(o.thisWeek).isEqualTo(PeriodTotals(rides = 1, durationSec = 3600, tss = 50, energyKj = 600))
        assertThat(o.lastWeek).isEqualTo(PeriodTotals(rides = 3, durationSec = 3600 + 2700 + 3600, tss = 70, energyKj = 1200))
        assertThat(o.weeks).hasSize(12)
        assertThat(o.weeks.last().monday).isEqualTo(LocalDate.of(2026, 11, 16))
        assertThat(o.weeks[10].monday).isEqualTo(LocalDate.of(2026, 11, 9))
        assertThat(o.weeks[10].totals.tss).isEqualTo(70)
        assertThat(o.weeks.first().totals).isEqualTo(PeriodTotals())
        assertThat(o.allTime.rides).isEqualTo(4)
        assertThat(o.allTime.tss).isEqualTo(120)
    }

    @Test fun ridesOlderThanTheChartStillCountAllTime() {
        val o = TrainingLoad.overview(listOf(ride(wed.minusWeeks(30), 80), ride(wed, 20)), wed, weekCount = 4)
        assertThat(o.weeks.sumOf { it.totals.tss }).isEqualTo(20)
        assertThat(o.allTime.tss).isEqualTo(100)
    }

    @Test fun noFitnessWithoutTss() {
        assertThat(TrainingLoad.fitness(emptyList(), wed)).isNull()
        assertThat(TrainingLoad.fitness(listOf(ride(wed, null)), wed)).isNull()
        assertThat(TrainingLoad.overview(emptyList(), wed).fitness).isNull()
    }

    @Test fun steadyLoadConvergesToDailyTss() {
        val rides = (0L until 365).map { ride(wed.minusDays(it), 100) }
        val f = TrainingLoad.fitness(rides, wed)!!
        assertThat(f.fitness).isWithin(0.1).of(100.0)
        assertThat(f.fatigue).isWithin(0.1).of(100.0)
        assertThat(f.formLevel).isEqualTo(FormLevel.Neutral)
    }

    @Test fun hardBlockMakesTiredRestMakesFresh() {
        // four weeks of 80 TSS on five days a week, then a 120 TSS day today
        val block = (1L..28).filter { it % 7 !in setOf(0L, 3L) }.map { ride(wed.minusDays(it), 80) } + ride(wed, 120)
        val tired = TrainingLoad.fitness(block, wed)!!
        assertThat(tired.fatigue).isGreaterThan(tired.fitness)
        assertThat(tired.form).isLessThan(-10.0)

        val rested = TrainingLoad.fitness(block, wed.plusDays(10))!!
        assertThat(rested.form).isGreaterThan(5.0)
        assertThat(rested.formLevel).isEqualTo(FormLevel.Fresh)
        assertThat(rested.fitness).isLessThan(tired.fitness)
    }

    @Test fun futureRidesIgnored() {
        val f = TrainingLoad.fitness(listOf(ride(wed, 50), ride(wed.plusDays(1), 500)), wed)!!
        assertThat(f).isEqualTo(TrainingLoad.fitness(listOf(ride(wed, 50)), wed))
    }
}
