package app.blockwork.ui.history

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import android.content.Context
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import app.blockwork.training.TrainingController
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.launch
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.blockwork.data.rides.RideEntity
import app.blockwork.data.rides.RideRepository
import app.blockwork.ui.common.formatDuration
import app.blockwork.ui.common.plural
import dagger.hilt.android.lifecycle.HiltViewModel
import app.blockwork.domain.training.RideLoad
import app.blockwork.domain.training.TrainingLoad
import app.blockwork.domain.training.TrainingOverview
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.text.DateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import javax.inject.Inject

@HiltViewModel
class HistoryViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: RideRepository,
    private val training: TrainingController,
) : ViewModel() {
    val rides = repository.rides.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Overview statistics over all finished rides; null while loading. */
    val overview: StateFlow<Pair<TrainingOverview, LocalDate>?> = repository.rides
        .map { rides ->
            val today = LocalDate.now()
            TrainingLoad.overview(rides.mapNotNull { it.toLoad() }, today) to today
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Deleting while a workout records would remove the ride it is writing to. */
    val workoutRunning: Boolean get() = training.isActive

    fun deleteAll() {
        if (training.isActive) return
        viewModelScope.launch {
            repository.deleteAll()
            FitShare.clearCache(context)
        }
    }
}

/** Finished rides with a duration count; interrupted ones are left out until recovered. */
private fun RideEntity.toLoad(): RideLoad? {
    val duration = durationSec?.takeIf { finished && it > 0 } ?: return null
    val date = Instant.ofEpochMilli(startedAt).atZone(ZoneId.systemDefault()).toLocalDate()
    return RideLoad(date, duration, tss, energyKj)
}

fun formatRideDate(millis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(millis))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(onBack: () -> Unit, onOpen: (Long) -> Unit, viewModel: HistoryViewModel = hiltViewModel()) {
    val rides by viewModel.rides.collectAsStateWithLifecycle()
    val overview by viewModel.overview.collectAsStateWithLifecycle()
    var confirmDeleteAll by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("History") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    IconButton(onClick = { confirmDeleteAll = true }, enabled = rides.isNotEmpty()) {
                        Icon(Icons.Default.DeleteSweep, "Delete all rides")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (rides.isEmpty()) item { Text("No rides yet.") }
            val o = overview
            if (rides.isNotEmpty() && o != null) {
                item(key = "overview") { TrainingOverviewSection(o.first, o.second) }
                item(key = "rides-title") { Text("Rides", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 6.dp)) }
            }
            items(rides, key = { it.id }) { RideCard(it, onOpen) }
        }
    }

    if (confirmDeleteAll) {
        val running = viewModel.workoutRunning
        AlertDialog(
            onDismissRequest = { confirmDeleteAll = false },
            title = { Text(if (running) "Workout in progress" else "Delete all ${plural(rides.size, "ride")}?") },
            text = {
                Text(
                    if (running) {
                        "Finish or end the current workout first – its ride is still being recorded."
                    } else {
                        "All rides and their recorded data (power, cadence, heart rate) are removed from this phone. " +
                            "Training plan sessions will show as not done. FIT files you already exported elsewhere " +
                            "are not affected. This cannot be undone."
                    },
                )
            },
            confirmButton = {
                if (running) {
                    TextButton(onClick = { confirmDeleteAll = false }) { Text("OK") }
                } else {
                    TextButton(onClick = { confirmDeleteAll = false; viewModel.deleteAll() }) {
                        Text("Delete all", color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            dismissButton = {
                if (!running) TextButton(onClick = { confirmDeleteAll = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun RideCard(ride: RideEntity, onOpen: (Long) -> Unit) {
    Card(Modifier.fillMaxWidth().clickable { onOpen(ride.id) }) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(ride.workoutName, style = MaterialTheme.typography.titleMedium, maxLines = 1)
            Text(formatRideDate(ride.startedAt), style = MaterialTheme.typography.bodySmall)
            Text(
                listOfNotNull(
                    ride.durationSec?.let(::formatDuration),
                    ride.avgPower?.let { "$it W avg" },
                    ride.normalizedPower?.let { "NP $it" },
                    ride.tss?.let { "TSS $it" },
                    ride.avgHeartRate?.let { "♥ $it" },
                ).joinToString(" · ").ifEmpty { if (ride.finished) "No data" else "Interrupted – open to recover" },
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
