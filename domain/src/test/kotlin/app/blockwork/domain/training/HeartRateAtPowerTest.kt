package app.blockwork.domain.training

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate

/** Synthetic rides only: heart rate is a made-up linear function of power plus a per-ride offset. */
class HeartRateAtPowerTest {
    private val today = LocalDate.of(2026, 11, 18)
    private val ftp = 250

    /** Steady steps of (seconds, watts); heart rate = [offset] + 0.4 bpm/W. */
    private fun ride(daysAgo: Long, offset: Double, steps: List<Pair<Int, Int>> = listOf(900 to 150, 900 to 170)): RideProfile {
        var t = 0
        val samples = steps.flatMapIndexed { index, (sec, watts) ->
            List(sec) {
                t++
                val hr = (offset + 0.4 * watts).toInt()
                RideSample(t, t * 1000L, watts, 90.0, 30.0, hr, watts, index, 1.0)
            }
        }
        return RideProfile.of(samples, today.minusDays(daysAgo), ftp)
    }

    @Test fun recoversSlopeAndAdjustsToReferencePower() {
        val rides = listOf(ride(40, 80.0), ride(30, 80.0), ride(10, 74.0), ride(2, 74.0))
        val r = HeartRateAtPower.analyze(rides, today)!!
        // Equal time at 150 and 170 W: the weighted median is the lower bin.
        assertThat(r.referenceWatts).isEqualTo(150)
        assertThat(r.slopeBpmPerWatt!!).isWithin(0.001).of(0.4)
        assertThat(r.points.map { it.bpm }).containsExactly(140.0, 140.0, 134.0, 134.0).inOrder()
        // 170 W is within 15 % of 150 W, so both steps count (each minus the settling time).
        assertThat(r.points.first().seconds).isEqualTo(2 * (900 - FtpCheck.SETTLE_SEC))
    }

    @Test fun reportsClearChangeAgainstNoise() {
        val offsets = listOf(80.0, 81.0, 79.0, 80.0, 74.0, 75.0, 73.0, 74.0)
        val days = listOf(54L, 48, 40, 32, 24, 16, 8, 1)
        val r = HeartRateAtPower.analyze(days.zip(offsets).map { (d, o) -> ride(d, o) }, today)!!
        val c = r.change!!
        assertThat(c.recentRides).isEqualTo(4)
        assertThat(c.previousRides).isEqualTo(4)
        assertThat(c.delta).isWithin(0.01).of(-6.0)
        assertThat(c.isClear).isTrue()
        assertThat(r.typicalError).isNotNull()
    }

    @Test fun smallChangeWithinNoiseIsNotClear() {
        val offsets = listOf(80.0, 84.0, 77.0, 82.0, 79.0, 83.0, 76.0, 81.0)
        val days = listOf(54L, 48, 40, 32, 24, 16, 8, 1)
        val c = HeartRateAtPower.analyze(days.zip(offsets).map { (d, o) -> ride(d, o) }, today)!!.change!!
        assertThat(c.delta).isWithin(0.01).of(-1.0)
        assertThat(c.isClear).isFalse()
    }

    @Test fun ignoresPowerAboveModerateRangeAndShortRides() {
        val hard = ride(5, 70.0, steps = listOf(900 to 230)) // 92 % FTP
        val short = ride(4, 70.0, steps = listOf(150 to 150)) // too short to count as a steady step
        assertThat(HeartRateAtPower.analyze(listOf(hard, short), today)).isNull()
    }

    @Test fun withoutWithinRideSpreadOnlyExactPowerCounts() {
        val rides = listOf(
            ride(20, 80.0, steps = listOf(1800 to 150)),
            ride(10, 80.0, steps = listOf(1800 to 150)),
            ride(5, 80.0, steps = listOf(1800 to 170)),
        )
        val r = HeartRateAtPower.analyze(rides, today)!!
        assertThat(r.slopeBpmPerWatt).isNull()
        assertThat(r.referenceWatts).isEqualTo(150)
        assertThat(r.points).hasSize(2)
        assertThat(r.change).isNull()
    }

    @Test fun typicalErrorIsSdOfConsecutiveDifferencesOverRootTwo() {
        val points = listOf(140.0, 142.0, 140.0, 142.0).mapIndexed { i, bpm -> HrAtPowerPoint(today.plusDays(i.toLong()), bpm, 600) }
        val diffs = listOf(2.0, -2.0, 2.0)
        val mean = diffs.average()
        val sd = kotlin.math.sqrt(diffs.sumOf { (it - mean) * (it - mean) } / 2)
        assertThat(HeartRateAtPower.typicalError(points)!!).isWithin(1e-9).of(sd / kotlin.math.sqrt(2.0))
        assertThat(HeartRateAtPower.typicalError(points.take(3))).isNull()
    }
}
