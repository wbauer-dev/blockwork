package app.indoortrain.ui.settings

import android.content.Intent
import androidx.core.net.toUri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.indoortrain.data.SettingsRepository
import app.indoortrain.data.UserSettings
import app.indoortrain.data.strava.StravaRepository
import app.indoortrain.data.strava.StravaState
import app.indoortrain.devices.DeviceManager
import app.indoortrain.domain.devices.ConnectionState
import app.indoortrain.domain.devices.DeviceStatus
import app.indoortrain.domain.devices.TrainerCapabilities
import app.indoortrain.ui.common.label
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.roundToInt

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    val devices: DeviceManager,
    private val strava: StravaRepository,
) : ViewModel() {

    val stravaState = strava.state.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StravaState.Disconnected)

    suspend fun stravaAuthorizationUrl(): String = strava.beginAuthorization()

    fun disconnectStrava() {
        viewModelScope.launch { strava.disconnect() }
    }

    fun setStravaAutoUpload(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setStravaAutoUpload(enabled) }
    }

    val settings: StateFlow<UserSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UserSettings())

    private val _ergResult = MutableStateFlow<String?>(null)
    val ergResult: StateFlow<String?> = _ergResult.asStateFlow()

    fun saveFtp(text: String): Boolean {
        val ftp = text.trim().toIntOrNull()?.takeIf { it in UserSettings.FTP_RANGE } ?: return false
        viewModelScope.launch { settingsRepository.setFtp(ftp) }
        return true
    }

    fun setSimulation(enabled: Boolean) {
        viewModelScope.launch { devices.setSimulation(enabled) }
    }

    fun setErgTarget(watts: Int) {
        viewModelScope.launch {
            _ergResult.value = "Setting $watts W…"
            _ergResult.value = "Target $watts W: ${devices.setTargetPower(watts)}"
        }
    }

    fun releaseErg() {
        viewModelScope.launch { _ergResult.value = "Release: ${devices.releaseTrainerControl()}" }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val simulation by viewModel.devices.simulation.collectAsStateWithLifecycle()
    val hrStatus by viewModel.devices.heartRateStatus.collectAsStateWithLifecycle()
    val trainerStatus by viewModel.devices.trainerStatus.collectAsStateWithLifecycle()
    val capabilities by viewModel.devices.trainerCapabilities.collectAsStateWithLifecycle()
    val ergResult by viewModel.ergResult.collectAsStateWithLifecycle()
    val stravaState by viewModel.stravaState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            FtpCard(settings.ftpWatts, viewModel::saveFtp)

            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Simulation mode", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Use simulated heart rate monitor and trainer (synthetic data) instead of Bluetooth devices.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Switch(checked = simulation, onCheckedChange = viewModel::setSimulation)
                }
            }

            StravaCard(
                state = stravaState,
                autoUpload = settings.stravaAutoUpload,
                onConnect = {
                    scope.launch {
                        val url = viewModel.stravaAuthorizationUrl()
                        context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
                    }
                },
                onDisconnect = viewModel::disconnectStrava,
                onAutoUpload = viewModel::setStravaAutoUpload,
            )

            ErgTestCard(
                connected = trainerStatus.connection == ConnectionState.Connected,
                result = ergResult,
                onSet = viewModel::setErgTarget,
                onRelease = viewModel::releaseErg,
            )

            DiagnosticsCard("Trainer", trainerStatus, capabilities)
            DiagnosticsCard("Heart rate monitor", hrStatus, null)
        }
    }
}

@Composable
private fun FtpCard(currentFtp: Int, onSave: (String) -> Boolean) {
    var text by remember { mutableStateOf(currentFtp.toString()) }
    var invalid by remember { mutableStateOf(false) }
    LaunchedEffect(currentFtp) { text = currentFtp.toString() }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("FTP", style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it; invalid = false },
                    suffix = { Text("W") },
                    singleLine = true,
                    isError = invalid,
                    supportingText = {
                        if (invalid) Text("${UserSettings.FTP_RANGE.first}–${UserSettings.FTP_RANGE.last} W")
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
                Button(onClick = { invalid = !onSave(text) }) { Text("Save") }
            }
        }
    }
}

@Composable
private fun StravaCard(
    state: StravaState,
    autoUpload: Boolean,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onAutoUpload: (Boolean) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Strava", style = MaterialTheme.typography.titleMedium)
            when (state) {
                StravaState.NotConfigured -> Text(
                    "Not set up: add your Strava API client id and secret to secrets.properties and rebuild " +
                        "(see docs/strava.md).",
                    style = MaterialTheme.typography.bodySmall,
                )
                StravaState.Disconnected -> {
                    Text("Upload rides to your Strava account.", style = MaterialTheme.typography.bodySmall)
                    Button(onClick = onConnect) { Text("Connect with Strava") }
                }
                is StravaState.Connected -> {
                    Text("Connected" + (state.athleteName?.let { " as $it" } ?: ""), style = MaterialTheme.typography.bodyMedium)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Upload automatically", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "After each workout (longer than 2 min). Includes power, cadence and heart rate.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Switch(checked = autoUpload, onCheckedChange = onAutoUpload)
                    }
                    OutlinedButton(onClick = onDisconnect) { Text("Disconnect") }
                }
            }
        }
    }
}

@Composable
private fun ErgTestCard(connected: Boolean, result: String?, onSet: (Int) -> Unit, onRelease: () -> Unit) {
    var watts by remember { mutableFloatStateOf(150f) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("ERG test", style = MaterialTheme.typography.titleMedium)
            Text(
                "Sets a fixed target power on the trainer. Pedal while testing.",
                style = MaterialTheme.typography.bodySmall,
            )
            Text("${watts.roundToInt()} W", style = MaterialTheme.typography.headlineSmall)
            Slider(value = watts, onValueChange = { watts = it }, valueRange = 50f..500f, steps = 44)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { onSet(watts.roundToInt()) }, enabled = connected) { Text("Set target") }
                OutlinedButton(onClick = onRelease, enabled = connected) { Text("Release") }
            }
            if (!connected) Text("Trainer not connected.", style = MaterialTheme.typography.bodySmall)
            result?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

@Composable
private fun DiagnosticsCard(title: String, status: DeviceStatus, capabilities: TrainerCapabilities?) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            InfoLine("Status", status.connection.label())
            InfoLine("Name", status.name)
            InfoLine("Address", status.address)
            InfoLine("Model", status.model)
            InfoLine("Firmware", status.firmware)
            InfoLine("Battery", status.batteryPercent?.let { "$it %" })
            InfoLine("Last error", status.error)
            if (capabilities != null) {
                InfoLine("Power target (ERG)", capabilities.powerTargetSupported?.yesNo())
                InfoLine("Cadence", capabilities.cadenceSupported?.yesNo())
                InfoLine("Power measurement", capabilities.powerMeasurementSupported?.yesNo())
                InfoLine(
                    "Power range",
                    capabilities.powerRange?.let { "${it.minWatts}–${it.maxWatts} W (step ${it.incrementWatts})" },
                )
                InfoLine("Feature raw", capabilities.featureRawHex)
            }
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String?) {
    Row {
        Text("$label: ", style = MaterialTheme.typography.bodySmall)
        Text(value ?: "–", style = MaterialTheme.typography.bodySmall)
    }
}

private fun Boolean.yesNo() = if (this) "yes" else "no"
