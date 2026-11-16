package app.indoortrain.domain.training

import kotlin.math.pow
import kotlin.math.roundToInt

/** One recorded second of a ride. */
data class RideSample(
    /** Seconds since ride start (running time, pauses excluded). */
    val elapsedSec: Int,
    val timestampMillis: Long,
    val powerWatts: Int?,
    val cadenceRpm: Double?,
    val speedKmh: Double?,
    val heartRateBpm: Int?,
    val targetWatts: Int?,
    val stepIndex: Int,
)

data class RideSummary(
    val durationSec: Int,
    val avgPower: Int?,
    val maxPower: Int?,
    val normalizedPower: Int?,
    val avgHeartRate: Int?,
    val maxHeartRate: Int?,
    val avgCadence: Int?,
    val energyKj: Int,
    val intensityFactor: Double?,
    val tss: Int?,
    /** Distance from speed integration, metres. */
    val distanceMeters: Int,
)

object RideStats {

    fun summarize(samples: List<RideSample>, ftp: Int): RideSummary {
        val powers = samples.mapNotNull { it.powerWatts }
        val hrs = samples.mapNotNull { it.heartRateBpm }
        val cadences = samples.mapNotNull { it.cadenceRpm }.filter { it > 0 }
        val np = normalizedPower(samples.map { it.powerWatts ?: 0 })
        val duration = samples.size
        val intensityFactor = np?.let { (it.toDouble() / ftp * 100).roundToInt() / 100.0 }
        val tss = if (np != null && intensityFactor != null) {
            (duration * np * intensityFactor / (ftp * 3600.0) * 100).roundToInt()
        } else {
            null
        }
        return RideSummary(
            durationSec = duration,
            avgPower = powers.averageOrNull(),
            maxPower = powers.maxOrNull(),
            normalizedPower = np,
            avgHeartRate = hrs.averageOrNull(),
            maxHeartRate = hrs.maxOrNull(),
            avgCadence = cadences.takeIf { it.isNotEmpty() }?.average()?.roundToInt(),
            energyKj = (powers.sumOf { it.toLong() } / 1000.0).roundToInt(),
            intensityFactor = intensityFactor,
            tss = tss,
            distanceMeters = samples.sumOf { (it.speedKmh ?: 0.0) / 3.6 }.roundToInt(),
        )
    }

    /** Coggan NP: 4th-power mean of the 30 s rolling average. Null for rides under 30 s. */
    fun normalizedPower(powerPerSecond: List<Int>): Int? {
        if (powerPerSecond.size < 30) return null
        var windowSum = powerPerSecond.take(30).sum().toDouble()
        var sum4 = (windowSum / 30).pow(4)
        var n = 1
        for (i in 30 until powerPerSecond.size) {
            windowSum += powerPerSecond[i] - powerPerSecond[i - 30]
            sum4 += (windowSum / 30).pow(4)
            n++
        }
        return (sum4 / n).pow(0.25).roundToInt()
    }

    private fun List<Int>.averageOrNull(): Int? = takeIf { it.isNotEmpty() }?.average()?.roundToInt()
}
