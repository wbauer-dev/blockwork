package app.indoortrain.ui.plan

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.indoortrain.data.SettingsRepository
import app.indoortrain.data.UserSettings
import app.indoortrain.data.plans.PlanRepository
import app.indoortrain.data.rides.RideRepository
import app.indoortrain.data.workouts.WorkoutRepository
import app.indoortrain.devices.DeviceManager
import app.indoortrain.domain.devices.ConnectionState
import app.indoortrain.domain.plan.PlanProgress
import app.indoortrain.domain.plan.SessionStatus
import app.indoortrain.domain.training.WorkoutMetrics
import app.indoortrain.training.TrainingController
import app.indoortrain.ui.common.WorkoutProfile
import app.indoortrain.ui.common.formatMinutes
import app.indoortrain.ui.common.rememberWorkoutStarter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

@HiltViewModel
class PlanSessionViewModel @Inject constructor(
    savedState: SavedStateHandle,
    plans: PlanRepository,
    rides: RideRepository,
    settings: SettingsRepository,
    private val workouts: WorkoutRepository,
    private val devices: DeviceManager,
    private val training: TrainingController,
) : ViewModel() {
    val sessionId: String = checkNotNull(savedState["id"])
    val plan = plans.activePlan.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val rides = rides.planRides.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val userSettings = settings.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UserSettings())
    private val _saved = MutableStateFlow(false)
    val saved = _saved.asStateFlow()

    val trainerConnected: Boolean
        get() = devices.trainerStatus.value.connection == ConnectionState.Connected

    fun start(onStarted: () -> Unit) {
        val session = plan.value?.session(sessionId) ?: return
        viewModelScope.launch {
            training.start(session.workout, plannedSessionId = session.id)
            onStarted()
        }
    }

    fun saveToLibrary() {
        val session = plan.value?.session(sessionId) ?: return
        viewModelScope.launch {
            workouts.save(session.workout.copy(id = workouts.newId()))
            _saved.value = true
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlanSessionScreen(onBack: () -> Unit, onStarted: () -> Unit, viewModel: PlanSessionViewModel = hiltViewModel()) {
    val plan by viewModel.plan.collectAsStateWithLifecycle()
    val rides by viewModel.rides.collectAsStateWithLifecycle()
    val settings by viewModel.userSettings.collectAsStateWithLifecycle()
    val saved by viewModel.saved.collectAsStateWithLifecycle()
    val startWorkout = rememberWorkoutStarter({ viewModel.trainerConnected }) { viewModel.start(onStarted) }
    val session = plan?.session(viewModel.sessionId)
    val today = remember { LocalDate.now() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(session?.workout?.name ?: "Session", maxLines = 1) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        val p = plan
        if (p == null || session == null) {
            Text("Session not found.", Modifier.padding(padding).padding(16.dp))
            return@Scaffold
        }
        val w = session.workout
        val status = PlanProgress.status(p, session, today, rides)
        val estimate = remember(w, settings.ftpWatts) { WorkoutMetrics.estimate(w, settings.ftpWatts) }
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusIcon(status)
                Text(
                    "${p.dateOf(session).dayLabel()} · ${session.type.title} · " + when (status) {
                        SessionStatus.Done -> "done"
                        SessionStatus.Partial -> "partly done"
                        SessionStatus.Missed -> "missed"
                        SessionStatus.Today -> "today"
                        SessionStatus.Upcoming -> "upcoming"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Text(w.description, style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Stat("Duration", formatMinutes(w.durationSec))
                Stat("IF", estimate.intensityFactor?.let { "%.2f".format(it) } ?: "–")
                Stat("TSS", estimate.tss?.toString() ?: "–")
            }
            WorkoutProfile(w.flatten(), Modifier.fillMaxWidth().height(120.dp))
            Button(onClick = startWorkout, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Icon(Icons.Default.PlayArrow, null)
                Text(if (status == SessionStatus.Done) " Ride again" else " Start")
            }
            OutlinedButton(onClick = viewModel::saveToLibrary, enabled = !saved, modifier = Modifier.fillMaxWidth()) {
                Text(if (saved) "Saved to My workouts" else "Save to My workouts")
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    }
}
