package app.indoortrain.ui.workouts

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.indoortrain.data.SettingsRepository
import app.indoortrain.data.UserSettings
import app.indoortrain.data.workouts.LibraryWorkout
import app.indoortrain.data.workouts.WorkoutRepository
import app.indoortrain.data.workouts.WorkoutSource
import app.indoortrain.domain.io.WorkoutImportException
import app.indoortrain.domain.io.WorkoutImporter
import app.indoortrain.ui.common.WorkoutProfile
import app.indoortrain.ui.common.formatMinutes
import app.indoortrain.ui.common.readTextLimited
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** Outcome of a (multi-file) import, shown in a dialog. */
data class ImportReport(val imported: List<String>, val problems: List<String>, val singleImportedId: String?)

@HiltViewModel
class WorkoutListViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: WorkoutRepository,
    private val settings: SettingsRepository,
) : ViewModel() {

    val workouts: StateFlow<List<LibraryWorkout>> =
        repository.workouts.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val userSettings = settings.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UserSettings())

    private val _report = MutableStateFlow<ImportReport?>(null)
    val report: StateFlow<ImportReport?> = _report.asStateFlow()

    fun import(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val ftp = settings.settings.first().ftpWatts
            val imported = mutableListOf<String>()
            val ids = mutableListOf<String>()
            val problems = mutableListOf<String>()
            for (uri in uris) {
                val name = displayName(uri) ?: "workout"
                try {
                    val text = withContext(Dispatchers.IO) { readTextLimited(context, uri, WorkoutImporter.MAX_FILE_CHARS) }
                    val result = repository.import(name, text, ftp)
                    imported += result.workout.name
                    ids += result.workout.id
                    result.warnings.forEach { problems += "$name: $it" }
                } catch (e: WorkoutImportException) {
                    problems += "$name: ${e.message}"
                } catch (e: java.io.IOException) {
                    problems += "$name: could not be read (${e.message})"
                }
            }
            _report.value = ImportReport(imported, problems, ids.singleOrNull())
        }
    }

    fun dismissReport() {
        _report.value = null
    }

    private fun displayName(uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: uri.lastPathSegment
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkoutListScreen(
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
    onNew: () -> Unit,
    viewModel: WorkoutListViewModel = hiltViewModel(),
) {
    val workouts by viewModel.workouts.collectAsStateWithLifecycle()
    val settings by viewModel.userSettings.collectAsStateWithLifecycle()
    val report by viewModel.report.collectAsStateWithLifecycle()
    // .zwo/.erg/.mrc have no registered MIME type, so allow any file and detect the format.
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { viewModel.import(it) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Workouts") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                actions = {
                    TextButton(onClick = { picker.launch(arrayOf("*/*")) }) {
                        Icon(Icons.Default.FileOpen, null)
                        Text(" Import")
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = onNew, icon = { Icon(Icons.Default.Add, null) }, text = { Text("New") })
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 88.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Text(
                    "Targets are based on FTP ${settings.ftpWatts} W. Import .zwo, .erg, .mrc or .json files.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            val (builtIn, mine) = workouts.partition { it.source == WorkoutSource.BuiltIn }
            item { SectionTitle("My workouts") }
            if (mine.isEmpty()) {
                item { Text("None yet – import a file or tap New.", style = MaterialTheme.typography.bodySmall) }
            }
            items(mine, key = { it.workout.id }) { WorkoutCard(it, onOpen) }
            item { SectionTitle("Built-in") }
            items(builtIn, key = { it.workout.id }) { WorkoutCard(it, onOpen) }
        }
    }

    report?.let { r ->
        AlertDialog(
            onDismissRequest = viewModel::dismissReport,
            title = { Text(if (r.imported.isEmpty()) "Import failed" else "Imported ${r.imported.size} workout(s)") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    r.imported.forEach { Text("✓ $it") }
                    r.problems.forEach { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.dismissReport()
                    r.singleImportedId?.let(onOpen)
                }) { Text(if (r.singleImportedId != null) "Open" else "OK") }
            },
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun WorkoutCard(item: LibraryWorkout, onOpen: (String) -> Unit) {
    val w = item.workout
    Card(Modifier.fillMaxWidth().clickable { onOpen(w.id) }) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(w.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f), maxLines = 2)
                Text(formatMinutes(w.durationSec), style = MaterialTheme.typography.labelLarge)
            }
            if (item.source == WorkoutSource.Imported) {
                Text("Imported", style = MaterialTheme.typography.labelSmall)
            }
            WorkoutProfile(w.flatten(), Modifier.fillMaxWidth().height(48.dp))
        }
    }
}
