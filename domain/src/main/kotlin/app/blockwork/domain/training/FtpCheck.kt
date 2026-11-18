package app.blockwork.domain.training

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.roundToInt

/** Steady-state heart rate and power in one [FtpCheck.POWER_BIN]-watt power bin. */
data class HrBin(val seconds: Int, val hrSum: Long, val powerSum: Long) {
    val meanHr: Double get() = hrSum.toDouble() / seconds
    val meanPower: Double get() = powerSum.toDouble() / seconds
    operator fun plus(o: HrBin) = HrBin(seconds + o.seconds, hrSum + o.hrSum, powerSum + o.powerSum)

    companion object {
        val EMPTY = HrBin(0, 0, 0)
    }
}

/**
 * What the FTP check needs from one ride, computed from its 1 Hz samples by [FtpCheck.fingerprint].
 * Heart-rate bins use absolute watts, so they stay comparable when the FTP setting changes.
 */
data class RideFingerprint(
    val date: LocalDate,
    val ftp: Int,
    val isFtpTest: Boolean,
    val completed: Boolean,
    /** Bin lower bound in watts -> steady-state heart rate there. */
    val hrByPower: Map<Int, HrBin>,
    /** Seconds with a planned target of at least [FtpCheck.HARD_FTP] (only rides that stored intensity). */
    val hardSec: Int,
    val raisedSec: Int,
    val loweredSec: Int,
)

enum class Direction { Up, Down, Neutral }

/** Heart rate at the same power, recent weeks vs the weeks before. Negative = lower now (fitter). */
data class HrTrend(val deltaBpm: Double, val comparedSec: Int, val direction: Direction)

/** Hard workouts at the current FTP in which the rider raised or lowered the intensity / ended early. */
data class IntensityTrend(val hardRides: Int, val raised: Int, val lowered: Int, val direction: Direction)

/**
 * Power at threshold heart rate, extrapolated from a heart rate vs power line fitted to steady
 * segments. Threshold HR is assumed to be [FtpCheck.THRESHOLD_LOW]–[FtpCheck.THRESHOLD_HIGH] of max HR,
 * which gives the range.
 */
data class FtpEstimate(
    val watts: Int,
    val low: Int,
    val high: Int,
    val thresholdHrLow: Int,
    val thresholdHrHigh: Int,
    val direction: Direction,
)

enum class EstimateUnavailable { NoMaxHeartRate, NotEnoughData }

enum class FtpVerdict { Up, Down, NoChange, NotEnoughData }

data class FtpCheckResult(
    val verdict: FtpVerdict,
    val hrTrend: HrTrend?,
    val intensity: IntensityTrend?,
    val estimate: FtpEstimate?,
    val estimateUnavailable: EstimateUnavailable?,
)

/**
 * Looks for signs that the FTP setting is out of date. In ERG mode power follows the targets, so
 * the signals come from the body's response: heart rate at the same power over time, the rider's
 * intensity changes, and an extrapolated power at threshold heart rate. A verdict needs two
 * signals pointing the same way and none against; it is a hint to re-test, never an FTP change.
 */
object FtpCheck {
    const val POWER_BIN = 10
    /** Heart rate lags a step change by 1–3 minutes; the start of each steady step is ignored. */
    const val SETTLE_SEC = 120
    const val MIN_STEADY_STEP_SEC = 180
    /** A sample counts as steady only when power is this close to the target. */
    const val ON_TARGET = 0.08
    const val HARD_FTP = 0.88

    const val RECENT_DAYS = 21L
    const val BASELINE_DAYS = 63L
    const val ESTIMATE_DAYS = 28L
    const val TREND_BPM = 4.0
    const val LAST_HARD_RIDES = 4

    const val THRESHOLD_LOW = 0.85
    const val THRESHOLD_MID = 0.885
    const val THRESHOLD_HIGH = 0.92
    /** Estimate differs from the FTP setting by at least this fraction to count as a signal. */
    const val ESTIMATE_SIGNAL = 0.05

    fun fingerprint(
        samples: List<RideSample>,
        date: LocalDate,
        ftp: Int,
        isFtpTest: Boolean,
        completed: Boolean,
    ): RideFingerprint {
        val bins = HashMap<Int, HrBin>()
        // Runs of samples in the same step with the same target = steady segments.
        var start = 0
        while (start < samples.size) {
            val first = samples[start]
            var end = start
            while (end + 1 < samples.size &&
                samples[end + 1].stepIndex == first.stepIndex &&
                samples[end + 1].targetWatts == first.targetWatts
            ) end++
            val target = first.targetWatts
            if (target != null && end - start + 1 >= MIN_STEADY_STEP_SEC) {
                for (s in samples.subList(start + SETTLE_SEC, end + 1)) {
                    val p = s.powerWatts ?: continue
                    val hr = s.heartRateBpm ?: continue
                    if (abs(p - target) > target * ON_TARGET) continue
                    val key = p / POWER_BIN * POWER_BIN
                    bins[key] = (bins[key] ?: HrBin.EMPTY) + HrBin(1, hr.toLong(), p.toLong())
                }
            }
            start = end + 1
        }

        var hard = 0
        var raised = 0
        var lowered = 0
        if (!isFtpTest) {
            for (s in samples) {
                val i = s.intensity ?: continue
                val target = s.targetWatts ?: continue
                if (target / i / ftp < HARD_FTP) continue
                hard++
                if (i > 1.001) raised++ else if (i < 0.999) lowered++
            }
        }
        return RideFingerprint(date, ftp, isFtpTest, completed, bins, hard, raised, lowered)
    }

