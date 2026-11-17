package app.indoortrain.ui.history

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
import androidx.compose.material.icons.filled.Share
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.indoortrain.data.SettingsRepository
import app.indoortrain.data.export.FitExporter
import app.indoortrain.data.rides.RideEntity
import app.indoortrain.data.rides.RideRepository
import app.indoortrain.domain.training.RideSample
import app.indoortrain.domain.HeartRateZone
import app.indoortrain.domain.PowerZone
import app.indoortrain.ui.common.ChartArea
import app.indoortrain.ui.common.ChartBand
import app.indoortrain.ui.common.ChartLine
import app.indoortrain.ui.common.ChartRule
import app.indoortrain.ui.common.HeartRateLine
import app.indoortrain.ui.common.RideChart
import app.indoortrain.ui.common.bpmRange
import app.indoortrain.ui.common.color
import app.indoortrain.ui.common.niceStep
import app.indoortrain.ui.common.formatDuration
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import kotlin.math.ceil

@HiltViewModel
class RideDetailViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val rides: RideRepository,
    settings: SettingsRepository,
) : ViewModel() {
    private val id: Long = checkNotNull(savedState.get<Long>("id"))

    val ride: StateFlow<RideEntity?> = rides.observe(id).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Current max heart rate setting, for the zone bands (not stored per ride). */
    val maxHeartRate: StateFlow<Int?> =
        settings.settings.map { it.maxHeartRate }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _samples = MutableStateFlow<List<RideSample>>(emptyList())
    val samples: StateFlow<List<RideSample>> = _samples.asStateFlow()

    init {
        viewModelScope.launch {
            rides.ride(id)?.let { rides.recoverIfUnfinished(it) }
            _samples.value = rides.samples(id)
        }
    }

    suspend fun fitBytes(): ByteArray? {
        val r = ride.value ?: return null
        val s = samples.value.takeIf { it.isNotEmpty() } ?: return null
        return withContext(Dispatchers.Default) { FitExporter.export(FitExporter.RideInfo(r.startedAt, r.ftp), s) }
    }

    fun delete(onDeleted: () -> Unit) {
        viewModelScope.launch {
            rides.delete(id)
            onDeleted()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RideDetailScreen(onBack: () -> Unit, viewModel: RideDetailViewModel = hiltViewModel()) {
    val ride by viewModel.ride.collectAsStateWithLifecycle()
    val samples by viewModel.samples.collectAsStateWithLifecycle()
    val maxHeartRate by viewModel.maxHeartRate.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var confirmDelete by remember { mutableStateOf(false) }
    val exportFit: () -> Unit = {
        scope.launch {
            val r = viewModel.ride.value ?: return@launch
            viewModel.fitBytes()?.let { FitShare.share(context, it, r.startedAt, r.workoutName) }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(ride?.workoutName ?: "Ride", maxLines = 1) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    IconButton(onClick = exportFit, enabled = samples.isNotEmpty()) { Icon(Icons.Default.Share, "Export FIT") }
                    IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.Delete, "Delete") }
                },
            )
        },
    ) { padding ->
        val r = ride
        if (r == null) {
            Text("Ride not found.", Modifier.padding(padding).padding(16.dp))
            return@Scaffold
        }
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                formatRideDate(r.startedAt) + if (r.finished && !r.completed) " · ended early" else "",
                style = MaterialTheme.typography.bodyMedium,
            )

            if (samples.size > 1) RideCharts(samples, r.ftp, maxHeartRate)

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Line("Duration", r.durationSec?.let(::formatDuration))
                    Line("Average power", r.avgPower?.let { "$it W" })
                    Line("Normalized power", r.normalizedPower?.let { "$it W" })
                    Line("Max power", r.maxPower?.let { "$it W" })
                    Line("Average heart rate", r.avgHeartRate?.let { "$it bpm" })
                    Line("Max heart rate", r.maxHeartRate?.let { "$it bpm" })
                    Line("Average cadence", r.avgCadence?.let { "$it rpm" })
                    Line("Energy", r.energyKj?.let { "$it kJ" })
                    Line("Intensity factor", r.intensityFactor?.let { "%.2f".format(it) })
                    Line("TSS", r.tss?.toString())
                    Line("FTP at the time", "${r.ftp} W")
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Export", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "FIT file for Strava (strava.com → Upload activity), Garmin Connect, intervals.icu and others.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Button(onClick = exportFit, enabled = samples.isNotEmpty()) { Text("Export FIT file") }
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ride?") },
            text = { Text("The ride is removed from this phone. FIT files you already exported are not affected.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; viewModel.delete(onBack) }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

/** Power over target blocks (zone colours, FTP line) and heart rate over zone bands, on one time axis. */
@Composable
private fun RideCharts(samples: List<RideSample>, ftp: Int, maxHeartRate: Int?) {
    val seconds = remember(samples) { samples.map { it.elapsedSec } }
    val power = remember(samples) { samples.map { it.powerWatts } }
    val target = remember(samples) { samples.map { it.targetWatts } }
    val heartRate = remember(samples) { samples.map { it.heartRateBpm } }

    // A little headroom above the highest value (FTP included, so its line is always visible).
    val peak = maxOf(ftp, power.maxOf { it ?: 0 }, target.maxOf { it ?: 0 })
    val wattTop = ceil(peak * 1.08 / 10).toInt() * 10
    ChartWithTitle("Power · target (W)") {
        RideChart(
            seconds = seconds,
            line = ChartLine(power, MaterialTheme.colorScheme.onSurface),
            area = ChartArea(target) { watts -> PowerZone.forFtpFraction(watts / ftp).color().copy(alpha = 0.6f) },
            rule = ChartRule(ftp, "FTP $ftp W"),
            yRange = 0..wattTop,
            yStep = niceStep(wattTop, maxTicks = 4),
            modifier = Modifier.fillMaxWidth().height(180.dp),
        )
    }

    if (heartRate.any { it != null }) {
        val (lo, hi) = bpmRange(heartRate, maxHeartRate)
        val bands = maxHeartRate?.let { max ->
            val lower = HeartRateZone.lowerBounds(max)
            HeartRateZone.entries.map { z ->
                ChartBand(lower.getValue(z).toDouble(), z.upperFraction * max, z.color().copy(alpha = 0.18f))
            }
        }.orEmpty()
        ChartWithTitle(if (maxHeartRate != null) "Heart rate · zones (bpm)" else "Heart rate (bpm)") {
            RideChart(
                seconds = seconds,
                line = ChartLine(heartRate, HeartRateLine),
                bands = bands,
                yRange = lo..hi,
                yStep = niceStep(hi - lo, maxTicks = 4),
                modifier = Modifier.fillMaxWidth().height(140.dp),
            )
        }
    }
}

@Composable
private fun ChartWithTitle(title: String, chart: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge)
        chart()
    }
}

@Composable
private fun Line(label: String, value: String?) {
    Row {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Text(value ?: "–", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
    }
}
