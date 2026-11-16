package app.indoortrain.ui.workouts

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.indoortrain.data.SettingsRepository
import app.indoortrain.data.UserSettings
import app.indoortrain.devices.DeviceManager
import app.indoortrain.domain.Workout
import app.indoortrain.domain.devices.ConnectionState
import app.indoortrain.domain.training.BuiltInWorkouts
import app.indoortrain.training.TrainingController
import app.indoortrain.ui.common.WorkoutProfile
import app.indoortrain.ui.common.formatMinutes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class WorkoutListViewModel @Inject constructor(
    settings: SettingsRepository,
    private val devices: DeviceManager,
    private val training: TrainingController,
) : ViewModel() {
    val workouts: List<Workout> = BuiltInWorkouts.all
    val settings = settings.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UserSettings())

    val trainerConnected: Boolean
        get() = devices.trainerStatus.value.connection == ConnectionState.Connected

    fun start(workout: Workout, onStarted: () -> Unit) {
        viewModelScope.launch {
            training.start(workout)
            onStarted()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkoutListScreen(
    onBack: () -> Unit,
    onStarted: () -> Unit,
    viewModel: WorkoutListViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var confirmWithoutTrainer by remember { mutableStateOf<Workout?>(null) }
    var pending by remember { mutableStateOf<Workout?>(null) }

    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        // Workout runs either way; without permission the notification is just hidden.
        pending?.let { viewModel.start(it, onStarted) }
        pending = null
    }

    fun launch(workout: Workout) {
        val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) {
            viewModel.start(workout, onStarted)
        } else {
            pending = workout
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Workouts") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    "Targets are based on FTP ${settings.ftpWatts} W (change in Settings).",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            items(viewModel.workouts, key = { it.id }) { workout ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(workout.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            Text(formatMinutes(workout.durationSec), style = MaterialTheme.typography.labelLarge)
                        }
                        if (workout.description.isNotBlank()) {
                            Text(workout.description, style = MaterialTheme.typography.bodySmall)
                        }
                        WorkoutProfile(workout.flatten(), Modifier.fillMaxWidth().height(56.dp))
                        Button(
                            onClick = {
                                if (viewModel.trainerConnected) launch(workout) else confirmWithoutTrainer = workout
                            },
                            modifier = Modifier.align(Alignment.End),
                        ) { Text("Start") }
                    }
                }
            }
        }
    }

    confirmWithoutTrainer?.let { workout ->
        AlertDialog(
            onDismissRequest = { confirmWithoutTrainer = null },
            title = { Text("Trainer not connected") },
            text = { Text("The workout starts anyway and controls the trainer as soon as it connects.") },
            confirmButton = {
                TextButton(onClick = { confirmWithoutTrainer = null; launch(workout) }) { Text("Start anyway") }
            },
            dismissButton = { TextButton(onClick = { confirmWithoutTrainer = null }) { Text("Cancel") } },
        )
    }
}
