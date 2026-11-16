package app.indoortrain.ui.training

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.indoortrain.domain.devices.ControlResult
import app.indoortrain.domain.training.RideSummary
import app.indoortrain.domain.training.SessionPhase
import app.indoortrain.domain.training.SessionState
import app.indoortrain.training.TrainingController
import app.indoortrain.ui.common.ConnectedGreen
import app.indoortrain.ui.common.ConnectingAmber
import app.indoortrain.ui.common.KeepScreenOn
import app.indoortrain.ui.common.WorkoutProfile
import app.indoortrain.ui.common.formatDuration
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.math.roundToInt

@HiltViewModel
class TrainingViewModel @Inject constructor(val controller: TrainingController) : ViewModel()

@Composable
fun TrainingScreen(onBack: () -> Unit, onDone: () -> Unit, viewModel: TrainingViewModel = hiltViewModel()) {
    val c = viewModel.controller
    val state by c.state.collectAsStateWithLifecycle()
    var confirmStop by remember { mutableStateOf(false) }
    KeepScreenOn()

    Scaffold { padding ->
        val s = state
        val content = Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp)
        when {
            s == null -> Column(content, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Header("Workout", null, onBack)
                Text("No workout running.")
                Button(onClick = onDone) { Text("Back") }
            }
            s.phase == SessionPhase.Finished -> Column(
                content.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Header(s.workoutName, null, onBack)
                Summary(s, onDone = { c.dismiss(); onDone() })
            }
            else -> Running(
                s,
                modifier = content,
                onBack = onBack,
                onPauseResume = { if (s.phase == SessionPhase.Paused) c.resume() else c.pause() },
                onSkip = { c.skipStep() },
                onExtend = { c.extendStep() },
                onIntensityUp = { c.increaseIntensity() },
                onIntensityDown = { c.decreaseIntensity() },
                onStop = { confirmStop = true },
            )
        }
    }

    if (confirmStop) {
        AlertDialog(
            onDismissRequest = { confirmStop = false },
            title = { Text("End workout?") },
            text = { Text("The ride so far is saved.") },
            confirmButton = { TextButton(onClick = { confirmStop = false; c.stop() }) { Text("End") } },
            dismissButton = { TextButton(onClick = { confirmStop = false }) { Text("Continue") } },
        )
    }
}

@Composable
private fun Header(title: String, subtitle: String?, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back (workout keeps running)") }
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        subtitle?.let { Text(it, style = MaterialTheme.typography.labelLarge) }
    }
}

/** Fits on one phone screen (designed for ~750 dp usable height); power card and chart absorb the rest. */
@Composable
private fun Running(
    s: SessionState,
    modifier: Modifier,
    onBack: () -> Unit,
    onPauseResume: () -> Unit,
    onSkip: () -> Unit,
    onExtend: () -> Unit,
    onIntensityUp: () -> Unit,
    onIntensityDown: () -> Unit,
    onStop: () -> Unit,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Header(s.workoutName, "${formatDuration(s.elapsedSec)} · ${formatDuration(s.remainingSec)} left", onBack)
        Warnings(s)
        PowerCard(s, Modifier.weight(1.4f))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Tile(
                label = s.currentStep.label ?: "Interval",
                value = formatDuration(s.stepRemainingSec),
                unit = "",
                footer = "Step ${s.stepIndex + 1}/${s.steps.size}",
                modifier = Modifier.weight(1.2f),
            )
            Tile("Heart rate", s.heartRateBpm?.toString(), "bpm", modifier = Modifier.weight(1f))
            Tile("Cadence", s.cadenceRpm?.toString(), "rpm", modifier = Modifier.weight(1f))
        }

        NextCard(s)

        WorkoutProfile(
            steps = s.steps,
            modifier = Modifier.fillMaxWidth().weight(1f).heightIn(min = 56.dp),
            progressSec = s.currentStep.startSec + s.stepElapsedSec,
            powerHistory = s.powerHistory,
            ftp = s.ftp,
            intensity = s.intensity,
        )

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = onIntensityDown, contentPadding = CompactPadding) { Text("−5 %") }
            Text(
                "${(s.intensity * 100).roundToInt()} %",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
            )
            OutlinedButton(onClick = onIntensityUp, contentPadding = CompactPadding) { Text("+5 %") }
            FilledTonalButton(onClick = onExtend, contentPadding = CompactPadding) { Text("+1 min") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 8.dp)) {
            val paused = s.phase == SessionPhase.Paused
            Button(onClick = onPauseResume, modifier = Modifier.weight(2f).height(52.dp)) {
                Icon(if (paused) Icons.Default.PlayArrow else Icons.Default.Pause, null)
                Text(if (paused) " Resume" else " Pause")
            }
            FilledTonalButton(onClick = onSkip, modifier = Modifier.weight(1f).height(52.dp), contentPadding = CompactPadding) {
                Icon(Icons.Default.SkipNext, null)
                Text("Skip")
            }
            OutlinedButton(onClick = onStop, modifier = Modifier.weight(1f).height(52.dp), contentPadding = CompactPadding) {
                Icon(Icons.Default.Stop, null)
                Text("End")
            }
        }
    }
}

