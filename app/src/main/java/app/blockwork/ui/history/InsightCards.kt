package app.blockwork.ui.history

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import app.blockwork.domain.training.HeartRateAtPower
import app.blockwork.domain.training.HrAtPowerResult
import app.blockwork.domain.training.IntensityDistribution
import app.blockwork.domain.training.IntensityDistributionResult
import app.blockwork.domain.training.TidSummary
import app.blockwork.domain.training.ZoneSeconds
import app.blockwork.ui.common.HeartRateLine
import app.blockwork.ui.common.plural
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/** Three-zone colours, low to high; validated for colour-vision deficiency on light and dark surfaces. */
private val ZoneColors = listOf(Color(0xFF2196F3), Color(0xFFCC7A00), Color(0xFFE0365F))
private val ZoneNames = listOf("Low", "Threshold", "High")

private fun ZoneSeconds.values() = listOf(z1, z2, z3)

private fun percent(part: Int, total: Int) = if (total == 0) 0 else (part * 100.0 / total).roundToInt()

private enum class TidSource(val title: String) { Power("Power"), HeartRate("Heart rate") }

@Composable
fun IntensityDistributionCard(result: IntensityDistributionResult, hasMaxHeartRate: Boolean) {
    var source by rememberSaveable { mutableStateOf(TidSource.Power) }
    var selected by rememberSaveable(result.weeks.size) { mutableIntStateOf(result.weeks.lastIndex) }
    val weeks = result.weeks.map { if (source == TidSource.Power) it.power else it.heartRate }
    val summary: TidSummary? = if (source == TidSource.Power) result.recentPower else result.recentHeartRate

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Intensity distribution", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                SingleChoiceSegmentedButtonRow {
                    TidSource.entries.forEachIndexed { i, s ->
                        SegmentedButton(
                            selected = source == s,
                            onClick = { source = s },
                            shape = SegmentedButtonDefaults.itemShape(i, TidSource.entries.size),
                            icon = {}, // the check mark would clip "Heart rate" on narrow phones
                        ) { Text(s.title) }
                    }
                }
            }
            if (summary == null) {
                Text("Set your max heart rate in Settings to see heart-rate zones.", style = MaterialTheme.typography.bodyMedium)
                return@Column
            }

            Text("Last 4 weeks", style = MaterialTheme.typography.labelLarge)
            val total = summary.zones.total
            Row {
                summary.zones.values().forEachIndexed { i, sec ->
                    ZoneStat(ZoneColors[i], "${percent(sec, total)} %", ZoneNames[i], formatHours(sec), Modifier.weight(1f))
                }
            }
            Text(tidVerdict(summary), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)

            weeks.getOrNull(selected)?.let { w ->
                val label = if (selected == weeks.lastIndex) "This week" else "Week of ${result.weeks[selected].monday.format(WeekFormat)}"
                Text(
                    if (w.total == 0) "$label · no data" else
                        "$label · " + w.values().mapIndexed { i, s -> "${ZoneNames[i]} ${percent(s, w.total)} %" }.joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            StackedShareBars(
                weeks = weeks,
                firstMonday = result.weeks.first().monday,
                selected = selected,
                onSelect = { selected = it },
                modifier = Modifier.fillMaxWidth().height(130.dp),
            )
            Text(
                when (source) {
                    TidSource.Power ->
                        "Share of riding time per week. Low = up to ${pct(IntensityDistribution.POWER_Z1_MAX_FTP)} FTP, " +
                            "threshold = up to ${pct(IntensityDistribution.POWER_Z2_MAX_FTP)}, high = above (FTP at the " +
                            "time of each ride). FTP-based zones approximate your personal thresholds."
                    TidSource.HeartRate ->
                        "Share of riding time per week. Low = below 82 % of max heart rate, threshold = 82–87 %, " +
                            "high = 88 % and above (Seiler 2010). Heart rate lags behind short intervals, so high " +
                            "intensity reads low here."
                } + " Three-zone model after Seiler; polarization index (PI) and type after Treff et al. 2019.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (source == TidSource.Power && !hasMaxHeartRate) {
                Text(
                    "Set your max heart rate in Settings to compare with heart-rate zones.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun pct(fraction: Double) = "${(fraction * 100).roundToInt()} %"

private fun tidVerdict(s: TidSummary): String {
    val type = s.type ?: return "Less than an hour of riding – not enough to classify."
    val pi = s.polarizationIndex?.let { " · PI %.2f".format(it) } ?: ""
    return type.title + pi
}

@Composable
private fun ZoneStat(color: Color, value: String, label: String, sub: String, modifier: Modifier) {
    Column(modifier) {
        Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, maxLines = 1)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.size(10.dp).background(color, RoundedCornerShape(2.dp)))
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
        Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

/** One 100 % column per week, zone 1 at the bottom; 2 dp gaps separate the segments. */
@Composable
private fun StackedShareBars(
    weeks: List<ZoneSeconds>,
    firstMonday: LocalDate,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier,
) {
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val measurer = rememberTextMeasurer()
    val description = "Share of time in low, threshold and high zones per week, oldest first: " +
        weeks.indices.joinToString("; ") { i ->
            val w = weeks[i]
            if (w.total == 0) "no data" else w.values().mapIndexed { z, s -> "${ZoneNames[z]} ${percent(s, w.total)} %" }.joinToString(", ")
        }
    val gutter = remember { measurer.measure("100 %", labelStyle).size.width + 6f }
    Canvas(
        modifier
            .semantics { contentDescription = description }
            .pointerInput(weeks.size, gutter) {
                detectTapGestures { pos ->
                    val slot = (size.width - gutter) / weeks.size
                    val i = ((pos.x - gutter) / slot).toInt()
                    if (pos.x >= gutter && i in weeks.indices) onSelect(i)
                }
            },
    ) {
        val xLabelHeight = measurer.measure("0", labelStyle).size.height + 4f
        val plotBottom = size.height - xLabelHeight
        for (tick in listOf(0, 50, 100)) {
            val ty = plotBottom - tick / 100f * plotBottom
            drawLine(gridColor, Offset(gutter, ty), Offset(size.width, ty), strokeWidth = 1f)
            val label = measurer.measure("$tick %", labelStyle)
            drawText(label, topLeft = Offset(0f, (ty - label.size.height / 2f).coerceIn(0f, plotBottom - label.size.height)))
        }
        val slot = (size.width - gutter) / weeks.size
        val barWidth = minOf(slot * 0.6f, 24.dp.toPx())
        val gap = 2.dp.toPx()
        val radius = 4.dp.toPx()
        weeks.forEachIndexed { i, w ->
            if (w.total == 0) return@forEachIndexed
            val left = gutter + i * slot + (slot - barWidth) / 2
            val alpha = if (i == selected) 1f else 0.5f
            var bottom = plotBottom
            val parts = w.values().withIndex().filter { it.value > 0 }
            parts.forEachIndexed { n, (zone, sec) ->
                val h = sec.toFloat() / w.total * plotBottom
                val top = bottom - h
                val isTop = n == parts.lastIndex
                // Gap below every segment except the lowest.
                val segBottom = if (bottom < plotBottom) bottom - gap / 2 else bottom
                val segTop = if (isTop) top else top + gap / 2
                if (segBottom - segTop > 0.5f) {
                    val path = Path().apply {
                        addRoundRect(
                            RoundRect(
                                left, segTop, left + barWidth, segBottom,
                                topLeftCornerRadius = CornerRadius(if (isTop) radius else 0f),
                                topRightCornerRadius = CornerRadius(if (isTop) radius else 0f),
                            ),
                        )
                    }
                    drawPath(path, ZoneColors[zone].copy(alpha = alpha))
                }
                bottom = top
            }
        }
        val first = measurer.measure(firstMonday.format(WeekFormat), labelStyle)
        drawText(first, topLeft = Offset(gutter, plotBottom + 4f))
        val last = measurer.measure("This week", labelStyle)
        drawText(last, topLeft = Offset(size.width - last.size.width, plotBottom + 4f))
    }
}

@Composable
fun HeartRateAtPowerCard(result: HrAtPowerResult?, today: LocalDate) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val points = result?.points.orEmpty()
            Text(
                if (result == null) "Heart rate at fixed power" else "Heart rate at ${result.referenceWatts} W",
                style = MaterialTheme.typography.titleMedium,
            )
            if (result == null || points.size < 2) {
                Text(
                    "Needs at least two rides with a heart-rate strap and steady steps of 3 minutes or longer " +
                        "between ${pct(HeartRateAtPower.MIN_FTP)} and ${pct(HeartRateAtPower.MAX_FTP)} FTP, such as endurance rides.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                return@Column
            }

            var selected by remember(points) { mutableIntStateOf(points.lastIndex) }
            val change = result.change
            if (change != null) {
                val delta = change.delta
                Row {
                    Stat("%.0f".format(change.recentMean), "bpm", "last 4 weeks", Modifier.weight(1f))
                    Stat(signed(delta), "bpm", "vs 4 weeks before", Modifier.weight(1f))
                    Stat("±%.1f".format(change.typicalError), "bpm", "ride-to-ride noise", Modifier.weight(1f))
                }
                Text(
                    when {
                        !change.isClear -> "No clear change – within normal ride-to-ride variation."
                        delta < 0 -> "Lower heart rate at the same power – usually a sign of better aerobic fitness."
                        else -> "Higher heart rate at the same power – lost fitness, but also heat, poor sleep, " +
                            "illness or accumulated fatigue can cause this."
                    },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            } else {
                Text(
                    "Needs at least two such rides in each of the last two 4-week periods to show a change" +
                        (result.typicalError?.let { " (ride-to-ride noise so far: ±%.1f bpm)".format(it) } ?: "") + ".",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            points.getOrNull(selected)?.let { p ->
                Text(
                    "${p.date.format(WeekFormat)}: %.0f bpm · %d min steady".format(p.bpm, p.seconds / 60),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            HrPointsChart(result, today, selected, { selected = it }, Modifier.fillMaxWidth().height(150.dp))
            Text(
                "Dots: single rides · line: average of the last ${TRAILING} rides. Lower is fitter. Uses the steady " +
                    "part of steps between ${pct(HeartRateAtPower.MIN_FTP)} and ${pct(HeartRateAtPower.MAX_FTP)} FTP" +
                    (result.slopeBpmPerWatt?.let { s -> "; nearby powers are adjusted by %.2f bpm per watt, fitted from your rides".format(s) } ?: "") +
                    ". Heat, hydration, sleep, caffeine and fatigue move heart rate too, and heavy fatigue can lower it. " +
                    "After Lamberts et al. 2011 and Buchheit 2014.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "${plural(points.size, "ride")} so far.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private const val TRAILING = 5

private fun signed(v: Double): String {
    val r = (v * 10).roundToInt() / 10.0
    return when {
        r > 0 -> "+%.1f".format(r)
        r < 0 -> "−%.1f".format(-r)
        else -> "0.0"
    }
}

@Composable
private fun HrPointsChart(
    result: HrAtPowerResult,
    today: LocalDate,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier,
) {
    val points = result.points
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val ringColor = MaterialTheme.colorScheme.surfaceContainerHighest
    val measurer = rememberTextMeasurer()
    val first = points.first().date
    val spanDays = maxOf(ChronoUnit.DAYS.between(first, today), 1L).toFloat()
    val lo = floor((points.minOf { it.bpm } - 2) / 5) * 5
    val hi = maxOf(ceil((points.maxOf { it.bpm } + 2) / 5) * 5, lo + 10)
    val trailing = points.indices.map { i -> points.subList(maxOf(0, i - TRAILING + 1), i + 1).map { it.bpm }.average() }
    val description = "Heart rate at ${result.referenceWatts} watts per ride, oldest first: " +
        points.joinToString("; ") { "${it.date.format(WeekFormat)} ${it.bpm.roundToInt()} bpm" }
    val gutter = remember(hi) { measurer.measure("${hi.roundToInt()}", labelStyle).size.width + 6f }

    Canvas(
        modifier
            .semantics { contentDescription = description }
            .pointerInput(points, gutter) {
                detectTapGestures { pos ->
                    val pad = 6.dp.toPx()
                    val w = size.width - gutter - 2 * pad
                    val nearest = points.indices.minByOrNull { i ->
                        abs(gutter + pad + ChronoUnit.DAYS.between(first, points[i].date) / spanDays * w - pos.x)
                    }
                    nearest?.let(onSelect)
                }
            },
    ) {
        val pad = 6.dp.toPx()
        val xLabelHeight = measurer.measure("0", labelStyle).size.height + 4f
        val plotTop = pad
        val plotBottom = size.height - xLabelHeight - pad
        val plotWidth = size.width - gutter - 2 * pad
        fun x(d: LocalDate) = gutter + pad + ChronoUnit.DAYS.between(first, d) / spanDays * plotWidth
        fun y(bpm: Double) = plotBottom - ((bpm - lo) / (hi - lo)).toFloat() * (plotBottom - plotTop)

        for (tick in listOf(lo, (lo + hi) / 2, hi)) {
            val ty = y(tick)
            drawLine(gridColor, Offset(gutter, ty), Offset(size.width, ty), strokeWidth = 1f)
            val label = measurer.measure("${tick.roundToInt()}", labelStyle)
            drawText(label, topLeft = Offset(0f, ty - label.size.height / 2f))
        }

        val line = Path()
        points.forEachIndexed { i, p ->
            val o = Offset(x(p.date), y(trailing[i]))
            if (i == 0) line.moveTo(o.x, o.y) else line.lineTo(o.x, o.y)
        }
        drawPath(line, HeartRateLine, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))

        val r = 4.dp.toPx()
        val ring = 2.dp.toPx()
        points.forEachIndexed { i, p ->
            val c = Offset(x(p.date), y(p.bpm))
            val radius = if (i == selected) r * 1.5f else r
            drawCircle(ringColor, radius + ring, c)
            drawCircle(HeartRateLine.copy(alpha = if (i == selected) 1f else 0.55f), radius, c)
        }

        val firstLabel = measurer.measure(first.format(WeekFormat), labelStyle)
        drawText(firstLabel, topLeft = Offset(gutter, size.height - firstLabel.size.height))
        val lastLabel = measurer.measure("Today", labelStyle)
        drawText(lastLabel, topLeft = Offset(size.width - lastLabel.size.width, size.height - lastLabel.size.height))
    }
}
