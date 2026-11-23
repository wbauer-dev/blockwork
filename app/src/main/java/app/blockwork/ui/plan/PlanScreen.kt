package app.blockwork.ui.plan

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.EditCalendar
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.blockwork.data.SettingsRepository
import app.blockwork.data.UserSettings
import app.blockwork.data.plans.PlanRepository
import app.blockwork.data.rides.RideRepository
import app.blockwork.domain.io.WorkoutImportException
import app.blockwork.domain.io.WorkoutImporter
import app.blockwork.domain.plan.LinkedRide
import app.blockwork.domain.plan.PlanEditor
import app.blockwork.domain.plan.PlanProgress
import app.blockwork.domain.plan.SessionStatus
import app.blockwork.domain.plan.TrainingPlan
import app.blockwork.domain.plan.WeekKind
import app.blockwork.domain.training.WorkoutMetrics
import app.blockwork.ui.common.formatMinutes
import app.blockwork.ui.common.readTextLimited
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import javax.inject.Inject

@HiltViewModel
class PlanViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val plans: PlanRepository,
    rides: RideRepository,
    settings: SettingsRepository,
) : ViewModel() {
    /** Loaded = false until the first database emission, so the empty state does not flash. */
    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    val plan: StateFlow<TrainingPlan?> = plans.activePlan
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val rides: StateFlow<List<LinkedRide>> = rides.planRides
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val userSettings = settings.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UserSettings())

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    init {
        viewModelScope.launch {
            plans.activePlan.collect { _loaded.value = true }
        }
    }

    fun exportText(plan: TrainingPlan): String = plans.export(plan)

    val edits = PlanEdits(plans, viewModelScope)

    fun shift(from: LocalDate, days: Int) {
        val p = plan.value ?: return
        edits.apply(p, PlanEditor.shift(p, from, days, LocalDate.now(), rides.value), "Sessions from ${from.dayLabel()} moved ${shiftLabel(days)}")
    }

    fun delete() {
        viewModelScope.launch { plans.delete() }
    }

    fun import(uri: Uri?) {
        if (uri == null) return
        viewModelScope.launch {
            _message.value = try {
                val text = withContext(Dispatchers.IO) { readTextLimited(context, uri, WorkoutImporter.MAX_FILE_CHARS) }
                if (text.length > WorkoutImporter.MAX_FILE_CHARS) throw WorkoutImportException("File is too large")
                val plan = plans.import(text)
                "Imported \"${plan.name}\" – starts ${plan.startDate.dayLabel()}"
            } catch (e: WorkoutImportException) {
                "Import failed: ${e.message}"
            } catch (e: java.io.IOException) {
                "Import failed: ${e.message}"
            }
        }
    }

    fun dismissMessage() {
        _message.value = null
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlanScreen(
    onBack: () -> Unit,
    onNewPlan: () -> Unit,
    onOpenSession: (String) -> Unit,
    viewModel: PlanViewModel = hiltViewModel(),
) {
    val plan by viewModel.plan.collectAsStateWithLifecycle()
    val loaded by viewModel.loaded.collectAsStateWithLifecycle()
    val rides by viewModel.rides.collectAsStateWithLifecycle()
    val settings by viewModel.userSettings.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var confirmDelete by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { viewModel.import(it) }
    val today = remember { LocalDate.now() }
    val notice by viewModel.edits.notice.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var shifting by remember { mutableStateOf(false) }
    PlanNoticeSnackbar(notice, snackbar, viewModel.edits)

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Training plan") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    plan?.let { p ->
                        if (!today.isAfter(p.endDate)) {
                            IconButton(onClick = { shifting = true }) { Icon(Icons.Default.EditCalendar, "Shift plan") }
                        }
                        IconButton(onClick = {
                            val send = Intent(Intent.ACTION_SEND).setType("application/json")
                                .putExtra(Intent.EXTRA_SUBJECT, "${p.name}.json")
                                .putExtra(Intent.EXTRA_TEXT, viewModel.exportText(p))
                            context.startActivity(Intent.createChooser(send, "Share plan"))
                        }) { Icon(Icons.Default.Share, "Export plan") }
                        IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.Delete, "End plan") }
                    }
                },
            )
        },
    ) { padding ->
        val p = plan
        when {
            !loaded -> Unit
            p == null -> Column(
                Modifier.fillMaxSize().padding(padding).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("No training plan yet.", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Create a plan for your goal: it schedules your workouts week by week with recovery weeks " +
                        "and FTP tests. Targets follow your FTP, so the plan adapts when your FTP changes.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(onClick = onNewPlan, modifier = Modifier.fillMaxWidth()) { Text("Create plan") }
                OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Import plan (.json)")
                }
            }
            else -> PlanContent(p, rides, today, settings.ftpWatts, onOpenSession, onNewPlan, Modifier.padding(padding))
        }
    }

    message?.let {
        AlertDialog(
            onDismissRequest = viewModel::dismissMessage,
            text = { Text(it) },
            confirmButton = { TextButton(onClick = viewModel::dismissMessage) { Text("OK") } },
        )
    }
    val p = plan
    if (shifting && p != null) {
        ShiftPlanDialog(
            plan = p,
            rides = rides,
            today = today,
            onShift = { from, days -> shifting = false; viewModel.shift(from, days) },
            onDismiss = { shifting = false },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("End this plan?") },
            text = { Text("The plan is removed. Your recorded rides stay in History.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; viewModel.delete() }) { Text("End plan") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun PlanContent(
    plan: TrainingPlan,
    rides: List<LinkedRide>,
    today: LocalDate,
    ftp: Int,
    onOpenSession: (String) -> Unit,
    onNewPlan: () -> Unit,
    modifier: Modifier,
) {
    val load = remember(plan, ftp) {
        plan.weeks.map { w -> w.sessions.sumOf { WorkoutMetrics.estimate(it.workout, ftp).tss ?: 0 } }
    }
    val currentWeek = plan.weekIndexOf(today)
    val done = PlanProgress.doneCount(plan, today, rides)
    val finished = today.isAfter(plan.endDate)

    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(plan.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text(
                        when {
                            finished -> "Plan finished on ${plan.endDate.dayLabel()}"
                            currentWeek == null -> "Starts ${plan.startDate.dayLabel()}"
                            else -> "Week ${currentWeek + 1} of ${plan.weeks.size} · " +
                                if (plan.weeks[currentWeek].kind == WeekKind.Recovery) "recovery week" else "build week"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text("$done of ${plan.sessions.size} sessions done", style = MaterialTheme.typography.bodyMedium)
                    WeeklyLoadChart(load, plan.weeks.map { it.kind }, currentWeek, Modifier.fillMaxWidth().height(56.dp))
                    Text("Planned load (TSS) per week at FTP $ftp W", style = MaterialTheme.typography.labelSmall)
                    if (finished) Button(onClick = onNewPlan) { Text("Create next plan") }
                }
            }
        }
        for (week in plan.weeks) {
            item(key = "week-${week.index}") {
                val from = plan.startDate.plusWeeks(week.index.toLong())
                Text(
                    "Week ${week.index + 1} · ${if (week.kind == WeekKind.Recovery) "Recovery" else "Build"} · " +
                        "${from.dayLabel()} – ${from.plusDays(6).dayLabel()} · TSS ${load[week.index]}",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            items(week.sessions, key = { it.id }) { s ->
                val status = PlanProgress.status(plan, s, today, rides)
                Card(
                    Modifier.fillMaxWidth().clickable { onOpenSession(s.id) },
                    colors = if (status == SessionStatus.Today) {
                        CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                    } else {
                        CardDefaults.cardColors()
                    },
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(plan.dateOf(s).dayLabel(), style = MaterialTheme.typography.labelMedium)
                            Text(s.workout.name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                "${s.type.title} · ${formatMinutes(s.workout.durationSec)}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        StatusIcon(status)
                    }
                }
            }
        }
    }
}
