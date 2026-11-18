package app.blockwork.ui.plan

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.blockwork.data.plans.PlanRepository
import app.blockwork.domain.plan.PlanGenerator
import app.blockwork.domain.plan.PlanGoal
import app.blockwork.domain.plan.PlanRequest
import app.blockwork.domain.plan.TrainingPlan
import app.blockwork.domain.plan.WeekKind
import app.blockwork.ui.common.formatMinutes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import javax.inject.Inject
import kotlin.math.roundToInt

@HiltViewModel
class PlanWizardViewModel @Inject constructor(private val plans: PlanRepository) : ViewModel() {
    val existing = plans.activePlan.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun create(request: PlanRequest, onCreated: () -> Unit) {
        viewModelScope.launch {
            plans.create(request)
            onCreated()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlanWizardScreen(onBack: () -> Unit, onCreated: () -> Unit, viewModel: PlanWizardViewModel = hiltViewModel()) {
    val existing by viewModel.existing.collectAsStateWithLifecycle()
    val locale = LocalConfiguration.current.locales[0]
    var goal by remember { mutableStateOf(PlanGoal.FtpBuild) }
    var weeks by remember { mutableIntStateOf(8) }
    var days by remember { mutableStateOf(setOf(DayOfWeek.TUESDAY, DayOfWeek.THURSDAY, DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)) }
    var minutes by remember { mutableIntStateOf(60) }
    var nextWeek by remember { mutableStateOf(LocalDate.now().dayOfWeek >= DayOfWeek.THURSDAY) }
    var confirmReplace by remember { mutableStateOf(false) }

    val thisMonday = TrainingPlan.mondayOf(LocalDate.now())
    val start = if (nextWeek) thisMonday.plusWeeks(1) else thisMonday
    val valid = days.size in PlanRequest.DAYS
    val request = PlanRequest(goal, weeks, days, minutes, start)
    val preview = remember(request, valid) { if (valid) PlanGenerator.generate(request, "preview") else null }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("New training plan") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Goal", style = MaterialTheme.typography.titleMedium)
            PlanGoal.entries.forEach { g ->
                Card(
                    Modifier.fillMaxWidth().clickable { goal = g },
                    colors = if (g == goal) {
                        CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                    } else {
                        CardDefaults.cardColors()
                    },
                ) {
                    Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = g == goal, onClick = { goal = g })
                        Column {
                            Text(g.title, style = MaterialTheme.typography.titleSmall)
                            Text(g.description, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            Text("Length: $weeks weeks", style = MaterialTheme.typography.titleMedium)
            Slider(
                value = weeks.toFloat(), onValueChange = { weeks = it.roundToInt() },
                valueRange = PlanRequest.WEEKS.first.toFloat()..PlanRequest.WEEKS.last.toFloat(),
                steps = PlanRequest.WEEKS.last - PlanRequest.WEEKS.first - 1,
            )

            Text("Training days (${days.size})", style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                DayOfWeek.entries.forEach { d ->
                    FilterChip(
                        selected = d in days,
                        onClick = { days = if (d in days) days - d else days + d },
                        label = { Text(d.getDisplayName(TextStyle.SHORT, locale)) },
                    )
                }
            }
            if (!valid) {
                Text("Choose 2 to 6 days.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            Text("Session length: $minutes min (long ride ${(minutes * 1.5).roundToInt().coerceAtMost(180)} min)", style = MaterialTheme.typography.titleMedium)
            Slider(
                value = minutes.toFloat(), onValueChange = { minutes = (it / 15).roundToInt() * 15 },
                valueRange = PlanRequest.MINUTES.first.toFloat()..PlanRequest.MINUTES.last.toFloat(),
                steps = (PlanRequest.MINUTES.last - PlanRequest.MINUTES.first) / 15 - 1,
            )

            Text("Start", style = MaterialTheme.typography.titleMedium)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(selected = !nextWeek, onClick = { nextWeek = false }, shape = SegmentedButtonDefaults.itemShape(0, 2)) {
                    Text("This week (${thisMonday.dayLabel()})")
                }
                SegmentedButton(selected = nextWeek, onClick = { nextWeek = true }, shape = SegmentedButtonDefaults.itemShape(1, 2)) {
                    Text("Next week")
                }
            }

            preview?.let { p ->
                val build = p.weeks.getOrNull(1)?.takeIf { it.kind == WeekKind.Build } ?: p.weeks.first()
                val hours = p.weeks.filter { it.kind == WeekKind.Build }
                    .map { w -> w.sessions.sumOf { it.workout.durationSec } / 3600.0 }.average()
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Typical build week (≈ ${"%.1f".format(hours)} h)", style = MaterialTheme.typography.titleSmall)
                        build.sessions.forEach {
                            Text(
                                "${it.day.getDisplayName(TextStyle.SHORT, locale)}  ${it.workout.name} · ${formatMinutes(it.workout.durationSec)}",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        val recovery = p.weeks.count { it.kind == WeekKind.Recovery }
                        Text(
                            "$recovery recovery week(s) with FTP test · ${p.sessions.size} sessions · ends ${p.endDate.dayLabel()}",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }

            Button(
                onClick = { if (existing != null) confirmReplace = true else viewModel.create(request, onCreated) },
                enabled = valid,
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            ) { Text("Create plan") }
        }
    }

    if (confirmReplace) {
        AlertDialog(
            onDismissRequest = { confirmReplace = false },
            title = { Text("Replace current plan?") },
            text = { Text("\"${existing?.name}\" is replaced. Your recorded rides stay in History.") },
            confirmButton = {
                TextButton(onClick = { confirmReplace = false; viewModel.create(request, onCreated) }) { Text("Replace") }
            },
            dismissButton = { TextButton(onClick = { confirmReplace = false }) { Text("Cancel") } },
        )
    }
}
