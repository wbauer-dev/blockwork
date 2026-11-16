package app.indoortrain.ui.history

import android.content.Intent
import androidx.core.net.toUri
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
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.indoortrain.data.export.FitExporter
import app.indoortrain.data.rides.RideEntity
import app.indoortrain.data.rides.RideRepository
import app.indoortrain.data.strava.StravaRepository
import app.indoortrain.data.strava.StravaState
import app.indoortrain.domain.training.RideSample
import app.indoortrain.ui.common.ChartSeries
import app.indoortrain.ui.common.LineChart
import app.indoortrain.ui.common.formatDuration
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class RideDetailViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val rides: RideRepository,
    private val strava: StravaRepository,
) : ViewModel() {
    private val id: Long = checkNotNull(savedState.get<Long>("id"))

    val ride: StateFlow<RideEntity?> = rides.observe(id).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val stravaState = strava.state.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StravaState.Disconnected)
    val uploading: StateFlow<Boolean> = combine(strava.uploading, MutableStateFlow(id)) { set, i -> i in set }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val _samples = MutableStateFlow<List<RideSample>>(emptyList())
    val samples: StateFlow<List<RideSample>> = _samples.asStateFlow()

    init {
        viewModelScope.launch {
            rides.ride(id)?.let { rides.recoverIfUnfinished(it) }
            _samples.value = rides.samples(id)
        }
    }

    fun upload() {
        viewModelScope.launch { strava.upload(id) }
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

private val PowerColor = Color(0xFF2196F3)
private val TargetColor = Color(0xFF9E9E9E)
private val HeartColor = Color(0xFFE53935)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RideDetailScreen(onBack: () -> Unit, viewModel: RideDetailViewModel = hiltViewModel()) {
    val ride by viewModel.ride.collectAsStateWithLifecycle()
    val samples by viewModel.samples.collectAsStateWithLifecycle()
    val strava by viewModel.stravaState.collectAsStateWithLifecycle()
    val uploading by viewModel.uploading.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var confirmDelete by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(ride?.workoutName ?: "Ride", maxLines = 1) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    IconButton(onClick = {
                        scope.launch {
                            val r = ride ?: return@launch
                            viewModel.fitBytes()?.let { FitShare.share(context, it, r.startedAt, r.workoutName) }
                        }
                    }, enabled = samples.isNotEmpty()) { Icon(Icons.Default.Share, "Export FIT") }
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

            Text("Power", style = MaterialTheme.typography.labelLarge)
            LineChart(
                listOf(
                    ChartSeries(samples.map { it.targetWatts }, TargetColor, dashed = true),
                    ChartSeries(samples.map { it.powerWatts }, PowerColor),
                ),
                Modifier.fillMaxWidth().height(140.dp),
            )
            if (samples.any { it.heartRateBpm != null }) {
                Text("Heart rate", style = MaterialTheme.typography.labelLarge)
                LineChart(
                    listOf(ChartSeries(samples.map { it.heartRateBpm }, HeartColor)),
                    Modifier.fillMaxWidth().height(100.dp),
                    minY = 50,
                )
            }

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

            StravaCard(r, strava, uploading, onUpload = viewModel::upload, onOpen = { activityId ->
                context.startActivity(Intent(Intent.ACTION_VIEW, "https://www.strava.com/activities/$activityId".toUri()))
            })
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ride?") },
            text = { Text("The ride is removed from this phone. Activities already on Strava are not affected.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; viewModel.delete(onBack) }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun StravaCard(r: RideEntity, strava: StravaState, uploading: Boolean, onUpload: () -> Unit, onOpen: (Long) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Strava", style = MaterialTheme.typography.titleMedium)
            val activityId = r.stravaActivityId
            when {
                activityId != null -> OutlinedButton(onClick = { onOpen(activityId) }) { Text("View on Strava") }
                uploading -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(Modifier.height(20.dp), strokeWidth = 2.dp)
                    Text("Uploading…")
                }
                strava is StravaState.Connected -> Button(onClick = onUpload) { Text("Upload to Strava") }
                strava is StravaState.NotConfigured -> Text("Strava is not set up (see Settings).", style = MaterialTheme.typography.bodySmall)
                else -> Text("Connect Strava in Settings to upload.", style = MaterialTheme.typography.bodySmall)
            }
            if (activityId == null && !uploading) {
                r.stravaError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

@Composable
private fun Line(label: String, value: String?) {
    Row {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Text(value ?: "–", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
    }
}
