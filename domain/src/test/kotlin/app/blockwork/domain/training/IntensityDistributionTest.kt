package app.blockwork.domain.training

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate

/** Synthetic data only. */
class IntensityDistributionTest {
    private val wed = LocalDate.of(2026, 11, 18)

    /** Zone shares in percent, scaled to 10 hours. */
    private fun pct(z1: Int, z2: Int, z3: Int) = ZoneSeconds(z1 * 360, z2 * 360, z3 * 360)

    private fun sample(t: Int, power: Int?, hr: Int?) = RideSample(t, t * 1000L, power, 90.0, 30.0, hr, power, 0)

    @Test fun polarizationIndexMatchesTreffTable() {
        // Rows from Treff et al. 2019, Table 1.
        assertThat(IntensityDistribution.polarizationIndex(pct(80, 0, 20))!!).isWithin(0.005).of(3.18)
        assertThat(IntensityDistribution.polarizationIndex(pct(74, 11, 15))!!).isWithin(0.005).of(2.00)
        assertThat(IntensityDistribution.polarizationIndex(pct(94, 4, 2))!!).isWithin(0.005).of(1.67)
        assertThat(IntensityDistribution.polarizationIndex(pct(57, 43, 0))).isEqualTo(0.0)
        // Zone 3 > zone 1: not valid.
        assertThat(IntensityDistribution.polarizationIndex(pct(43, 0, 57))).isNull()
        assertThat(IntensityDistribution.polarizationIndex(ZoneSeconds())).isNull()
        // No zone 2 and almost no zone 3: log of zero is undefined.
        assertThat(IntensityDistribution.polarizationIndex(ZoneSeconds(1000, 0, 5))).isNull()
    }

    @Test fun classifiesTreffExamples() {
        assertThat(IntensityDistribution.classify(pct(80, 5, 15))).isEqualTo(TidType.Polarized)
        assertThat(IntensityDistribution.classify(pct(75, 5, 20))).isEqualTo(TidType.Polarized)
        assertThat(IntensityDistribution.classify(pct(70, 20, 10))).isEqualTo(TidType.Pyramidal)
        assertThat(IntensityDistribution.classify(pct(94, 4, 2))).isEqualTo(TidType.Pyramidal)
        assertThat(IntensityDistribution.classify(pct(40, 50, 10))).isEqualTo(TidType.Threshold)
        assertThat(IntensityDistribution.classify(pct(20, 10, 70))).isEqualTo(TidType.HighIntensity)
        // Z1 > Z3 > Z2 but PI ≤ 2.00: not polarized.
        assertThat(IntensityDistribution.classify(pct(50, 24, 26))).isEqualTo(TidType.LowIntensity)
        // Too little data.
        assertThat(IntensityDistribution.classify(ZoneSeconds(3000, 100, 400))).isNull()
    }

    @Test fun powerZoneBoundariesFollowFtpAndSkipCoasting() {
        val s = listOf(150, 151, 210, 211, 0).mapIndexed { i, p -> sample(i + 1, p, null) } + sample(6, null, null)
        assertThat(IntensityDistribution.powerZones(s, ftp = 200)).isEqualTo(ZoneSeconds(1, 2, 1))
    }

    @Test fun heartRateZoneBoundariesUseSeilerAnchors() {
        val s = listOf(163, 164, 174, 175, 175).mapIndexed { i, hr -> sample(i + 1, 100, hr) } + sample(6, 100, null)
        val histogram = IntensityDistribution.heartRateHistogram(s)
        assertThat(histogram).containsExactly(163, 1, 164, 1, 174, 1, 175, 2)
        // Max 200: < 164 bpm zone 1, 164–174 zone 2, ≥ 175 zone 3.
        assertThat(IntensityDistribution.heartRateZones(histogram, 200)).isEqualTo(ZoneSeconds(1, 2, 2))
    }

    @Test fun groupsByWeekAndSummarizesRecentDays() {
        fun ride(date: LocalDate, zones: ZoneSeconds, hr: Map<Int, Int>) = RideProfile(date, 200, emptyMap(), zones, hr)
        val rides = listOf(
            ride(wed, ZoneSeconds(3000, 0, 600), mapOf(120 to 3000, 190 to 600)),
            ride(LocalDate.of(2026, 11, 10), ZoneSeconds(1000, 500, 0), mapOf(170 to 1500)),
            ride(LocalDate.of(2026, 10, 1), ZoneSeconds(9999, 0, 0), mapOf(120 to 9999)), // older than 28 days
            ride(LocalDate.of(2026, 11, 20), ZoneSeconds(5, 5, 5), emptyMap()), // future: ignored
        )
        val r = IntensityDistribution.analyze(rides, maxHeartRate = 200, today = wed)
        assertThat(r.weeks).hasSize(12)
        assertThat(r.weeks.last().monday).isEqualTo(LocalDate.of(2026, 11, 16))
        assertThat(r.weeks.last().power).isEqualTo(ZoneSeconds(3000, 0, 600))
        assertThat(r.weeks.last().heartRate).isEqualTo(ZoneSeconds(3000, 0, 600))
        assertThat(r.weeks[10].heartRate).isEqualTo(ZoneSeconds(0, 1500, 0))
        assertThat(r.recentPower.zones).isEqualTo(ZoneSeconds(4000, 500, 600))
        assertThat(r.recentHeartRate!!.zones).isEqualTo(ZoneSeconds(3000, 1500, 600))
        assertThat(IntensityDistribution.analyze(rides, maxHeartRate = null, today = wed).recentHeartRate).isNull()
    }
}
