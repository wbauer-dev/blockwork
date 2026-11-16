package app.indoortrain.ui.history

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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import app.indoortrain.data.rides.RideEntity
import app.indoortrain.data.rides.RideRepository
import app.indoortrain.ui.common.formatDuration
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import java.text.DateFormat
import java.util.Date
import javax.inject.Inject

@HiltViewModel
class HistoryViewModel @Inject constructor(rides: RideRepository) : ViewModel() {
    val rides = rides.rides.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}

fun formatRideDate(millis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(millis))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(onBack: () -> Unit, onOpen: (Long) -> Unit, viewModel: HistoryViewModel = hiltViewModel()) {
    val rides by viewModel.rides.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("History") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (rides.isEmpty()) item { Text("No rides yet.") }
            items(rides, key = { it.id }) { RideCard(it, onOpen) }
        }
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
