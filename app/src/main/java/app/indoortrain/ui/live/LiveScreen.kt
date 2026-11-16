package app.indoortrain.ui.live

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
import androidx.compose.material.icons.automirrored.filled.DirectionsBike
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.indoortrain.data.SettingsRepository
import app.indoortrain.data.plans.PlanRepository
import app.indoortrain.domain.HeartRateZone
import app.indoortrain.ui.common.color
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import androidx.compose.material3.CardDefaults
import androidx.compose.ui.graphics.Color
import app.indoortrain.data.rides.RideRepository
import app.indoortrain.devices.DeviceManager
import app.indoortrain.ui.plan.PlanTodayCard
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import app.indoortrain.domain.training.SessionPhase
import app.indoortrain.training.TrainingController
import app.indoortrain.ui.common.BluetoothAccessBanner
import app.indoortrain.ui.common.DeviceStatusCard
import app.indoortrain.ui.common.KeepScreenOn
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.math.roundToInt

@HiltViewModel
class LiveViewModel @Inject constructor(
    val devices: DeviceManager,
    val training: TrainingController,
    plans: PlanRepository,
    rides: RideRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {
    val plan = plans.activePlan.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val planRides = rides.planRides.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val settings = settingsRepository.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val hasRides = rides.rides.map { it.isNotEmpty() }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    fun hideOnboarding() {
        viewModelScope.launch { settingsRepository.dismissOnboarding() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveScreen(
    onOpenDevices: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenWorkouts: () -> Unit,
    onOpenTraining: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenPlan: () -> Unit,
    onOpenPlanSession: (String) -> Unit,
    viewModel: LiveViewModel = hiltViewModel(),
) {
    val plan by viewModel.plan.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val hasRides by viewModel.hasRides.collectAsStateWithLifecycle()
    val planRides by viewModel.planRides.collectAsStateWithLifecycle()
    val session by viewModel.training.state.collectAsStateWithLifecycle()
    val dm = viewModel.devices
    val hrStatus by dm.heartRateStatus.collectAsStateWithLifecycle()
    val trainerStatus by dm.trainerStatus.collectAsStateWithLifecycle()
    val heartRate by dm.heartRate.collectAsStateWithLifecycle()
    val bike by dm.bikeData.collectAsStateWithLifecycle()
    val paired by dm.deviceSettings.collectAsStateWithLifecycle()
    val simulation by dm.simulation.collectAsStateWithLifecycle()
    val permissions by dm.permissionsGranted.collectAsStateWithLifecycle()
    val btOn by dm.bluetoothEnabled.collectAsStateWithLifecycle()

    KeepScreenOn()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("IndoorTrain") },
                actions = {
                    IconButton(onClick = onOpenHistory) { Icon(Icons.Default.History, "History") }
                    IconButton(onClick = onOpenDevices) { Icon(Icons.Default.Bluetooth, "Devices") }
                    IconButton(onClick = onOpenSettings) { Icon(Icons.Default.Settings, "Settings") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            BluetoothAccessBanner(permissions, btOn, simulation, dm::onPermissionsChanged)
            settings?.takeIf { !it.onboardingDismissed }?.let { st ->
                GettingStartedCard(
                    steps = listOf(
                        OnboardingStep("Set your FTP", "Settings → FTP, or ride the FTP ramp test", st.ftpConfigured, onOpenSettings),
                        OnboardingStep(
                            "Pair heart rate strap and trainer", "Bluetooth icon above",
                            paired.heartRateMonitor != null && paired.trainer != null, onOpenDevices,
                        ),
                        OnboardingStep("Ride a workout or create a plan", "Workouts or Plan below", hasRides || plan != null, onOpenWorkouts),
                    ),
                    onHide = viewModel::hideOnboarding,
                )
            }
            val running = session
            if (running != null) {
                Button(onClick = onOpenTraining, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        if (running.phase == SessionPhase.Finished) "Workout finished – show summary"
                        else "Workout running: ${running.workoutName} – open",
                    )
                }
            } else {
                PlanTodayCard(plan, planRides, onOpenPlan, onOpenPlanSession)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = onOpenWorkouts, modifier = Modifier.weight(1f).height(56.dp)) {
                        Icon(Icons.Default.FitnessCenter, contentDescription = null)
                        Text("  Workouts", style = MaterialTheme.typography.titleMedium)
                    }
                    FilledTonalButton(onClick = onOpenPlan, modifier = Modifier.weight(1f).height(56.dp)) {
                        Icon(Icons.Default.CalendarMonth, contentDescription = null)
                        Text("  Plan", style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
            if (simulation) {
                AssistChip(onClick = onOpenSettings, label = { Text("Simulation mode – synthetic data") })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                DeviceStatusCard(
                    Icons.Default.Favorite, "Heart rate", paired.heartRateMonitor?.name, hrStatus,
                    Modifier.weight(1f),
                )
                DeviceStatusCard(
                    Icons.AutoMirrored.Filled.DirectionsBike, "Trainer", paired.trainer?.name, trainerStatus,
                    Modifier.weight(1f),
                )
            }
            if (!simulation && paired.heartRateMonitor == null && paired.trainer == null) {
                Text(
                    "No devices paired yet. Tap the Bluetooth icon to find your heart rate strap and trainer.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            MetricTile("Power", bike?.powerWatts?.toString(), "W", large = true)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val zone = heartRate?.bpm?.let { bpm -> settings?.maxHeartRate?.let { HeartRateZone.forBpm(bpm, it) } }
                MetricTile(
                    if (zone != null) "Heart rate · Z${zone.number}" else "Heart rate",
                    heartRate?.bpm?.toString(), "bpm", Modifier.weight(1f), tint = zone?.color(),
                )
                MetricTile("Cadence", bike?.cadenceRpm?.roundToInt()?.toString(), "rpm", Modifier.weight(1f))
            }
            MetricTile("Speed", bike?.speedKmh?.let { "%.1f".format(it) }, "km/h")
        }
    }
}

@Composable
private fun MetricTile(
    label: String,
    value: String?,
    unit: String,
    modifier: Modifier = Modifier,
    large: Boolean = false,
    tint: Color? = null,
) {
    Card(
        modifier.fillMaxWidth(),
        colors = if (tint != null) CardDefaults.cardColors(containerColor = tint.copy(alpha = 0.3f)) else CardDefaults.cardColors(),
    ) {
        Column(Modifier.padding(16.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    value ?: "--",
                    style = if (large) MaterialTheme.typography.displayLarge else MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(unit, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 6.dp))
            }
        }
    }
}
