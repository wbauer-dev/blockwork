package app.blockwork.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/** The main series, drawn as a line; null values break it. */
class ChartLine(val values: List<Int?>, val color: Color)

/** A filled area under [values] (e.g. the target power), coloured per sample by [colors]. */
class ChartArea(val values: List<Int?>, val colors: List<Color>)

/** A horizontal background band between two y values (e.g. a heart-rate zone). */
class ChartBand(val from: Double, val to: Double, val color: Color)

/** A dashed horizontal reference line with a label at its right end (e.g. FTP). */
class ChartRule(val value: Int, val label: String)

/**
 * Chart of a recorded ride: x = [seconds] (one entry per sample, running time), y = [yRange] with
 * a labelled gridline every [yStep] and time labels below. The y labels sit in a fixed-width
 * gutter so stacked charts of the same ride line up.
 */
@Composable
fun RideChart(
    seconds: List<Int>,
    line: ChartLine,
    yRange: IntRange,
    yStep: Int,
    modifier: Modifier = Modifier,
    area: ChartArea? = null,
    bands: List<ChartBand> = emptyList(),
    rule: ChartRule? = null,
) {
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val ruleColor = MaterialTheme.colorScheme.onSurfaceVariant
    val measurer = rememberTextMeasurer()
    Canvas(modifier) {
        if (seconds.size < 2) return@Canvas
        val gutter = 36.dp.toPx()
        val labelGap = 4.dp.toPx()
        val axisHeight = measurer.measure("0", labelStyle).size.height + labelGap
        val left = gutter
        val right = size.width
        val top = labelGap // room for the top gridline label
        val bottom = size.height - axisHeight
        if (right - left < 10f || bottom - top < 10f) return@Canvas
        val lo = yRange.first.toDouble()
        val hi = yRange.last.toDouble()
        val total = seconds.last().coerceAtLeast(1).toFloat()
        fun x(sec: Int) = left + sec / total * (right - left)
        fun y(v: Double) = bottom - ((v - lo) / (hi - lo)).toFloat().coerceIn(0f, 1f) * (bottom - top)

        for (band in bands) {
            val from = band.from.coerceIn(lo, hi)
            val to = band.to.coerceIn(lo, hi)
            if (to > from) drawRect(band.color, Offset(left, y(to)), Size(right - left, y(from) - y(to)))
        }

        area?.let { drawArea(it, seconds, ::x, ::y, right, bottom) }

        // Horizontal gridlines with value labels.
        var tick = ceil(lo / yStep) * yStep
        while (tick <= hi) {
            val py = y(tick)
            drawLine(gridColor, Offset(left, py), Offset(right, py), strokeWidth = 1.dp.toPx())
            val text = measurer.measure(tick.toInt().toString(), labelStyle)
            val ty = (py - text.size.height / 2f).coerceIn(0f, bottom - text.size.height)
            drawText(text, topLeft = Offset(left - labelGap - text.size.width, ty))
            tick += yStep
        }

        // Time axis: as many evenly spaced labels as fit.
        val widest = measurer.measure(formatDuration(seconds.last()), labelStyle).size.width
        val step = timeTickStep(seconds.last(), maxTicks = ((right - left) / (widest * 1.8f)).toInt())
        var sec = 0
        while (sec <= seconds.last()) {
            val px = x(sec)
            if (sec > 0) drawLine(gridColor, Offset(px, top), Offset(px, bottom), strokeWidth = 1.dp.toPx())
            val text = measurer.measure(formatDuration(sec), labelStyle)
            val tx = (px - text.size.width / 2f).coerceIn(left, right - text.size.width)
            drawText(text, topLeft = Offset(tx, bottom + labelGap))
            sec += step
        }
        drawLine(gridColor, Offset(left, bottom), Offset(right, bottom), strokeWidth = 1.dp.toPx())

        rule?.let { drawRule(it, y(it.value.toDouble()), left, right, ruleColor, measurer, labelStyle) }

        val path = Path()
        var open = false
        for (p in columns(seconds, line.values, ::x)) {
            val v = p.value
            if (v == null) {
                open = false
                continue
            }
            if (open) path.lineTo(p.x, y(v)) else path.moveTo(p.x, y(v))
            open = true
        }
        drawPath(path, line.color, style = Stroke(width = 1.5.dp.toPx()))
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawRule(
    rule: ChartRule, py: Float, left: Float, right: Float, color: Color, measurer: TextMeasurer, style: TextStyle,
) {
    drawLine(
        color, Offset(left, py), Offset(right, py),
        strokeWidth = 1.dp.toPx(),
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())),
    )
    val text = measurer.measure(rule.label, style)
    drawText(text, topLeft = Offset(right - text.size.width - 2.dp.toPx(), (py - text.size.height).coerceAtLeast(0f)))
}

