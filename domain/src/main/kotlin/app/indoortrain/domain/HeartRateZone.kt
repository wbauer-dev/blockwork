package app.indoortrain.domain

import kotlin.math.roundToInt

/** Five heart-rate zones as % of maximum heart rate (upper bound exclusive). */
enum class HeartRateZone(val number: Int, val label: String, val upperFraction: Double) {
    Z1(1, "Recovery", 0.60),
    Z2(2, "Endurance", 0.70),
    Z3(3, "Tempo", 0.80),
    Z4(4, "Threshold", 0.90),
    Z5(5, "Maximum", Double.POSITIVE_INFINITY);

    companion object {
        val MAX_HR_RANGE = 120..230

        fun forBpm(bpm: Int, maxHeartRate: Int): HeartRateZone {
            val f = bpm.toDouble() / maxHeartRate
            return entries.first { f < it.upperFraction }
        }

        /** Lower bpm bound of each zone, for display. */
        fun lowerBounds(maxHeartRate: Int): Map<HeartRateZone, Int> = entries.associateWith { z ->
            val lower = entries.getOrNull(z.ordinal - 1)?.upperFraction ?: 0.0
            (lower * maxHeartRate).roundToInt()
        }
    }
}