    fun analyze(rides: List<RideFingerprint>, currentFtp: Int, maxHeartRate: Int?, today: LocalDate): FtpCheckResult {
        fun age(r: RideFingerprint) = ChronoUnit.DAYS.between(r.date, today)
        val known = rides.filter { age(it) >= 0 }

        val hrTrend = hrTrend(
            recent = known.filter { age(it) < RECENT_DAYS },
            baseline = known.filter { age(it) in RECENT_DAYS until BASELINE_DAYS },
        )
        val intensity = intensityTrend(known.filter { age(it) < ESTIMATE_DAYS && it.ftp == currentFtp })
        val estimate = maxHeartRate?.let { estimate(known.filter { age(it) < ESTIMATE_DAYS }, it, currentFtp) }

        val directions = listOfNotNull(hrTrend?.direction, intensity?.direction, estimate?.direction)
        val up = directions.count { it == Direction.Up }
        val down = directions.count { it == Direction.Down }
        val verdict = when {
            directions.isEmpty() -> FtpVerdict.NotEnoughData
            up >= 2 && down == 0 -> FtpVerdict.Up
            down >= 2 && up == 0 -> FtpVerdict.Down
            else -> FtpVerdict.NoChange
        }
        return FtpCheckResult(
            verdict, hrTrend, intensity, estimate,
            estimateUnavailable = when {
                estimate != null -> null
                maxHeartRate == null -> EstimateUnavailable.NoMaxHeartRate
                else -> EstimateUnavailable.NotEnoughData
            },
        )
    }

    private fun merge(rides: List<RideFingerprint>): Map<Int, HrBin> {
        val out = HashMap<Int, HrBin>()
        for (r in rides) for ((k, b) in r.hrByPower) out[k] = (out[k] ?: HrBin.EMPTY) + b
        return out
    }

    private fun hrTrend(recent: List<RideFingerprint>, baseline: List<RideFingerprint>): HrTrend? {
        val r = recent.filter { it.hrByPower.isNotEmpty() }
        val b = baseline.filter { it.hrByPower.isNotEmpty() }
        if (r.size < 2 || b.size < 2) return null
        val rb = merge(r)
        val bb = merge(b)
        var weight = 0
        var sum = 0.0
        for ((k, now) in rb) {
            val before = bb[k] ?: continue
            if (now.seconds < MIN_STEADY_STEP_SEC || before.seconds < MIN_STEADY_STEP_SEC) continue
            val w = minOf(now.seconds, before.seconds)
            weight += w
            sum += w * (now.meanHr - before.meanHr)
        }
        if (weight < 600) return null
        val delta = sum / weight
        val direction = when {
            delta <= -TREND_BPM -> Direction.Up
            delta >= TREND_BPM -> Direction.Down
            else -> Direction.Neutral
        }
        return HrTrend(delta, weight, direction)
    }

    private fun intensityTrend(rides: List<RideFingerprint>): IntensityTrend? {
        val hard = rides.filter { !it.isFtpTest && it.hardSec >= 300 }.sortedBy { it.date }.takeLast(LAST_HARD_RIDES)
        if (hard.size < 2) return null
        val raised = hard.count { it.raisedSec >= it.hardSec * 0.5 }
        val lowered = hard.count { it.loweredSec >= it.hardSec * 0.25 || !it.completed }
        val direction = when {
            raised >= 2 && lowered == 0 -> Direction.Up
            lowered >= 2 && raised == 0 -> Direction.Down
            else -> Direction.Neutral
        }
        return IntensityTrend(hard.size, raised, lowered, direction)
    }

    private fun estimate(rides: List<RideFingerprint>, maxHeartRate: Int, currentFtp: Int): FtpEstimate? {
        val points = merge(rides).values.filter { it.seconds >= SETTLE_SEC }
            .map { b -> Triple(b.meanPower, b.meanHr, b.seconds.toDouble()) }
        val total = points.sumOf { it.third }
        if (total < 1200 || points.size < 2) return null
        val maxPower = points.maxOf { it.first }
        if (maxPower - points.minOf { it.first } < 40) return null

        // Weighted least squares: hr = a + b * power.
        val mx = points.sumOf { it.first * it.third } / total
        val my = points.sumOf { it.second * it.third } / total
        val sxx = points.sumOf { it.third * (it.first - mx) * (it.first - mx) }
        val sxy = points.sumOf { it.third * (it.first - mx) * (it.second - my) }
        val syy = points.sumOf { it.third * (it.second - my) * (it.second - my) }
        val slope = sxy / sxx
        if (slope !in 0.05..1.0 || syy <= 0) return null
        val r2 = sxy * sxy / (sxx * syy)
        if (r2 < 0.5) return null
        val intercept = my - slope * mx

        fun powerAt(fraction: Double) = (maxHeartRate * fraction - intercept) / slope
        val mid = powerAt(THRESHOLD_MID)
        // Too far beyond anything ridden steadily: the line says little up there.
        if (mid > maxPower * 1.5 || mid <= 0) return null
        fun round5(w: Double) = (w / 5).roundToInt() * 5
        val watts = round5(mid)
        return FtpEstimate(
            watts = watts,
            low = round5(powerAt(THRESHOLD_LOW)),
            high = round5(powerAt(THRESHOLD_HIGH)),
            thresholdHrLow = (maxHeartRate * THRESHOLD_LOW).roundToInt(),
            thresholdHrHigh = (maxHeartRate * THRESHOLD_HIGH).roundToInt(),
            direction = when {
                watts >= currentFtp * (1 + ESTIMATE_SIGNAL) -> Direction.Up
                watts <= currentFtp * (1 - ESTIMATE_SIGNAL) -> Direction.Down
                else -> Direction.Neutral
            },
        )
    }
}
