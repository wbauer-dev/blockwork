package app.blockwork.ui.devices

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.DirectionsBike
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.blockwork.ble.BleScanner
import app.blockwork.devices.DeviceManager
import app.blockwork.domain.devices.DeviceKind
import app.blockwork.domain.devices.DiscoveredDevice
import app.blockwork.domain.devices.PairedDevice
import app.blockwork.ui.common.BluetoothAccessBanner
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

@HiltViewModel
class DevicesViewModel @Inject constructor(
    val devices: DeviceManager,
    private val scanner: BleScanner,
) : ViewModel() {

    private val _found = MutableStateFlow<Map<String, DiscoveredDevice>>(emptyMap())
    val found: StateFlow<Map<String, DiscoveredDevice>> = _found.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var scanJob: Job? = null

    fun startScan() {
        if (!devices.permissionsGranted.value || !devices.bluetoothEnabled.value) return
        scanJob?.cancel()
        _error.value = null
        scanJob = viewModelScope.launch {
            _scanning.value = true
            try {
                withTimeoutOrNull(SCAN_DURATION_MS) {
                    scanner.scan()
                        .catch { _error.value = it.message }
                        .collect { d -> _found.update { it + (d.address to d) } }
                }
            } finally {
                _scanning.value = false
            }
        }
    }

    fun pair(device: DiscoveredDevice) {
        viewModelScope.launch { devices.pair(device) }
    }

    fun forget(kind: DeviceKind) {
        viewModelScope.launch { devices.forget(kind) }
    }

    companion object {
        private const val SCAN_DURATION_MS = 20_000L
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicesScreen(onBack: () -> Unit, viewModel: DevicesViewModel = hiltViewModel()) {
    val dm = viewModel.devices
    val found by viewModel.found.collectAsStateWithLifecycle()
    val scanning by viewModel.scanning.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val paired by dm.deviceSettings.collectAsStateWithLifecycle()
    val simulation by dm.simulation.collectAsStateWithLifecycle()
    val permissions by dm.permissionsGranted.collectAsStateWithLifecycle()
    val btOn by dm.bluetoothEnabled.collectAsStateWithLifecycle()

    LaunchedEffect(permissions, btOn) { viewModel.startScan() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Devices") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                actions = {
                    if (scanning) {
                        CircularProgressIndicator(Modifier.size(24.dp).padding(end = 4.dp), strokeWidth = 2.dp)
                    } else {
                        TextButton(onClick = viewModel::startScan) { Text("Scan") }
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item {
                BluetoothAccessBanner(
                    permissions, btOn, simulation = false, onPermissionsResult = dm::onPermissionsChanged,
                    modifier = Modifier.padding(16.dp),
                )
                if (simulation) {
                    Text(
                        "Simulation mode is on – paired devices are not used until you switch it off in Settings.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(16.dp),
                    )
                }
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
                }
            }
            for (kind in DeviceKind.entries) {
                val current = if (kind == DeviceKind.HeartRateMonitor) paired.heartRateMonitor else paired.trainer
                val candidates = found.values.filter { it.kind == kind }.sortedByDescending { it.rssi }
                item(key = "header-$kind") {
                    SectionHeader(kind, current, onForget = { viewModel.forget(kind) })
                }
                if (candidates.isEmpty()) {
                    item(key = "empty-$kind") {
                        Text(
                            if (scanning) "Searching…" else "None found. Wake the device up and tap Scan.",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                }
                items(candidates, key = { "${kind}-${it.address}" }) { d ->
                    val isPaired = current?.address == d.address
                    ListItem(
                        headlineContent = { Text(d.name ?: "Unknown device") },
                        supportingContent = { Text("Signal ${d.rssi} dBm" + if (isPaired) " · paired" else "") },
                        leadingContent = { Icon(kind.icon(), contentDescription = null) },
                        trailingContent = { if (isPaired) Icon(Icons.Default.Check, "Paired") },
                        modifier = Modifier.clickable(enabled = !isPaired) { viewModel.pair(d) },
                    )
                }
                item(key = "divider-$kind") { HorizontalDivider() }
            }
        }
    }
}

@Composable
private fun SectionHeader(kind: DeviceKind, current: PairedDevice?, onForget: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            (if (kind == DeviceKind.HeartRateMonitor) "Heart rate monitor" else "Trainer") +
                (current?.let { ": ${it.name ?: it.address}" } ?: ": not paired"),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.weight(1f),
        )
        if (current != null) TextButton(onClick = onForget) { Text("Forget") }
    }
}

private fun DeviceKind.icon() = when (this) {
    DeviceKind.HeartRateMonitor -> Icons.Default.Favorite
    DeviceKind.Trainer -> Icons.AutoMirrored.Filled.DirectionsBike
}
