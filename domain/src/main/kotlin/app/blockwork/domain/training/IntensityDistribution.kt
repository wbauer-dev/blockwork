package app.blockwork.domain.training

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import kotlin.math.log10

/**
 * Seconds in the three intensity zones of the endurance-training literature (Seiler & Kjerland
 * 2006; Seiler 2010): zone 1 below the first lactate/ventilatory threshold (~2 mM), zone 2 between
 * the thresholds, zone 3 above the second threshold (~4 mM, maximal lactate steady state).
 */
data class ZoneSeconds(val z1: Int = 0, val z2: Int = 0, val z3: Int = 0) {
    val total: Int get() = z1 + z2 + z3

    operator fun plus(o: ZoneSeconds) = ZoneSeconds(z1 + o.z1, z2 + o.z2, z3 + o.z3)

    /** Zone shares as fractions 0–1; null without data. */
    fun fractions(): Triple<Double, Double, Double>? {
        val t = total.takeIf { it > 0 }?.toDouble() ?: return null
        return Triple(z1 / t, z2 / t, z3 / t)
    }
}

/** Training intensity distribution types as described by Treff et al. (2019, Front Physiol 10:707). */
enum class TidType(val title: String) {
    Polarized("Polarized"),
    Pyramidal("Pyramidal"),
    /** Most time in zone 1 and more in zone 3 than zone 2, but PI ≤ 2.00: non-polarized per Treff. */
    LowIntensity("Mostly low intensity"),
    Threshold("Threshold"),
    HighIntensity("High intensity"),
}

data class TidSummary(val zones: ZoneSeconds, val polarizationIndex: Double?, val type: TidType?)

/** Time in zone for one week (Monday start) by power and by heart rate. */
data class TidWeek(val monday: LocalDate, val power: ZoneSeconds, val heartRate: ZoneSeconds)

data class IntensityDistributionResult(
    /** Oldest first; the last entry is the current week. */
    val weeks: List<TidWeek>,
    /** Last [IntensityDistribution.SUMMARY_DAYS] days. */
    val recentPower: TidSummary,
    /** Null when no max heart rate is set. */
    val recentHeartRate: TidSummary?,
)

/**
 * Training intensity distribution (time in zone) over the ride history.
 *
 * Power zones use the FTP at the time of each ride. FTP approximates the maximal lactate steady
 * state, so the zone 2/3 boundary sits at the top of the Coggan threshold zone and the zone 1/2
 * boundary at the top of the Coggan endurance zone – an approximation of the individual
 * thresholds, not a measurement of them.
 *
 * Heart-rate zones use the %HRmax anchors of the five-zone scale in Seiler (2010, IJSPP 5:276,
 * Table 1), whose zone 3 (82–87 % HRmax, 2.5–4 mM) corresponds to the three-zone model's zone 2.
 * Heart rate lags behind short intervals, so HR-based time in zone 3 reads low for them.
 */
object IntensityDistribution {
    const val POWER_Z1_MAX_FTP = 0.75
    const val POWER_Z2_MAX_FTP = 1.05
    const val HR_Z2_FROM_MAX = 0.82
    const val HR_Z3_FROM_MAX = 0.875
    /** Treff et al. 2019: PI > 2.00 a.u. = polarized. */
    const val POLARIZED_PI = 2.0
    const val SUMMARY_DAYS = 28L
    /** Less total time than this is not classified. */
    const val MIN_CLASSIFY_SEC = 3600

    /** Power-based time in zone; seconds without power or with 0 W (not pedaling) are left out. */
    fun powerZones(samples: List<RideSample>, ftp: Int): ZoneSeconds {
        var z1 = 0
        var z2 = 0
        var z3 = 0
        for (s in samples) {
            val p = s.powerWatts?.takeIf { it > 0 } ?: continue
            val f = p.toDouble() / ftp
            when {
                f <= POWER_Z1_MAX_FTP -> z1++
                f <= POWER_Z2_MAX_FTP -> z2++
                else -> z3++
            }
        }
        return ZoneSeconds(z1, z2, z3)
    }