/** One closed path per run of equal colour, so neighbouring columns join without seams. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawArea(
    area: ChartArea, seconds: List<Int>, x: (Int) -> Float, y: (Double) -> Float, right: Float, bottom: Float,
) {
    val points = columns(seconds, area.values, x, area.colors)
    var i = 0
    while (i < points.size) {
        val color = points[i].color
        if (points[i].value == null || color == null) {
            i++
            continue
        }
        val path = Path().apply { moveTo(points[i].x, bottom) }
        var j = i
        while (j < points.size && points[j].value != null && points[j].color == color) {
            val v = points[j].value!!
            val end = points.getOrNull(j + 1)?.x ?: right
            path.lineTo(points[j].x, y(v))
            path.lineTo(end, y(v))
            j++
        }
        path.lineTo(points.getOrNull(j)?.x ?: right, bottom)
        path.close()
        drawPath(path, color)
        i = j
    }
}

private class ColumnPoint(val x: Float, val value: Double?, val color: Color? = null)

/** One point per pixel column: the average of the samples that fall into it (null if none has a value). */
private fun columns(
    seconds: List<Int>, values: List<Int?>, x: (Int) -> Float, colors: List<Color>? = null,
): List<ColumnPoint> {
    val out = ArrayList<ColumnPoint>()
    var column = Int.MIN_VALUE
    var startX = 0f
    var color: Color? = null
    var sum = 0.0
    var n = 0
    fun flush() {
        if (column != Int.MIN_VALUE) out += ColumnPoint(startX, if (n > 0) sum / n else null, color)
    }
    for (i in 0 until minOf(seconds.size, values.size)) {
        val px = x(seconds[i])
        if (px.toInt() != column) {
            flush()
            column = px.toInt()
            startX = px
            color = colors?.getOrNull(i)
            sum = 0.0
            n = 0
        }
        values[i]?.let { sum += it; n++ }
    }
    flush()
    return out
}

/** Smallest "nice" step (1, 2 or 5 × 10ⁿ) that splits [span] into at most [maxTicks] intervals. */
internal fun niceStep(span: Int, maxTicks: Int): Int {
    val raw = span.coerceAtLeast(1).toDouble() / maxTicks.coerceAtLeast(1)
    val magnitude = 10.0.pow(floor(log10(raw)))
    val nice = listOf(1.0, 2.0, 5.0, 10.0).first { it * magnitude >= raw - 1e-9 } * magnitude
    return nice.toInt().coerceAtLeast(1)
}

private val TimeSteps = listOf(10, 15, 30, 60, 120, 300, 600, 900, 1200, 1800, 3600, 7200)

/** Smallest round time step (10 s … 2 h) that puts at most [maxTicks] intervals into [totalSec]. */
internal fun timeTickStep(totalSec: Int, maxTicks: Int): Int =
    TimeSteps.firstOrNull { totalSec / it <= maxTicks.coerceAtLeast(1) } ?: TimeSteps.last()