private val CompactPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)

@Composable
private fun Warnings(s: SessionState) {
    val messages = buildList {
        if (s.phase == SessionPhase.Paused) add("Paused – trainer at easy resistance")
        if (!s.trainerConnected) add("Trainer disconnected – reconnecting…")
        when (s.controlProblem) {
            null, ControlResult.Success -> Unit
            ControlResult.NotConnected -> Unit
            else -> add("Trainer did not accept the target (${s.controlProblem}) – retrying")
        }
    }
    messages.forEach {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
            Text(it, Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun PowerCard(s: SessionState, modifier: Modifier = Modifier) {
    val target = s.targetWatts
    val power = s.power3s
    val tint: Color? = if (target != null && power != null && target > 0) {
        val ratio = power.toDouble() / target
        when {
            ratio < 0.95 -> ConnectingAmber
            ratio > 1.05 -> MaterialTheme.colorScheme.error
            else -> ConnectedGreen
        }
    } else {
        null
    }
    Card(
        modifier.fillMaxWidth(),
        colors = if (tint != null) {
            CardDefaults.cardColors(containerColor = tint.copy(alpha = 0.25f))
        } else {
            CardDefaults.cardColors()
        },
    ) {
        Column(
            Modifier.fillMaxSize().padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("Power (3 s)", style = MaterialTheme.typography.labelLarge)
            Text(power?.toString() ?: "--", fontSize = 84.sp, fontWeight = FontWeight.Bold, lineHeight = 90.sp)
            Text(
                "Target ${target ?: "–"} W",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun NextCard(s: SessionState) {
    val next = s.nextStep
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            if (next == null) {
                Text("Last interval", style = MaterialTheme.typography.titleMedium)
            } else {
                Text(
                    "Next in ${formatDuration(s.stepRemainingSec)}",
                    style = MaterialTheme.typography.labelLarge,
                )
                Text(
                    "${next.label ?: "Interval"} · ${formatDuration(next.durationSec)} @ ${s.nextStepWatts} W" +
                        if (next.isRamp) " (ramp)" else "",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun Tile(label: String, value: String?, unit: String, modifier: Modifier = Modifier, footer: String? = null) {
    Card(modifier) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(value ?: "--", fontSize = 34.sp, fontWeight = FontWeight.Bold, lineHeight = 38.sp, maxLines = 1)
                if (unit.isNotEmpty()) {
                    Text(" $unit", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(bottom = 5.dp))
                }
            }
            footer?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun Summary(s: SessionState, onDone: () -> Unit) {
    Text(
        if (s.completed) "Workout complete" else "Workout ended",
        style = MaterialTheme.typography.headlineMedium,
    )
    WorkoutProfile(
        steps = s.steps,
        modifier = Modifier.fillMaxWidth().height(110.dp),
        powerHistory = s.powerHistory,
        ftp = s.ftp,
        intensity = s.intensity,
    )
    val sum: RideSummary? = s.summary
    if (sum == null) {
        Text("Saving…")
    } else {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SummaryLine("Duration", formatDuration(sum.durationSec))
                SummaryLine("Average power", sum.avgPower?.let { "$it W" })
                SummaryLine("Normalized power", sum.normalizedPower?.let { "$it W" })
                SummaryLine("Max power", sum.maxPower?.let { "$it W" })
                SummaryLine("Average heart rate", sum.avgHeartRate?.let { "$it bpm" })
                SummaryLine("Max heart rate", sum.maxHeartRate?.let { "$it bpm" })
                SummaryLine("Average cadence", sum.avgCadence?.let { "$it rpm" })
                SummaryLine("Energy", "${sum.energyKj} kJ")
                SummaryLine("Intensity factor", sum.intensityFactor?.let { "%.2f".format(it) })
                SummaryLine("TSS", sum.tss?.toString())
                SummaryLine("Distance", "%.1f km".format(sum.distanceMeters / 1000.0))
            }
        }
        Text("The ride is saved on this phone.", style = MaterialTheme.typography.bodySmall)
    }
    Button(onClick = onDone, modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) { Text("Done") }
}

@Composable
private fun SummaryLine(label: String, value: String?) {
    Row {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Text(value ?: "–", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
    }
}
