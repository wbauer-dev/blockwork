package app.blockwork.ui.common

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.blockwork.devices.BluetoothPermissions

/**
 * Shows what is missing for BLE (permission or adapter switched off) with a button to fix it.
 * Renders nothing when everything is fine or the app runs in simulation mode.
 */
@SuppressLint("MissingPermission") // enable request is only offered once permissions are granted
@Composable
fun BluetoothAccessBanner(
    permissionsGranted: Boolean,
    bluetoothEnabled: Boolean,
    simulation: Boolean,
    onPermissionsResult: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (simulation || (permissionsGranted && bluetoothEnabled)) return
    val context = LocalContext.current
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        onPermissionsResult(BluetoothPermissions.granted(context))
    }
    val enableLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(Modifier.padding(12.dp)) {
            if (!permissionsGranted) {
                Text("Bluetooth permission is needed to find and connect your sensors.")
                Button(
                    onClick = { permissionLauncher.launch(BluetoothPermissions.required) },
                    modifier = Modifier.padding(top = 8.dp),
                ) { Text("Grant permission") }
            } else {
                Text("Bluetooth is switched off.")
                Button(
                    onClick = { enableLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) },
                    modifier = Modifier.padding(top = 8.dp),
                ) { Text("Turn on Bluetooth") }
            }
        }
    }
}
