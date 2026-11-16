package app.indoortrain.ui.common

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/**
 * Start flow shared by all "Start workout" buttons: asks to confirm when the trainer is not
 * connected and requests the notification permission once, then calls [onStart].
 * Returns the click handler; the dialog is rendered by this composable.
 */
@Composable
fun rememberWorkoutStarter(trainerConnected: () -> Boolean, onStart: () -> Unit): () -> Unit {
    val context = LocalContext.current
    val start by rememberUpdatedState(onStart)
    var confirm by remember { mutableStateOf(false) }
    // The workout runs either way; without permission the notification is just hidden.
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { start() }

    fun launch() {
        val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) start() else permission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Trainer not connected") },
            text = { Text("The workout starts anyway and controls the trainer as soon as it connects.") },
            confirmButton = { TextButton(onClick = { confirm = false; launch() }) { Text("Start anyway") } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
        )
    }
    return { if (trainerConnected()) launch() else confirm = true }
}
