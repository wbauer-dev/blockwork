package app.blockwork.domain.training

import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** What the history analyses need from one finished ride; computed once from its samples. */
data class RideProfile(
    val date: LocalDate,
    /** FTP setting during the ride. */
    val ftp: Int,
    /** Steady-state heart rate per power bin, see [FtpCheck.steadyHrByPower]. */
    val steadyHr: Map<Int, HrBin>,
    val powerZones: ZoneSeconds,
    /** Seconds per bpm. */
    val heartRateHistogram: Map<Int, Int>,
) {
    companion object {
        fun of(samples: List<RideSample>, date: LocalDate, ftp: Int) = RideProfile(
            date = date,
            ftp = ftp,
            steadyHr = FtpCheck.steadyHrByPower(samples),
            powerZones = IntensityDistribution.powerZones(samples, ftp),
            heartRateHistogram = IntensityDistribution.heartRateHistogram(samples),
        )
    }
}

/** One ride's steady-state heart rate, adjusted to the reference power. */
data class HrAtPowerPoint(val date: LocalDate, val bpm: Double, val seconds: Int)

/** Mean of the last [HeartRateAtPower.WINDOW_DAYS] days vs the same span before. */
data class HrAtPowerChange(
    val recentMean: Double,
    val recentRides: Int,
    val previousMean: Double,
    val previousRides: Int,
    val typicalError: Double,
) {
    /** Negative = lower heart rate at the same power now. */
    val delta: Double get() = recentMean - previousMean

    /** Standard error of the difference of two means, each ride carrying [typicalError]. */
    val standardError: Double get() = typicalError * sqrt(1.0 / recentRides + 1.0 / previousRides)

    /** The change exceeds about two standard errors (≈ 95 % confidence it is not noise). */
    val isClear: Boolean get() = abs(delta) > 2 * standardError
}

data class HrAtPowerResult(
    val referenceWatts: Int,
    /** Within-ride heart-rate slope used to adjust nearby powers; null = only rides at the reference power. */
    val slopeBpmPerWatt: Double?,
    /** Oldest first. */
    val points: List<HrAtPowerPoint>,
    /** Ride-to-ride noise (Hopkins 2000); null with fewer than [HeartRateAtPower.MIN_POINTS_FOR_ERROR] points. */
    val typicalError: Double?,
    val change: HrAtPowerChange?,
)

/**
 * Steady-state heart rate at a fixed submaximal power, ride by ride. A lower heart rate at the same
 * absolute power indicates better aerobic fitness (Lamberts et al. 2011, BJSM 45:797; Buchheit 2014,
 * Front Physiol 5:73). It also moves with heat, hydration, sleep, caffeine and fatigue, and can drop
 * with heavy fatigue too, so it is shown as a trend next to its own noise level.
 *
 * Only the moderate range ([MIN_FTP]–[MAX_FTP] of the FTP at the time) is used: below the first
 * threshold heart rate reaches a steady state, above it it keeps drifting upward. Rides at nearby
 * powers are adjusted to the reference power with a slope fitted within rides, so between-ride
 * fitness changes do not leak into the slope.
 */
object HeartRateAtPower {
    const val MIN_FTP = 0.50
    const val MAX_FTP = 0.80
    /** Bins with less steady time than this are too noisy. */
    const val MIN_BIN_SEC = 60
    /** A ride needs this much steady time near the reference power to give a point. */
    const val MIN_RIDE_SEC = 300
    /** Nearby powers are adjusted only within this fraction of the reference power. */
    const val ADJUST_WINDOW = 0.15
    /** Without a slope, only bins this close to the reference power count. */
    const val EXACT_WINDOW_WATTS = 5.0
    /** Within-ride power spread needed for a slope: about 10 min at ±15 W. */
    const val MIN_SLOPE_LEVERAGE = 600.0 * 15 * 15
    val PLAUSIBLE_SLOPE = 0.1..1.0
    const val WINDOW_DAYS = 28L
    const val MIN_POINTS_FOR_ERROR = 4

