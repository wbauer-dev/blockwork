package app.blockwork.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.blockwork.data.intervals.IntervalsAccount
import app.blockwork.data.intervals.IntervalsAccountStore
import app.blockwork.data.intervals.IntervalsClient
import app.blockwork.data.intervals.KeyCheck
import app.blockwork.upload.IntervalsUploads
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

@HiltViewModel
class IntervalsViewModel @Inject constructor(
    private val store: IntervalsAccountStore,
    private val client: IntervalsClient,
) : ViewModel() {
    /** Outer null while loading (so the connect form does not flash); [Loaded.account] null = not connected. */
    val account: StateFlow<Loaded?> = store.account.map { Loaded(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    data class Loaded(val account: IntervalsAccount?)

    private val _checking = MutableStateFlow(false)
    val checking: StateFlow<Boolean> = _checking.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun connect(key: String, onConnected: (String?) -> Unit) {
        val trimmed = key.trim()
        if (trimmed.isEmpty() || _checking.value) return
        viewModelScope.launch {
            _checking.value = true
            _error.value = null
            when (val r = withContext(Dispatchers.IO) { client.checkKey(trimmed) }) {
                is KeyCheck.Valid -> {
                    store.connect(trimmed, r.athleteName)
                    onConnected(r.athleteName)
                }
                KeyCheck.Rejected -> _error.value = "intervals.icu did not accept this API key."
                is KeyCheck.Failed -> _error.value = r.message
            }
            _checking.value = false
        }
    }

    fun clearError() {
        _error.value = null
    }

    fun setAutoUpload(enabled: Boolean) {
        viewModelScope.launch { store.setAutoUpload(enabled) }
    }

    fun disconnect() {
        viewModelScope.launch { store.disconnect() }
    }
}

@Composable
fun IntervalsCard(notify: (String) -> Unit, viewModel: IntervalsViewModel = hiltViewModel()) {
    val loaded by viewModel.account.collectAsStateWithLifecycle()
    val checking by viewModel.checking.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("intervals.icu", style = MaterialTheme.typography.titleMedium)
            val state = loaded ?: return@Column
            val a = state.account
            if (a == null) {
                var key by remember { mutableStateOf("") }
                val focus = LocalFocusManager.current
                fun connect() {
                    focus.clearFocus()
                    viewModel.connect(key) { name ->
                        key = ""
                        notify(name?.let { "Connected to intervals.icu as $it" } ?: "Connected to intervals.icu")
                    }
                }
                Text(
                    "Upload finished rides to your intervals.icu account. Your personal API key is on " +
                        "intervals.icu under Settings, near the bottom of the page.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = key,
                        onValueChange = { key = it; viewModel.clearError() },
                        label = { Text("API key") },
                        singleLine = true,
                        isError = error != null,
                        supportingText = { error?.let { Text(it) } },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { connect() }),
                        modifier = Modifier.weight(1f),
                    )
                    Button(onClick = { connect() }, enabled = key.isNotBlank() && !checking) {
                        Text(if (checking) "Checking…" else "Connect")
                    }
                }
                Text(
                    "Uploaded rides contain power, cadence, heart rate and beat-to-beat (RR) intervals. Once " +
                        "uploaded, intervals.icu's privacy policy applies. The key is stored encrypted on this phone.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    a.athleteName?.let { "Connected as $it" } ?: "Connected",
                    style = MaterialTheme.typography.bodyLarge,
                )
                SwitchRow(
                    title = "Upload rides automatically",
                    subtitle = "After each ride of ${IntervalsUploads.MIN_AUTO_UPLOAD_SEC / 60} minutes or more; waits " +
                        "for a network connection. Any ride can also be uploaded from its ride screen.",
                    checked = a.autoUpload,
                    onChange = viewModel::setAutoUpload,
                )
                OutlinedButton(onClick = { viewModel.disconnect(); notify("Disconnected from intervals.icu") }) {
                    Text("Disconnect")
                }
                Text(
                    "Disconnecting removes the API key from this phone. Rides already on intervals.icu stay there.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
