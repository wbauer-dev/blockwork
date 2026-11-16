package app.indoortrain.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Info
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
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.indoortrain.data.SettingsRepository
import app.indoortrain.data.ThemeMode
import app.indoortrain.data.UserSettings
import app.indoortrain.domain.HeartRateZone
import app.indoortrain.ui.common.color
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
) : ViewModel() {

    val settings: StateFlow<UserSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UserSettings())

    private val _ergResult = MutableStateFlow<String?>(null)
    val ergResult: StateFlow<String?> = _ergResult.asStateFlow()

    fun saveFtp(text: String): Boolean {
        val ftp = text.trim().toIntOrNull()?.takeIf { it in UserSettings.FTP_RANGE } ?: return false
        viewModelScope.launch { settingsRepository.setFtp(ftp) }
        return true
    }

    fun saveMaxHeartRate(text: String): Boolean {
        if (text.isBlank()) {
            viewModelScope.launch { settingsRepository.setMaxHeartRate(null) }
            return true
        }
        val bpm = text.trim().toIntOrNull()?.takeIf { it in HeartRateZone.MAX_HR_RANGE } ?: return false
        viewModelScope.launch { settingsRepository.setMaxHeartRate(bpm) }
        return true
    }

    fun setCues(sound: Boolean, vibration: Boolean, voice: Boolean) {
        viewModelScope.launch { settingsRepository.setCues(sound, vibration, voice) }
    }

    fun setTheme(mode: ThemeMode) {
        viewModelScope.launch { settingsRepository.setTheme(mode) }
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
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var showAbout by remember { mutableStateOf(false) }
    fun notify(message: String) {
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(message)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                actions = {
                    IconButton(onClick = { showAbout = true }) { Icon(Icons.Outlined.Info, "About IndoorTrain") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            FtpCard(settings.ftpWatts) { text ->
                viewModel.saveFtp(text).also { ok -> if (ok) notify("FTP saved: ${text.trim()} W") }
            }
            HeartRateCard(settings.maxHeartRate) { text ->
                viewModel.saveMaxHeartRate(text).also { ok ->
                    if (ok) {
                        notify(
                            if (text.isBlank()) "Maximum heart rate cleared – zones off"
                            else "Maximum heart rate saved: ${text.trim()} bpm",
                        )
                    }
                }
            }
            CueCard(settings, viewModel::setCues)
            ThemeCard(settings.theme, viewModel::setTheme)

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
    if (showAbout) AboutDialog(onDismiss = { showAbout = false })
}

@Composable
private fun FtpCard(currentFtp: Int, onSave: (String) -> Boolean) {
    var text by remember { mutableStateOf(currentFtp.toString()) }
    var invalid by remember { mutableStateOf(false) }
    LaunchedEffect(currentFtp) { text = currentFtp.toString() }
    val focus = LocalFocusManager.current
    val dirty = text.trim() != currentFtp.toString()
    fun save() {
        if (onSave(text)) focus.clearFocus() else invalid = true
    }
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
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (dirty) save() else focus.clearFocus() }),
                    modifier = Modifier.weight(1f),
                )
                Button(onClick = { save() }, enabled = dirty) { Text("Save") }
            }
        }
    }
}

@Composable
private fun HeartRateCard(maxHr: Int?, onSave: (String) -> Boolean) {
    var text by remember { mutableStateOf(maxHr?.toString().orEmpty()) }
    var invalid by remember { mutableStateOf(false) }
    LaunchedEffect(maxHr) { text = maxHr?.toString().orEmpty() }
    val focus = LocalFocusManager.current
    val dirty = text.trim() != maxHr?.toString().orEmpty()
    fun save() {
        if (onSave(text)) focus.clearFocus() else invalid = true
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Maximum heart rate", style = MaterialTheme.typography.titleMedium)
            Text(
                "Used for heart-rate zone colours. Leave empty to turn zones off. Stored only on this phone.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it; invalid = false },
                    suffix = { Text("bpm") },
                    singleLine = true,
                    isError = invalid,
                    supportingText = {
                        if (invalid) Text("${HeartRateZone.MAX_HR_RANGE.first}–${HeartRateZone.MAX_HR_RANGE.last} bpm")
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (dirty) save() else focus.clearFocus() }),
                    modifier = Modifier.weight(1f),
                )
                Button(onClick = { save() }, enabled = dirty) { Text("Save") }
            }
            if (maxHr != null) {
                val bounds = HeartRateZone.lowerBounds(maxHr)
                HeartRateZone.entries.forEach { z ->
                    val upper = HeartRateZone.entries.getOrNull(z.ordinal + 1)?.let { bounds.getValue(it) - 1 }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        androidx.compose.foundation.Canvas(Modifier.size(12.dp)) { drawCircle(z.color()) }
                        Text(
                            "Z${z.number} ${z.label}: ${bounds.getValue(z)}${upper?.let { "–$it" } ?: "+"} bpm",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CueCard(settings: UserSettings, onChange: (sound: Boolean, vibration: Boolean, voice: Boolean) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Workout cues", style = MaterialTheme.typography.titleMedium)
            SwitchRow("Beeps", "3-2-1 before each interval change", settings.cueSound) {
                onChange(it, settings.cueVibration, settings.cueVoice)
            }
            SwitchRow("Vibration", "Short buzz with each beep", settings.cueVibration) {
                onChange(settings.cueSound, it, settings.cueVoice)
            }
            SwitchRow("Voice", "\"In 10 seconds: 4 minutes at 280 watts\" (uses the phone's text-to-speech)", settings.cueVoice) {
                onChange(settings.cueSound, settings.cueVibration, it)
            }
        }
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThemeCard(mode: ThemeMode, onChange: (ThemeMode) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Appearance", style = MaterialTheme.typography.titleMedium)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                ThemeMode.entries.forEachIndexed { i, m ->
                    SegmentedButton(
                        selected = m == mode,
                        onClick = { onChange(m) },
                        shape = SegmentedButtonDefaults.itemShape(i, ThemeMode.entries.size),
                    ) { Text(m.name) }
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
