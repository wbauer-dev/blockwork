package app.indoortrain.domain.training

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate
import kotlin.math.roundToInt

/** Synthetic rides only: heart rate is a made-up linear function of power. */
class FtpCheckTest {
    private val today = LocalDate.of(2026, 11, 18)
    private val ftp = 250

    /** Steps of (seconds, planned watts); heart rate follows [hr] immediately. */
    private fun samples(
        steps: List<Pair<Int, Int>>,
        hr: (Int) -> Int?,
        intensity: Double? = 1.0,
    ): List<RideSample> {
        var t = 0
        return steps.flatMapIndexed { index, (sec, planned) ->
            val target = (planned * (intensity ?: 1.0)).roundToInt()
            List(sec) {
                t++
                RideSample(t, t * 1000L, target, 90.0, 30.0, hr(target), target, index, intensity)
            }
        }
    }

    private fun ride(
        daysAgo: Long,
        hr: (Int) -> Int?,
        steps: List<Pair<Int, Int>> = listOf(600 to 150, 600 to 220),
        intensity: Double? = 1.0,
        rideFtp: Int = ftp,
        isFtpTest: Boolean = false,
        completed: Boolean = true,
    ) = FtpCheck.fingerprint(samples(steps, hr, intensity), today.minusDays(daysAgo), rideFtp, isFtpTest, completed)

    private val fit = { p: Int -> 60 + (0.4 * p).roundToInt() }

    @Test fun fingerprintUsesOnlySettledOnTargetSamples() {
        val steps = listOf(600 to 200, 60 to 300, 300 to 150)
        val s = samples(steps, { 140 }).mapIndexed { i, x ->
            when (i) {
                900 -> x.copy(powerWatts = 100) // off target in the 150 W step (after it settled)
                901 -> x.copy(heartRateBpm = null)
                else -> x
            }
        }
        val f = FtpCheck.fingerprint(s, today, ftp, isFtpTest = false, completed = true)
        assertThat(f.hrByPower.keys).containsExactly(200, 150)
        assertThat(f.hrByPower.getValue(200).seconds).isEqualTo(600 - FtpCheck.SETTLE_SEC)
        assertThat(f.hrByPower.getValue(150).seconds).isEqualTo(300 - FtpCheck.SETTLE_SEC - 2)
        assertThat(f.hrByPower.getValue(200).meanHr).isEqualTo(140.0)
        // nothing at or above 88 % FTP in the 600 s and 300 s steps; the 60 s step at 300 W is hard
        assertThat(f.hardSec).isEqualTo(60)
        assertThat(f.raisedSec).isEqualTo(0)
    }

    @Test fun estimateExtrapolatesToThresholdHeartRate() {
        // hr = 60 + 0.4 W, max HR 190: threshold 161.5 / 168.15 / 174.8 bpm -> 253.75 / 270.4 / 287 W
        val rides = listOf(ride(0, fit), ride(5, fit))
        val r = FtpCheck.analyze(rides, ftp, maxHeartRate = 190, today = today)
        val e = r.estimate!!
        assertThat(e.watts).isEqualTo(270)
        assertThat(e.low).isEqualTo(255)
        assertThat(e.high).isEqualTo(285)
        assertThat(e.thresholdHrLow).isEqualTo(162)
        assertThat(e.thresholdHrHigh).isEqualTo(175)
        assertThat(e.direction).isEqualTo(Direction.Up) // 270 ≥ 250 × 1.05
        assertThat(r.estimateUnavailable).isNull()
    }

    @Test fun noEstimateWithoutMaxHeartRateOrSpread() {
        val rides = listOf(ride(0, fit), ride(5, fit))
        assertThat(FtpCheck.analyze(rides, ftp, null, today).estimateUnavailable).isEqualTo(EstimateUnavailable.NoMaxHeartRate)
        // only one power level: no slope to extrapolate from
        val flat = listOf(ride(0, fit, listOf(1800 to 170)), ride(3, fit, listOf(1800 to 170)))
        val r = FtpCheck.analyze(flat, ftp, 190, today)
        assertThat(r.estimate).isNull()
        assertThat(r.estimateUnavailable).isEqualTo(EstimateUnavailable.NotEnoughData)
    }

