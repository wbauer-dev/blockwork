package app.indoortrain.ui.history

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import app.indoortrain.domain.training.Fitness
import app.indoortrain.domain.training.PeriodTotals
import app.indoortrain.domain.training.TrainingLoad
import app.indoortrain.domain.training.TrainingOverview
import app.indoortrain.domain.training.WeekLoad
import app.indoortrain.ui.common.noun
import app.indoortrain.ui.common.plural
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.ceil
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

/** Overview cards shown above the ride list: this week, weekly load chart, fitness and form. */
@Composable
fun TrainingOverviewSection(overview: TrainingOverview, today: LocalDate) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ThisWeekCard(overview)
        WeeklyLoadCard(overview.weeks)
        FitnessCard(overview.fitness, overview.firstRide, today)
    }
}

@Composable
private fun ThisWeekCard(o: TrainingOverview) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("This week", style = MaterialTheme.typography.titleMedium)
            Row {
                Stat("${o.thisWeek.rides}", noun(o.thisWeek.rides, "ride"), "last wk ${o.lastWeek.rides}", Modifier.weight(1f))
                Stat(formatHours(o.thisWeek.durationSec), "time", "last wk ${formatHours(o.lastWeek.durationSec)}", Modifier.weight(1.2f))
                Stat("${o.thisWeek.tss}", "TSS", "last wk ${o.lastWeek.tss}", Modifier.weight(1f))
                Stat(formatKj(o.thisWeek.energyKj), "kJ", "last wk ${formatKj(o.lastWeek.energyKj)}", Modifier.weight(1.1f))
            }
            Text(
                "All time: ${plural(o.allTime.rides, "ride")} · ${formatHours(o.allTime.durationSec)} · " +
                    "${o.allTime.tss} TSS · ${formatKj(o.allTime.energyKj)} kJ",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Stat(value: String, label: String, sub: String?, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, maxLines = 1)
        Text(label, style = MaterialTheme.typography.labelMedium)
        sub?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

private enum class WeeklyMetric(val title: String) {
    Tss("TSS"),
    Time("Time");

    fun value(t: PeriodTotals): Double = when (this) {
        Tss -> t.tss.toDouble()
        Time -> t.durationSec / 3600.0
    }

    fun axisLabel(v: Double): String = when (this) {
        Tss -> v.roundToInt().toString()
        Time -> if (v == v.roundToInt().toDouble()) "${v.roundToInt()} h" else "%.1f h".format(v)
    }
}

private val WeekFormat = DateTimeFormatter.ofPattern("d MMM")

@Composable
private fun WeeklyLoadCard(weeks: List<WeekLoad>) {
    var metric by rememberSaveable { mutableStateOf(WeeklyMetric.Tss) }
    var selected by rememberSaveable(weeks.size) { mutableIntStateOf(weeks.lastIndex) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Weekly load", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                SingleChoiceSegmentedButtonRow {
                    WeeklyMetric.entries.forEachIndexed { i, m ->
                        SegmentedButton(
                            selected = metric == m,
                            onClick = { metric = m },
                            shape = SegmentedButtonDefaults.itemShape(i, WeeklyMetric.entries.size),
                        ) { Text(m.title) }
                    }
                }
            }
            weeks.getOrNull(selected)?.let { w ->
                val t = w.totals
                Text(
                    (if (selected == weeks.lastIndex) "This week" else "Week of ${w.monday.format(WeekFormat)}") +
                        " · ${t.tss} TSS · ${formatHours(t.durationSec)} · ${plural(t.rides, "ride")}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            WeeklyBars(weeks, metric, selected, onSelect = { selected = it }, Modifier.fillMaxWidth().height(150.dp))
            Text(
                "Tap a bar for that week. TSS counts only rides with power.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** One bar per week, oldest left; y axis starts at zero with a few recessive grid lines. */
@Composable
private fun WeeklyBars(
    weeks: List<WeekLoad>,
    metric: WeeklyMetric,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier,
) {
    val barColor = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    val measurer = rememberTextMeasurer()
    val values = weeks.map { metric.value(it.totals) }
    val top = niceCeiling(values.maxOrNull() ?: 0.0, if (metric == WeeklyMetric.Tss) 100.0 else 2.0)
    val description = "${metric.title} per week, oldest first: " + weeks.indices.joinToString("; ") { i ->
        "${weeks[i].monday.format(WeekFormat)} ${metric.axisLabel(values[i])}"
    }
    val gutter = remember(top, metric) { measurer.measure(metric.axisLabel(top), labelStyle).size.width + 6f }

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
        fun y(v: Double) = plotBottom - (v / top).toFloat() * plotBottom

        for (tick in listOf(0.0, top / 2, top)) {
            val ty = y(tick)
            drawLine(gridColor, Offset(gutter, ty), Offset(size.width, ty), strokeWidth = 1f)
            val label = measurer.measure(metric.axisLabel(tick), labelStyle)
            drawText(label, topLeft = Offset(0f, (ty - label.size.height / 2f).coerceIn(0f, plotBottom - label.size.height)))
        }

        val slot = (size.width - gutter) / weeks.size
        val barWidth = slot * 0.6f
        val radius = 4.dp.toPx()
        values.forEachIndexed { i, v ->
            if (v <= 0) return@forEachIndexed
            val left = gutter + i * slot + (slot - barWidth) / 2
            val barTop = y(v).coerceAtMost(plotBottom - 1f)
            // Rounded data end, square at the baseline.
            val path = Path().apply {
                addRoundRect(
                    RoundRect(
                        left, barTop, left + barWidth, plotBottom,
                        topLeftCornerRadius = CornerRadius(radius), topRightCornerRadius = CornerRadius(radius),
                    ),
                )
            }
            drawPath(path, if (i == selected) barColor else barColor.copy(alpha = 0.45f))
        }

        val first = measurer.measure(weeks.first().monday.format(WeekFormat), labelStyle)
        drawText(first, topLeft = Offset(gutter, plotBottom + 4f))
        val last = measurer.measure("This week", labelStyle)
        drawText(last, topLeft = Offset(size.width - last.size.width, plotBottom + 4f))
    }
}

@Composable
private fun FitnessCard(fitness: Fitness?, firstRide: LocalDate?, today: LocalDate) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Fitness and form", style = MaterialTheme.typography.titleMedium)
            if (fitness == null) {
                Text("Needs rides with power data.", style = MaterialTheme.typography.bodyMedium)
                return@Column
            }
            Row {
                Stat(fitness.fitness.roundToInt().toString(), "Fitness", "6-week load", Modifier.weight(1f))
                Stat(fitness.fatigue.roundToInt().toString(), "Fatigue", "1-week load", Modifier.weight(1f))
                val form = fitness.form.roundToInt()
                Stat(if (form > 0) "+$form" else form.toString().replace('-', '−'), "Form", "fitness − fatigue", Modifier.weight(1f))
            }
            Text(fitness.formLevel.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                "Fitness and fatigue are the average daily TSS over about 6 weeks and 1 week, with recent days " +
                    "counting more. Negative form after hard weeks is normal; rest days bring it back up.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (firstRide != null && ChronoUnit.DAYS.between(firstRide, today) < TrainingLoad.FITNESS_DAYS) {
                Text(
                    "Your history is shorter than 6 weeks, so fitness is still building up and reads low.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 9000 -> "2:30 h". */
private fun formatHours(sec: Int): String {
    val min = (sec + 30) / 60
    return "%d:%02d h".format(min / 60, min % 60)
}

private fun formatKj(kj: Int): String = "%,d".format(kj)

/** Smallest "nice" number (1, 2, 2.5, 5 × 10ⁿ) ≥ [v], at least [minimum]; keeps the half-way grid line round. */
internal fun niceCeiling(v: Double, minimum: Double): Double {
    val target = maxOf(v, minimum)
    val magnitude = 10.0.pow(kotlin.math.floor(log10(target)))
    val step = listOf(1.0, 2.0, 2.5, 5.0, 10.0).first { it * magnitude >= target - 1e-9 }
    return ceil(step * magnitude * 1e6) / 1e6
}
