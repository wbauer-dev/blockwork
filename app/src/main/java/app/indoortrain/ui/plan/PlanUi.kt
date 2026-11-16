package app.indoortrain.ui.plan

import androidx.compose.foundation.Canvas
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Today
import androidx.compose.material.icons.filled.Timelapse
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import app.indoortrain.domain.plan.SessionStatus
import app.indoortrain.domain.plan.WeekKind
import app.indoortrain.ui.common.ConnectedGreen
import app.indoortrain.ui.common.ConnectingAmber
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private val dayFormat = DateTimeFormatter.ofPattern("EEE d MMM")
private val shortFormat = DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT)

fun LocalDate.dayLabel(): String = format(dayFormat)
fun LocalDate.shortLabel(): String = format(shortFormat)

@Composable
fun StatusIcon(status: SessionStatus) {
    when (status) {
        SessionStatus.Done -> Icon(Icons.Default.CheckCircle, "Done", tint = ConnectedGreen)
        SessionStatus.Partial -> Icon(Icons.Default.Timelapse, "Partly done", tint = ConnectingAmber)
        SessionStatus.Missed -> Icon(Icons.Default.Remove, "Missed", tint = MaterialTheme.colorScheme.outline)
        SessionStatus.Today -> Icon(Icons.Default.Today, "Today", tint = MaterialTheme.colorScheme.primary)
        SessionStatus.Upcoming -> Icon(Icons.Default.RadioButtonUnchecked, "Upcoming", tint = MaterialTheme.colorScheme.outline)
    }
}

/** Planned load per week (bars), recovery weeks lighter, current week highlighted. */
@Composable
fun WeeklyLoadChart(load: List<Int>, kinds: List<WeekKind>, currentWeek: Int?, modifier: Modifier = Modifier) {
    val primary = MaterialTheme.colorScheme.primary
    val light = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
    val marker = MaterialTheme.colorScheme.onSurface
    Canvas(modifier) {
        if (load.isEmpty()) return@Canvas
        val max = (load.max().coerceAtLeast(1)) * 1.1f
        val slot = size.width / load.size
        val barWidth = slot * 0.7f
        load.forEachIndexed { i, v ->
            val h = v / max * (size.height - 6f)
            val x = i * slot + (slot - barWidth) / 2
            val color: Color = if (kinds.getOrNull(i) == WeekKind.Recovery) light else primary
            drawRoundRect(color, Offset(x, size.height - h), Size(barWidth, h), CornerRadius(4f, 4f))
            if (i == currentWeek) {
                drawRect(marker, Offset(x, size.height - 3f), Size(barWidth, 3f))
            }
        }
    }
}