    @Test fun lowerHeartRateAtSamePowerPointsUp() {
        val rides = listOf(
            ride(40, { 70 + (0.4 * it).roundToInt() }),
            ride(30, { 70 + (0.4 * it).roundToInt() }),
            ride(7, fit),
            ride(0, fit),
        )
        val trend = FtpCheck.analyze(rides, ftp, null, today).hrTrend!!
        assertThat(trend.deltaBpm).isWithin(0.01).of(-10.0)
        assertThat(trend.direction).isEqualTo(Direction.Up)
    }

    @Test fun hrTrendNeedsTwoRidesInEachPeriod() {
        val rides = listOf(ride(30, fit), ride(7, fit), ride(0, fit))
        assertThat(FtpCheck.analyze(rides, ftp, null, today).hrTrend).isNull()
    }

    @Test fun raisedIntensityInHardWorkoutsPointsUp() {
        val hard = listOf(300 to 120, 1200 to 230)
        val rides = (0L..3).map { ride(it * 3, fit, hard, intensity = 1.05) }
        val t = FtpCheck.analyze(rides, ftp, null, today).intensity!!
        assertThat(t.hardRides).isEqualTo(4)
        assertThat(t.raised).isEqualTo(4)
        assertThat(t.direction).isEqualTo(Direction.Up)
    }

    @Test fun loweredIntensityOrEndingEarlyPointsDown() {
        val hard = listOf(300 to 120, 1200 to 230)
        val rides = listOf(
            ride(1, fit, hard, intensity = 0.95),
            ride(4, fit, hard, completed = false),
            ride(8, fit, hard),
        )
        val t = FtpCheck.analyze(rides, ftp, null, today).intensity!!
        assertThat(t.lowered).isEqualTo(2)
        assertThat(t.direction).isEqualTo(Direction.Down)
    }

    @Test fun intensityIgnoresOtherFtpRampTestsAndOldRecordings() {
        val hard = listOf(300 to 120, 1200 to 230)
        val rides = listOf(
            ride(1, fit, hard, intensity = 1.05, rideFtp = 230), // before the FTP was raised
            ride(2, fit, hard, intensity = 1.05, rideFtp = 230),
            ride(3, fit, hard, isFtpTest = true, completed = false),
            ride(4, fit, hard, intensity = null, completed = false), // recorded before intensity was stored
        )
        assertThat(FtpCheck.analyze(rides, ftp, null, today).intensity).isNull()
    }

    @Test fun verdicts() {
        assertThat(FtpCheck.analyze(emptyList(), ftp, 190, today).verdict).isEqualTo(FtpVerdict.NotEnoughData)

        val older = { p: Int -> 70 + (0.4 * p).roundToInt() }
        val fitter = listOf(ride(40, older), ride(30, older), ride(7, fit), ride(0, fit))
        // lower HR at the same power + estimate above FTP
        assertThat(FtpCheck.analyze(fitter, ftp, 190, today).verdict).isEqualTo(FtpVerdict.Up)

        // same rides, but the FTP is already set to the estimate: only one signal left
        assertThat(FtpCheck.analyze(fitter, 270, 190, today).verdict).isEqualTo(FtpVerdict.NoChange)

        // HR says up, intensity says down: conflicting, no verdict
        val hard = listOf(300 to 120, 1200 to 230)
        val mixed = fitter + listOf(ride(1, fit, hard, intensity = 0.95), ride(2, fit, hard, intensity = 0.95))
        assertThat(FtpCheck.analyze(mixed, ftp, null, today).verdict).isEqualTo(FtpVerdict.NoChange)

        // FTP set too high (300 W): estimate well below and intensity lowered in hard workouts at that FTP
        val hard300 = listOf(300 to 120, 1200 to 280)
        val down = listOf(
            ride(1, fit, hard300, intensity = 0.95, rideFtp = 300),
            ride(3, fit, hard300, intensity = 0.95, rideFtp = 300),
            ride(0, fit),
            ride(5, fit),
        )
        val r = FtpCheck.analyze(down, 300, 190, today)
        assertThat(r.estimate!!.direction).isEqualTo(Direction.Down)
        assertThat(r.verdict).isEqualTo(FtpVerdict.Down)
    }
}
