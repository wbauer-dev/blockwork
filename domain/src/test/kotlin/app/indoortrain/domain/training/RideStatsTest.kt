package app.indoortrain.domain.training

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RideStatsTest {

    private fun sample(t: Int, power: Int?, hr: Int? = null, cadence: Double? = 90.0, speed: Double? = 36.0) =
        RideSample(t, t * 1000L, power, cadence, speed, hr, null, 0)

    @Test fun steadyPowerHasNpEqualToAverage() {
        val samples = (1..600).map { sample(it, 200, hr = 140) }
        val s = RideStats.summarize(samples, ftp = 250)
        assertThat(s.durationSec).isEqualTo(600)
        assertThat(s.avgPower).isEqualTo(200)
        assertThat(s.normalizedPower).isEqualTo(200)
        assertThat(s.intensityFactor).isEqualTo(0.8)
        assertThat(s.tss).isEqualTo(11) // 600 s * 200 * 0.8 / (250 * 3600) * 100 = 10.7
        assertThat(s.energyKj).isEqualTo(120)
        assertThat(s.avgHeartRate).isEqualTo(140)
        assertThat(s.distanceMeters).isEqualTo(6000)
    }

    @Test fun intervalsRaiseNpAboveAverage() {
        val powers = (0 until 1200).map { if ((it / 60) % 2 == 0) 300 else 100 }
        val np = RideStats.normalizedPower(powers)!!
        assertThat(np).isGreaterThan(200)
        assertThat(RideStats.normalizedPower(List(29) { 200 })).isNull()
    }

    @Test fun missingValuesAreIgnored() {
        val samples = listOf(sample(1, null, cadence = null, speed = null), sample(2, 100), sample(3, 300, hr = 150))
        val s = RideStats.summarize(samples, ftp = 200)
        assertThat(s.avgPower).isEqualTo(200)
        assertThat(s.maxPower).isEqualTo(300)
        assertThat(s.maxHeartRate).isEqualTo(150)
        assertThat(s.normalizedPower).isNull()
        assertThat(s.tss).isNull()
    }
}

class BestPowerTest {
    @Test fun bestOneMinuteAndRampEstimate() {
        // 5 min easy, then a ramp ending with a hard minute at 350 W
        val powers = List(300) { 150 } + List(60) { 300 } + List(60) { 350 } + List(10) { 100 }
        assertThat(RideStats.bestAverage(powers, 60)).isEqualTo(350)
        assertThat(RideStats.bestAverage(List(59) { 1 }, 60)).isNull()
        val samples = powers.mapIndexed { i, p -> RideSample(i + 1, i * 1000L, p, 90.0, 30.0, null, null, 0) }
        val summary = RideStats.summarize(samples, ftp = 250)
        assertThat(summary.bestPower1Min).isEqualTo(350)
        assertThat(summary.rampTestFtp).isEqualTo(263) // 0.75 * 350 = 262.5
    }
}
