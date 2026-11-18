package app.blockwork.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import app.blockwork.domain.HeartRateZone
import kotlin.math.ceil
import kotlin.math.roundToInt

internal val HeartRateLine = Color(0xFFD32F2F)

/**
 * Heart rate over the ride, on the same time axis as [WorkoutProfile] (x = second / [totalSec]),
 * so both charts line up when stacked. With [maxHeartRate] the zones are drawn as bands.
 * Gaps (null) break the line.
 */
@Composable
fun HeartRateChart(
    history: List<Int?>,
    totalSec: Int,
    modifier: Modifier = Modifier,
    maxHeartRate: Int? = null,
    progressSec: Int? = null,
) {
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    val markerColor = MaterialTheme.colorScheme.primary
    val measurer = rememberTextMeasurer()
    Canvas(modifier) {
        val (lo, hi) = bpmRange(history, maxHeartRate)
        val total = maxOf(totalSec, history.size, 1).toFloat()
        fun x(sec: Number) = sec.toFloat() / total * size.width
        fun y(bpm: Double) = size.height - ((bpm - lo) / (hi - lo)).toFloat().coerceIn(0f, 1f) * size.height

        if (maxHeartRate != null) {
            val lower = HeartRateZone.lowerBounds(maxHeartRate)
            for (zone in HeartRateZone.entries) {
                val from = lower.getValue(zone).toDouble().coerceAtLeast(lo.toDouble())
                val to = (zone.upperFraction * maxHeartRate).coerceAtMost(hi.toDouble())
                if (to <= from) continue
                drawRect(zone.color().copy(alpha = 0.18f), Offset(0f, y(to)), Size(size.width, y(from) - y(to)))
            }
        }

        // About one point per pixel column: average the samples that fall into it.
        val perPoint = ceil(history.size / size.width.coerceAtLeast(1f)).toInt().coerceAtLeast(1)
        val line = Path()
        var open = false
        var i = 0
        while (i < history.size) {
            val window = history.subList(i, minOf(i + perPoint, history.size)).filterNotNull()
            if (window.isEmpty()) {
                open = false
            } else {
                val p = Offset(x(i + 1), y(window.average()))
                if (open) line.lineTo(p.x, p.y) else line.moveTo(p.x, p.y)
                open = true
            }
            i += perPoint
        }
        drawPath(line, HeartRateLine, style = Stroke(width = 3f))

        drawText(measurer, "$hi", Offset(4f, 2f), labelStyle)
        val bottom = measurer.measure("$lo", labelStyle)
        drawText(bottom, topLeft = Offset(4f, size.height - bottom.size.height - 2f))

        progressSec?.let {
            val px = x(it.coerceAtMost(total.toInt()))
            drawLine(markerColor, Offset(px, 0f), Offset(px, size.height), strokeWidth = 5f, cap = StrokeCap.Round)
        }
    }
}

/**
 * Y range in bpm, rounded to 10s: half of max HR up to max HR when known, otherwise around the
 * readings; always widened to include every reading and at least 40 bpm tall.
 */
internal fun bpmRange(history: List<Int?>, maxHeartRate: Int?): Pair<Int, Int> {
    val values = history.filterNotNull()
    var lo = maxHeartRate?.let { it / 2 } ?: ((values.minOrNull() ?: 80) - 10)
    var hi = maxHeartRate ?: ((values.maxOrNull() ?: 160) + 10)
    values.minOrNull()?.let { lo = minOf(lo, it - 5) }
    values.maxOrNull()?.let { hi = maxOf(hi, it + 5) }
    lo = (lo / 10.0).toInt() * 10
    hi = (ceil(hi / 10.0)).roundToInt() * 10
    if (hi - lo < 40) hi = lo + 40
    return lo to hi
}