    fun analyze(rides: List<RideProfile>, today: LocalDate): HrAtPowerResult? {
        val perRide = rides.filter { !it.date.isAfter(today) }.sortedBy { it.date }.map { r ->
            r.date to r.steadyHr.values.filter { b ->
                b.seconds >= MIN_BIN_SEC && b.meanPower / r.ftp in MIN_FTP..MAX_FTP
            }
        }.filter { it.second.isNotEmpty() }
        if (perRide.isEmpty()) return null

        val reference = referencePower(perRide.flatMap { it.second })
        val slope = withinRideSlope(perRide.map { it.second })
        val window = if (slope != null) reference * ADJUST_WINDOW else EXACT_WINDOW_WATTS

        val points = perRide.mapNotNull { (date, bins) ->
            val near = bins.filter { abs(it.meanPower - reference) <= window }
            val seconds = near.sumOf { it.seconds }
            if (seconds < MIN_RIDE_SEC) return@mapNotNull null
            val bpm = near.sumOf { b -> b.seconds * (b.meanHr - (slope ?: 0.0) * (b.meanPower - reference)) } / seconds
            HrAtPowerPoint(date, bpm, seconds)
        }
        val typicalError = typicalError(points)
        return HrAtPowerResult(reference.roundToInt(), slope, points, typicalError, change(points, typicalError, today))
    }

    /** Seconds-weighted median power of the usable bins, rounded to 5 W. */
    private fun referencePower(bins: List<HrBin>): Double {
        val sorted = bins.sortedBy { it.meanPower }
        val half = sorted.sumOf { it.seconds } / 2.0
        var acc = 0
        for (b in sorted) {
            acc += b.seconds
            if (acc >= half) return (b.meanPower / 5).roundToInt() * 5.0
        }
        return (sorted.last().meanPower / 5).roundToInt() * 5.0
    }

    /** Pooled within-ride least-squares slope of heart rate on power; null if not estimable or implausible. */
    private fun withinRideSlope(rides: List<List<HrBin>>): Double? {
        var sxx = 0.0
        var sxy = 0.0
        for (bins in rides) {
            if (bins.size < 2) continue
            val w = bins.sumOf { it.seconds }.toDouble()
            val mp = bins.sumOf { it.seconds * it.meanPower } / w
            val mh = bins.sumOf { it.seconds * it.meanHr } / w
            for (b in bins) {
                sxx += b.seconds * (b.meanPower - mp) * (b.meanPower - mp)
                sxy += b.seconds * (b.meanPower - mp) * (b.meanHr - mh)
            }
        }
        if (sxx < MIN_SLOPE_LEVERAGE) return null
        return (sxy / sxx).takeIf { it in PLAUSIBLE_SLOPE }
    }

    /**
     * Typical error from consecutive rides: SD of the differences / √2 (Hopkins 2000, Sports Med 30:1).
     * Real change between rides is included, so it errs on the high (cautious) side.
     */
    fun typicalError(points: List<HrAtPowerPoint>): Double? {
        if (points.size < MIN_POINTS_FOR_ERROR) return null
        val diffs = points.zipWithNext { a, b -> b.bpm - a.bpm }
        val mean = diffs.average()
        val variance = diffs.sumOf { (it - mean) * (it - mean) } / (diffs.size - 1)
        return sqrt(variance) / sqrt(2.0)
    }

    private fun change(points: List<HrAtPowerPoint>, typicalError: Double?, today: LocalDate): HrAtPowerChange? {
        typicalError ?: return null
        val recentFrom = today.minusDays(WINDOW_DAYS)
        val previousFrom = recentFrom.minusDays(WINDOW_DAYS)
        val recent = points.filter { it.date.isAfter(recentFrom) }
        val previous = points.filter { it.date.isAfter(previousFrom) && !it.date.isAfter(recentFrom) }
        if (recent.size < 2 || previous.size < 2) return null
        return HrAtPowerChange(
            recentMean = recent.map { it.bpm }.average(),
            recentRides = recent.size,
            previousMean = previous.map { it.bpm }.average(),
            previousRides = previous.size,
            typicalError = typicalError,
        )
    }
}
