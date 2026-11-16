package app.indoortrain.domain

import kotlin.math.roundToInt

/**
 * A structured workout. Targets are stored relative to FTP (1.0 = 100 % FTP) so that
 * changing the FTP rescales all workouts.
 */
data class Workout(
    val id: String,
    val name: String,
    val description: String = "",
    val segments: List<Segment>,
    /** Ramp test: the summary offers an FTP estimate (75 % of best 1-min power). */
    val isFtpTest: Boolean = false,
) {
    val durationSec: Int get() = segments.sumOf { it.durationSec }

    /** Expands repeat blocks into a flat, ordered list of timed steps. */
    fun flatten(): List<Step> {
        val out = mutableListOf<Step>()
        var t = 0
        fun add(segment: Segment) {
            when (segment) {
                is Segment.Steady -> {
                    out += Step(t, segment.durationSec, segment.targetFtp, segment.targetFtp, segment.label)
                    t += segment.durationSec
                }
                is Segment.Ramp -> {
                    out += Step(t, segment.durationSec, segment.fromFtp, segment.toFtp, segment.label)
                    t += segment.durationSec
                }
                is Segment.FreeRide -> {
                    out += Step(t, segment.durationSec, 0.0, 0.0, segment.label, freeRide = true)
                    t += segment.durationSec
                }
                is Segment.Repeat -> repeat(segment.times) { segment.segments.forEach(::add) }
            }
        }
        segments.forEach(::add)
        return out
    }
}

sealed interface Segment {
    val durationSec: Int

    data class Steady(
        override val durationSec: Int,
        val targetFtp: Double,
        val label: String? = null,
    ) : Segment

    data class Ramp(
        override val durationSec: Int,
        val fromFtp: Double,
        val toFtp: Double,
        val label: String? = null,
    ) : Segment

    /** No ERG target: the trainer is released and the rider pedals freely. */
    data class FreeRide(
        override val durationSec: Int,
        val label: String? = null,
    ) : Segment

    data class Repeat(val times: Int, val segments: List<Segment>) : Segment {
        override val durationSec: Int get() = times * segments.sumOf { it.durationSec }
    }
}

/** A single flattened step of a workout. */
data class Step(
    val startSec: Int,
    val durationSec: Int,
    val fromFtp: Double,
    val toFtp: Double,
    val label: String? = null,
    val freeRide: Boolean = false,
) {
    val endSec: Int get() = startSec + durationSec
    val isRamp: Boolean get() = fromFtp != toFtp

    /** Target as FTP fraction at [offsetSec] seconds into this step (linear for ramps). */
    fun targetFtpAt(offsetSec: Int): Double {
        if (!isRamp || durationSec == 0) return fromFtp
        val f = offsetSec.coerceIn(0, durationSec).toDouble() / durationSec
        return fromFtp + (toFtp - fromFtp) * f
    }

    fun targetWattsAt(offsetSec: Int, ftp: Int): Int = (targetFtpAt(offsetSec) * ftp).roundToInt()
}
