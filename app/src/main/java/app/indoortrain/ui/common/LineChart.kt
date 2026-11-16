package app.indoortrain.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.runtime.Composable

data class ChartSeries(val values: List<Int?>, val color: Color, val dashed: Boolean = false)

/** Minimal time-series chart (x = sample index). Gaps (null) break the line. */
@Composable
fun LineChart(series: List<ChartSeries>, modifier: Modifier = Modifier, minY: Int = 0, maxY: Int? = null) {
    Canvas(modifier) {
        val count = series.maxOfOrNull { it.values.size } ?: 0
        if (count < 2) return@Canvas
        val top = maxY ?: ((series.flatMap { it.values }.filterNotNull().maxOrNull() ?: 1) * 1.1).toInt().coerceAtLeast(minY + 1)
        series.forEach { drawSeries(it, count, minY, top) }
    }
}

private fun DrawScope.drawSeries(s: ChartSeries, count: Int, minY: Int, maxY: Int) {
    // Downsample to about one point per pixel column.
    val step = (count / size.width).toInt().coerceAtLeast(1)
    val path = Path()
    var open = false
    var i = 0
    while (i < s.values.size) {
        val window = s.values.subList(i, minOf(i + step, s.values.size)).filterNotNull()
        if (window.isEmpty()) {
            open = false
        } else {
            val v = window.average()
            val p = Offset(
                i.toFloat() / (count - 1) * size.width,
                size.height - ((v - minY) / (maxY - minY)).toFloat().coerceIn(0f, 1f) * size.height,
            )
            if (open) path.lineTo(p.x, p.y) else path.moveTo(p.x, p.y)
            open = true
        }
        i += step
    }
    drawPath(
        path, s.color,
        style = Stroke(width = 3f, pathEffect = if (s.dashed) PathEffect.dashPathEffect(floatArrayOf(10f, 8f)) else null),
    )
}
