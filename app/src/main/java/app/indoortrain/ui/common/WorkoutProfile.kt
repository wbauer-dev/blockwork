package app.indoortrain.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import app.indoortrain.domain.PowerZone
import app.indoortrain.domain.Step

/** Coggan zone colours (grey → red). */
fun PowerZone.color(): Color = when (this) {
    PowerZone.Z1 -> Color(0xFF9E9E9E)
    PowerZone.Z2 -> Color(0xFF2196F3)
    PowerZone.Z3 -> Color(0xFF43A047)
    PowerZone.Z4 -> Color(0xFFFDD835)
    PowerZone.Z5 -> Color(0xFFFB8C00)
    PowerZone.Z6 -> Color(0xFFE53935)
}

/**
 * Workout profile: one block per step, coloured by zone, height = % FTP.
 * Optionally draws the ridden power (needs [ftp]) and a progress marker.
 */
@Composable
fun WorkoutProfile(
    steps: List<Step>,
    modifier: Modifier = Modifier,
    progressSec: Int? = null,
    powerHistory: List<Int?> = emptyList(),
    ftp: Int? = null,
    intensity: Double = 1.0,
) {
    val lineColor = MaterialTheme.colorScheme.onSurface
    val markerColor = MaterialTheme.colorScheme.primary
    Canvas(modifier) {
        if (steps.isEmpty()) return@Canvas
        val total = steps.last().endSec.coerceAtLeast(1).toFloat()
        val maxFtp = maxOf(1.3, steps.maxOf { maxOf(it.fromFtp, it.toFtp) } * intensity * 1.1)
        fun x(sec: Number) = sec.toFloat() / total * size.width
        fun y(ftpFraction: Double) = size.height - (ftpFraction / maxFtp).toFloat() * size.height

        for ((i, step) in steps.withIndex()) {
            val from = step.fromFtp * intensity
            val to = step.toFtp * intensity
            val path = Path().apply {
                moveTo(x(step.startSec), size.height)
                lineTo(x(step.startSec), y(from))
                lineTo(x(step.endSec), y(to))
                lineTo(x(step.endSec), size.height)
                close()
            }
            val done = progressSec != null && step.endSec <= progressSec
            val alpha = if (progressSec == null || done || i == steps.indexOfFirst { it.endSec > progressSec }) 1f else 0.55f
            drawPath(path, PowerZone.forFtpFraction((from + to) / 2).color().copy(alpha = alpha))
        }

        if (ftp != null && powerHistory.size > 1) {
            val line = Path()
            var started = false
            powerHistory.forEachIndexed { sec, watts ->
                if (watts == null) {
                    started = false
                    return@forEachIndexed
                }
                val p = Offset(x(sec + 1), y(watts.toDouble() / ftp).coerceIn(0f, size.height))
                if (started) line.lineTo(p.x, p.y) else line.moveTo(p.x, p.y)
                started = true
            }
            drawPath(line, lineColor, style = Stroke(width = 2f))
        }

        progressSec?.let {
            val px = x(it.coerceAtMost(total.toInt()))
            drawLine(markerColor, Offset(px, 0f), Offset(px, size.height), strokeWidth = 5f, cap = StrokeCap.Round)
        }
    }
}