    /** Seconds per bpm; kept per ride so the zones follow later max-HR changes. */
    fun heartRateHistogram(samples: List<RideSample>): Map<Int, Int> =
        samples.mapNotNull { it.heartRateBpm?.takeIf { bpm -> bpm > 0 } }.groupingBy { it }.eachCount()

    fun heartRateZones(histogram: Map<Int, Int>, maxHeartRate: Int): ZoneSeconds {
        var z1 = 0
        var z2 = 0
        var z3 = 0
        for ((bpm, sec) in histogram) {
            val f = bpm.toDouble() / maxHeartRate
            when {
                f < HR_Z2_FROM_MAX -> z1 += sec
                f < HR_Z3_FROM_MAX -> z2 += sec
                else -> z3 += sec
            }
        }
        return ZoneSeconds(z1, z2, z3)
    }

    /**
     * Polarization Index (Treff et al. 2019): log10(Z1 / Z2 × Z3 × 100) with zones as fractions.
     * Zero when there is no zone 3 time; with no zone 2 time, Z2 is replaced by 0.01 and Z3 by
     * Z3 − 0.01 (their Eq. 2). Not valid (null) when zone 3 exceeds zone 1, or without data.
     */
    fun polarizationIndex(zones: ZoneSeconds): Double? {
        val (z1, z2, z3) = zones.fractions() ?: return null
        if (z3 > z1) return null
        if (z3 == 0.0) return 0.0
        if (z2 == 0.0) return if (z3 > 0.01) log10(z1 / 0.01 * (z3 - 0.01) * 100) else null
        return log10(z1 / z2 * z3 * 100)
    }

    /** Treff et al. 2019 types; null for less than [MIN_CLASSIFY_SEC] of data. */
    fun classify(zones: ZoneSeconds): TidType? {
        if (zones.total < MIN_CLASSIFY_SEC) return null
        val pi = polarizationIndex(zones)
        val (z1, z2, z3) = Triple(zones.z1, zones.z2, zones.z3)
        return when {
            // PI > 2 implies Z1 ≥ Z3 > Z2.
            pi != null && pi > POLARIZED_PI -> TidType.Polarized
            z3 > z1 && z3 >= z2 -> TidType.HighIntensity
            z2 >= z1 && z2 >= z3 -> TidType.Threshold
            z2 >= z3 -> TidType.Pyramidal
            else -> TidType.LowIntensity
        }
    }

    fun summarize(zones: ZoneSeconds) = TidSummary(zones, polarizationIndex(zones), classify(zones))

    fun analyze(
        rides: List<RideProfile>,
        maxHeartRate: Int?,
        today: LocalDate,
        weekCount: Int = 12,
    ): IntensityDistributionResult {
        val known = rides.filter { !it.date.isAfter(today) }
        fun hrZones(r: RideProfile) = maxHeartRate?.let { heartRateZones(r.heartRateHistogram, it) } ?: ZoneSeconds()
        val thisMonday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val byWeek = known.groupBy { it.date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)) }
        val weeks = (weekCount - 1 downTo 0).map { back ->
            val monday = thisMonday.minusWeeks(back.toLong())
            val week = byWeek[monday].orEmpty()
            TidWeek(
                monday,
                week.fold(ZoneSeconds()) { acc, r -> acc + r.powerZones },
                week.fold(ZoneSeconds()) { acc, r -> acc + hrZones(r) },
            )
        }
        val recent = known.filter { it.date.isAfter(today.minusDays(SUMMARY_DAYS)) }
        return IntensityDistributionResult(
            weeks = weeks,
            recentPower = summarize(recent.fold(ZoneSeconds()) { acc, r -> acc + r.powerZones }),
            recentHeartRate = maxHeartRate?.let { summarize(recent.fold(ZoneSeconds()) { acc, r -> acc + hrZones(r) }) },
        )
    }
}
