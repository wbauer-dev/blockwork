package app.indoortrain.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import app.indoortrain.BuildConfig

private const val SOURCE_URL = "https://github.com/wbauer-dev/indoortrain"

@Composable
fun AboutDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text("IndoorTrain") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                AboutLine("Version", BuildConfig.VERSION_NAME)
                AboutLine("Version code", BuildConfig.VERSION_CODE.toString())
                AboutLine("Build", if (BuildConfig.DEBUG) "Debug" else "Release")
                AboutLine("Commit", BuildConfig.GIT_COMMIT)
                AboutLine("Build date", BuildConfig.BUILD_DATE)
                AboutLine("App ID", BuildConfig.APPLICATION_ID)
                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                Text("Copyright 2026 wbauer-dev. Licensed under the Apache License 2.0.", style = MaterialTheme.typography.bodySmall)
                val link = MaterialTheme.colorScheme.primary
                Text(
                    buildAnnotatedString {
                        append("Source: ")
                        withLink(
                            LinkAnnotation.Url(
                                SOURCE_URL,
                                TextLinkStyles(SpanStyle(color = link, textDecoration = TextDecoration.Underline)),
                            ),
                        ) { append(SOURCE_URL.removePrefix("https://")) }
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "Uses the Garmin FIT SDK (FIT Protocol License), the Nordic Android BLE library (BSD-3-Clause), " +
                        "AndroidX, Jetpack Compose, Hilt/Dagger and kotlinx libraries (Apache 2.0).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )
}

@Composable
private fun AboutLine(label: String, value: String) {
    Row {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(110.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}
