package app.blockwork.ui.workouts

import android.content.Intent
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.blockwork.data.SettingsRepository
import app.blockwork.data.UserSettings
import app.blockwork.data.workouts.LibraryWorkout
import app.blockwork.data.workouts.WorkoutRepository
import app.blockwork.devices.DeviceManager
import app.blockwork.domain.Segment
import app.blockwork.domain.devices.ConnectionState
import app.blockwork.domain.io.WorkoutJson
import app.blockwork.domain.training.WorkoutMetrics
import app.blockwork.training.TrainingController
import app.blockwork.ui.common.WorkoutProfile
import app.blockwork.ui.common.rememberWorkoutStarter
import app.blockwork.ui.common.formatDuration
import app.blockwork.ui.common.formatMinutes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.roundToInt

@HiltViewModel
class WorkoutDetailViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val repository: WorkoutRepository,
    settings: SettingsRepository,
    private val devices: DeviceManager,
    private val training: TrainingController,
) : ViewModel() {
    private val id: String = checkNotNull(savedState["id"])

    /** null while loading or after deletion. */
    val workout: StateFlow<LibraryWorkout?> = repository.workouts
        .map { list -> list.firstOrNull { it.workout.id == id } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val userSettings = settings.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UserSettings())

    val trainerConnected: Boolean
        get() = devices.trainerStatus.value.connection == ConnectionState.Connected

    fun start(onStarted: () -> Unit) {
        val w = workout.value?.workout ?: return
        viewModelScope.launch {
            training.start(w)
            onStarted()
        }
    }

    fun delete(onDeleted: () -> Unit) {
        viewModelScope.launch {
            repository.delete(id)
            onDeleted()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkoutDetailScreen(
    onBack: () -> Unit,
    onStarted: () -> Unit,
    onEdit: (id: String, copy: Boolean) -> Unit,
    viewModel: WorkoutDetailViewModel = hiltViewModel(),
) {
    val item by viewModel.workout.collectAsStateWithLifecycle()
    val settings by viewModel.userSettings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var confirmDelete by remember { mutableStateOf(false) }
    val startWorkout = rememberWorkoutStarter({ viewModel.trainerConnected }) { viewModel.start(onStarted) }

    val lib = item
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(lib?.workout?.name ?: "Workout", maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                actions = {
                    if (lib != null) {
                        IconButton(onClick = {
                            val send = Intent(Intent.ACTION_SEND)
                                .setType("application/json")
                                .putExtra(Intent.EXTRA_SUBJECT, "${lib.workout.name}.json")
                                .putExtra(Intent.EXTRA_TEXT, WorkoutJson.encode(lib.workout))
                            context.startActivity(Intent.createChooser(send, "Share workout"))
                        }) { Icon(Icons.Default.Share, "Share as JSON") }
                        if (lib.editable) {
                            IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.Delete, "Delete") }
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (lib == null) {
            Text("Workout not found.", Modifier.padding(padding).padding(16.dp))
            return@Scaffold
        }
        val w = lib.workout
        val ftp = settings.ftpWatts
        val estimate = remember(w, ftp) { WorkoutMetrics.estimate(w, ftp) }
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (w.description.isNotBlank()) Text(w.description, style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Stat("Duration", formatMinutes(w.durationSec))
                Stat("IF", estimate.intensityFactor?.let { "%.2f".format(it) } ?: "–")
                Stat("TSS", estimate.tss?.toString() ?: "–")
                Stat("FTP", "$ftp W")
            }
            WorkoutProfile(w.flatten(), Modifier.fillMaxWidth().height(120.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = startWorkout,
                    modifier = Modifier.weight(1f).height(52.dp),
                ) {
                    Icon(Icons.Default.PlayArrow, null)
                    Text(" Start")
                }
                OutlinedButton(onClick = { onEdit(w.id, !lib.editable) }, modifier = Modifier.height(52.dp)) {
                    Icon(Icons.Default.Edit, null)
                    Text(if (lib.editable) " Edit" else " Copy & edit")
                }
            }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    w.segments.forEach { SegmentLine(it, ftp, indent = 0) }
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete workout?") },
            text = { Text("\"${lib?.workout?.name}\" is removed from your library. Recorded rides are kept.") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; viewModel.delete(onBack) }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SegmentLine(segment: Segment, ftp: Int, indent: Int) {
    val pad = Modifier.padding(start = (indent * 16).dp)
    fun w(f: Double) = "${(f * ftp).roundToInt()} W"
    fun p(f: Double) = "${(f * 100).roundToInt()} %"
    when (segment) {
        is Segment.Steady -> Text(
            "${formatDuration(segment.durationSec)}  ${w(segment.targetFtp)} (${p(segment.targetFtp)})" +
                (segment.label?.let { "  · $it" } ?: ""),
            pad, style = MaterialTheme.typography.bodyMedium,
        )
        is Segment.Ramp -> Text(
            "${formatDuration(segment.durationSec)}  ${w(segment.fromFtp)} → ${w(segment.toFtp)}" +
                (segment.label?.let { "  · $it" } ?: ""),
            pad, style = MaterialTheme.typography.bodyMedium,
        )
        is Segment.FreeRide -> Text(
            "${formatDuration(segment.durationSec)}  free ride (no ERG)", pad, style = MaterialTheme.typography.bodyMedium,
        )
        is Segment.Repeat -> {
            Text("${segment.times} ×", pad, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            segment.segments.forEach { SegmentLine(it, ftp, indent + 1) }
        }
    }
}
