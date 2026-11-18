package app.blockwork.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.blockwork.domain.devices.ConnectionState
import app.blockwork.domain.devices.DeviceStatus

val ConnectedGreen = Color(0xFF2E9D57)
val ConnectingAmber = Color(0xFFE0A100)

fun ConnectionState.color(fallback: Color): Color = when (this) {
    ConnectionState.Connected -> ConnectedGreen
    ConnectionState.Connecting -> ConnectingAmber
    ConnectionState.Disconnected -> fallback
}

fun ConnectionState.label(): String = when (this) {
    ConnectionState.Connected -> "Connected"
    ConnectionState.Connecting -> "Connecting…"
    ConnectionState.Disconnected -> "Disconnected"
}

@Composable
fun DeviceStatusCard(
    icon: ImageVector,
    title: String,
    pairedName: String?,
    status: DeviceStatus,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(icon, contentDescription = null)
                Text(title, style = MaterialTheme.typography.titleSmall)
            }
            Text(
                status.name ?: pairedName ?: "Not paired",
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val outline = MaterialTheme.colorScheme.outline
                Box(Modifier.size(10.dp).background(status.connection.color(outline), CircleShape))
                Text(status.connection.label(), style = MaterialTheme.typography.bodySmall)
                status.batteryPercent?.let { Text("· Battery $it %", style = MaterialTheme.typography.bodySmall) }
            }
            val error = status.error
            if (status.connection != ConnectionState.Connected && error != null) {
                Text(
                    error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
