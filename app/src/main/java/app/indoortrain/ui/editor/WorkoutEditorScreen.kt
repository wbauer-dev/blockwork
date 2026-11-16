package app.indoortrain.ui.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.indoortrain.data.SettingsRepository
import app.indoortrain.data.UserSettings
import app.indoortrain.data.workouts.WorkoutRepository
import app.indoortrain.domain.Workout
import app.indoortrain.domain.io.DurationText
import app.indoortrain.domain.io.WorkoutImportException
import app.indoortrain.ui.common.WorkoutProfile
import app.indoortrain.ui.common.formatMinutes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class WorkoutEditorViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val repository: WorkoutRepository,
    settings: SettingsRepository,
) : ViewModel() {
    private val sourceId: String? = savedState["id"]
    private val copy: Boolean = savedState["copy"] ?: false
    private var nextKey = 0L
    private val key = { nextKey++ }

    /** Id the workout is saved under (new id for new workouts and copies). */
    private val targetId: String = if (sourceId == null || copy) repository.newId() else sourceId
    val isNew: Boolean = sourceId == null || copy

    val name = MutableStateFlow("")
    val description = MutableStateFlow("")
    private val _items = MutableStateFlow<List<EditorItem>>(emptyList())
    val items: StateFlow<List<EditorItem>> = _items.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    val userSettings = settings.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UserSettings())

    /** Live preview built from the currently valid items. */
    val preview: StateFlow<Workout?> = combine(name, _items) { n, list ->
        EditorModel.previewSegments(list).takeIf { it.isNotEmpty() }?.let { Workout(targetId, n, segments = it) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        viewModelScope.launch {
            val source = sourceId?.let { repository.get(it) }?.workout
            if (source != null) {
                name.value = if (copy) "${source.name} (copy)" else source.name
                description.value = source.description
                _items.value = EditorModel.toItems(source.segments, key)
            } else {
                name.value = "New workout"
                _items.value = listOf(
                    EditorItem.Ramp(key(), "10:00", "50", "75", "Warmup"),
                    EditorModel.newIntervals(key()),
                    EditorItem.Ramp(key(), "5:00", "60", "45", "Cooldown"),
                )
            }
        }
    }

    fun update(item: EditorItem) {
        _items.update { list -> list.map { if (it.key == item.key) item else it } }
        _error.value = null
    }

    fun add(factory: (Long) -> EditorItem) = _items.update { it + factory(key()) }

    fun remove(k: Long) = _items.update { list -> list.filterNot { it.key == k } }

    fun move(k: Long, delta: Int) = _items.update { list ->
        val i = list.indexOfFirst { it.key == k }
        val j = i + delta
        if (i < 0 || j !in list.indices) list else list.toMutableList().apply { add(j, removeAt(i)) }
    }

    fun save(onSaved: (String) -> Unit) {
        val segments = EditorModel.toSegments(_items.value).getOrElse {
            _error.value = it.message
            return
        }
        viewModelScope.launch {
            try {
                repository.save(Workout(targetId, name.value.trim(), description.value.trim(), segments))
                onSaved(targetId)
            } catch (e: WorkoutImportException) {
                _error.value = e.message
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkoutEditorScreen(
    onBack: () -> Unit,
    onSaved: (String) -> Unit,
    viewModel: WorkoutEditorViewModel = hiltViewModel(),
) {
    val name by viewModel.name.collectAsStateWithLifecycle()
    val description by viewModel.description.collectAsStateWithLifecycle()
    val items by viewModel.items.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val preview by viewModel.preview.collectAsStateWithLifecycle()
    val settings by viewModel.userSettings.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (viewModel.isNew) "New workout" else "Edit workout") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Discard") }
                },
                actions = { TextButton(onClick = { viewModel.save(onSaved) }) { Text("Save") } },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                OutlinedTextField(
                    value = name, onValueChange = { viewModel.name.value = it },
                    label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                OutlinedTextField(
                    value = description, onValueChange = { viewModel.description.value = it },
                    label = { Text("Description") }, modifier = Modifier.fillMaxWidth(), maxLines = 3,
                )
            }
            item {
                Column {
                    val p = preview
                    Text(
                        if (p != null) "${formatMinutes(p.durationSec)} · targets at FTP ${settings.ftpWatts} W" else "No valid intervals yet",
                        style = MaterialTheme.typography.labelLarge,
                    )
                    WorkoutProfile(p?.flatten().orEmpty(), Modifier.fillMaxWidth().height(80.dp).padding(top = 4.dp))
                    Text(
                        "Durations as m:ss (or minutes), power in % of FTP.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
            itemsIndexed(items, key = { _, it -> it.key }) { index, item ->
                ItemCard(
                    item = item,
                    index = index,
                    ftp = settings.ftpWatts,
                    canMoveUp = index > 0,
                    canMoveDown = index < items.lastIndex,
                    onChange = viewModel::update,
                    onMove = { viewModel.move(item.key, it) },
                    onRemove = { viewModel.remove(item.key) },
                )
            }
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AddChip("Steady") { viewModel.add(EditorModel::newSteady) }
                    AddChip("Ramp") { viewModel.add(EditorModel::newRamp) }
                    AddChip("Intervals") { viewModel.add(EditorModel::newIntervals) }
                    AddChip("Free ride") { viewModel.add(EditorModel::newFree) }
                }
            }
        }
    }
}

@Composable
private fun AddChip(label: String, onClick: () -> Unit) {
    AssistChip(onClick = onClick, label = { Text(label) }, leadingIcon = { Icon(Icons.Default.Add, null) })
}

@Composable
private fun ItemCard(
    item: EditorItem,
    index: Int,
    ftp: Int,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onChange: (EditorItem) -> Unit,
    onMove: (Int) -> Unit,
    onRemove: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 12.dp, end = 4.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val title = when (item) {
                    is EditorItem.Steady -> "Steady"
                    is EditorItem.Ramp -> "Ramp"
                    is EditorItem.Intervals -> "Intervals"
                    is EditorItem.Free -> "Free ride"
                    is EditorItem.Block -> "Repeat block"
                }
                val label = when (item) {
                    is EditorItem.Steady -> item.label
                    is EditorItem.Ramp -> item.label
                    is EditorItem.Free -> item.label
                    else -> null
                }
                Text(
                    "${index + 1}. $title" + (label?.takeIf { it != title }?.let { " · $it" } ?: ""),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { onMove(-1) }, enabled = canMoveUp) { Icon(Icons.Default.KeyboardArrowUp, "Move up") }
                IconButton(onClick = { onMove(1) }, enabled = canMoveDown) { Icon(Icons.Default.KeyboardArrowDown, "Move down") }
                IconButton(onClick = onRemove) { Icon(Icons.Default.Delete, "Remove") }
            }
            Column(Modifier.padding(end = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                when (item) {
                    is EditorItem.Steady -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DurationField("Duration", item.duration, Modifier.weight(1f)) { onChange(item.copy(duration = it)) }
                        PercentField("Power", item.power, ftp, Modifier.weight(1f)) { onChange(item.copy(power = it)) }
                    }
                    is EditorItem.Ramp -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DurationField("Duration", item.duration, Modifier.weight(1f)) { onChange(item.copy(duration = it)) }
                        PercentField("From", item.from, ftp, Modifier.weight(1f)) { onChange(item.copy(from = it)) }
                        PercentField("To", item.to, ftp, Modifier.weight(1f)) { onChange(item.copy(to = it)) }
                    }
                    is EditorItem.Intervals -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            NumberField("Repeats", item.times, Modifier.weight(1f)) { onChange(item.copy(times = it)) }
                            DurationField("On", item.onDuration, Modifier.weight(1f)) { onChange(item.copy(onDuration = it)) }
                            PercentField("On power", item.onPower, ftp, Modifier.weight(1f)) { onChange(item.copy(onPower = it)) }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("", Modifier.weight(1f))
                            DurationField("Off", item.offDuration, Modifier.weight(1f)) { onChange(item.copy(offDuration = it)) }
                            PercentField("Off power", item.offPower, ftp, Modifier.weight(1f)) { onChange(item.copy(offPower = it)) }
                        }
                    }
                    is EditorItem.Free -> Row {
                        DurationField("Duration", item.duration, Modifier.weight(1f)) { onChange(item.copy(duration = it)) }
                        Text(
                            "No ERG target – pedal freely.",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(2f).padding(start = 8.dp, top = 16.dp),
                        )
                    }
                    is EditorItem.Block -> Row(verticalAlignment = Alignment.CenterVertically) {
                        NumberField("Repeats", item.times, Modifier.weight(1f)) { onChange(item.copy(times = it)) }
                        Text(
                            "${item.segments.size} steps (from import; content not editable here)",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(2f).padding(start = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DurationField(label: String, value: String, modifier: Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true,
        isError = DurationText.parse(value) == null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), // digits + ':'
        modifier = modifier,
    )
}

@Composable
private fun PercentField(label: String, value: String, ftp: Int, modifier: Modifier, onChange: (String) -> Unit) {
    val fraction = DurationText.parsePercent(value)
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true,
        isError = fraction == null,
        suffix = { Text("%") },
        supportingText = { fraction?.let { Text("${(it * ftp).toInt()} W") } },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}

@Composable
private fun NumberField(label: String, value: String, modifier: Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true,
        isError = value.trim().toIntOrNull()?.let { it in 1..100 } != true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}
