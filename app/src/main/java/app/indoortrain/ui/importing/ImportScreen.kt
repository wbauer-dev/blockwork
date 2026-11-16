package app.indoortrain.ui.importing

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import app.indoortrain.data.SettingsRepository
import app.indoortrain.data.plans.PlanRepository
import app.indoortrain.data.workouts.WorkoutRepository
import app.indoortrain.domain.io.PlanJson
import app.indoortrain.domain.io.WorkoutImportException
import app.indoortrain.domain.io.WorkoutImporter
import app.indoortrain.ui.common.displayName
import app.indoortrain.ui.common.readTextLimited
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** A file or text handed to the app from outside ("Open with", share sheet). */
sealed interface ExternalImport {
    data class FromUri(val uri: Uri) : ExternalImport
    data class FromText(val text: String) : ExternalImport
}

/** Hands the incoming file from MainActivity to the import screen. */
@Singleton
class ExternalImportHolder @Inject constructor() {
    val pending = MutableStateFlow<ExternalImport?>(null)
}

sealed interface ImportState {
    data object Working : ImportState
    data class ConfirmPlan(val name: String, val weeks: Int, val replaces: String?) : ImportState
    data class WorkoutImported(val id: String, val name: String, val warnings: List<String>) : ImportState
    data object PlanImported : ImportState
    data class Failed(val message: String) : ImportState
}

@HiltViewModel
class ImportViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val holder: ExternalImportHolder,
    private val workouts: WorkoutRepository,
    private val plans: PlanRepository,
    private val settings: SettingsRepository,
) : ViewModel() {
    private val _state = MutableStateFlow<ImportState>(ImportState.Working)
    val state: StateFlow<ImportState> = _state.asStateFlow()
    private var planText: String? = null

    init {
        viewModelScope.launch {
            val request = holder.pending.value
            holder.pending.value = null
            _state.value = if (request == null) ImportState.Failed("Nothing to import") else process(request)
        }
    }

    private suspend fun process(request: ExternalImport): ImportState = try {
        val (name, text) = when (request) {
            is ExternalImport.FromText -> "shared.json" to request.text
            is ExternalImport.FromUri -> withContext(Dispatchers.IO) {
                (displayName(context, request.uri) ?: "file") to readTextLimited(context, request.uri, WorkoutImporter.MAX_FILE_CHARS)
            }
        }
        if (text.contains("\"${PlanJson.FORMAT}\"")) {
            planText = text
            val preview = PlanJson.decode(text, "preview", java.time.LocalDate.now())
            ImportState.ConfirmPlan(preview.name, preview.weeks.size, plans.activePlan.first()?.name)
        } else {
            val result = workouts.import(name, text, settings.settings.first().ftpWatts)
            ImportState.WorkoutImported(result.workout.id, result.workout.name, result.warnings)
        }
    } catch (e: WorkoutImportException) {
        ImportState.Failed(e.message ?: "Import failed")
    } catch (e: IOException) {
        ImportState.Failed("File could not be read: ${e.message}")
    } catch (e: SecurityException) {
        ImportState.Failed("No permission to read this file – save it first, then use Import in the app")
    }

    fun confirmPlan() {
        val text = planText ?: return
        viewModelScope.launch {
            _state.value = try {
                plans.import(text)
                ImportState.PlanImported
            } catch (e: WorkoutImportException) {
                ImportState.Failed(e.message ?: "Import failed")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportScreen(
    onClose: () -> Unit,
    onOpenWorkout: (String) -> Unit,
    onOpenPlan: () -> Unit,
    viewModel: ImportViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Import") },
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Close") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (val s = state) {
                ImportState.Working -> CircularProgressIndicator()
                is ImportState.ConfirmPlan -> {
                    Text("Training plan \"${s.name}\" (${s.weeks} weeks)", style = MaterialTheme.typography.titleMedium)
                    Text(
                        (s.replaces?.let { "This replaces your current plan \"$it\". " } ?: "") +
                            "The plan starts on the next Monday (or today if today is Monday).",
                    )
                    Button(onClick = viewModel::confirmPlan, modifier = Modifier.fillMaxWidth()) { Text("Import plan") }
                    OutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
                }
                is ImportState.WorkoutImported -> {
                    Text("Imported \"${s.name}\"", style = MaterialTheme.typography.titleMedium)
                    s.warnings.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                    Button(onClick = { onOpenWorkout(s.id) }, modifier = Modifier.fillMaxWidth()) { Text("Open workout") }
                }
                ImportState.PlanImported -> {
                    Text("Plan imported.", style = MaterialTheme.typography.titleMedium)
                    Button(onClick = onOpenPlan, modifier = Modifier.fillMaxWidth()) { Text("Open plan") }
                }
                is ImportState.Failed -> {
                    Text("Import failed", style = MaterialTheme.typography.titleMedium)
                    Text(s.message, color = MaterialTheme.colorScheme.error)
                    OutlinedButton(onClick = onClose) { Text("Close") }
                }
            }
        }
    }
}
