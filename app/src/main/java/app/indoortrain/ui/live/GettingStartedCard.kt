package app.indoortrain.ui.live

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.indoortrain.ui.common.ConnectedGreen

data class OnboardingStep(val title: String, val hint: String, val done: Boolean, val onClick: () -> Unit)

/** First-run checklist on the Live screen; disappears when all steps are done or hidden. */
@Composable
fun GettingStartedCard(steps: List<OnboardingStep>, onHide: () -> Unit) {
    if (steps.all { it.done }) return
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
    ) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Getting started", style = MaterialTheme.typography.titleMedium)
            steps.forEachIndexed { i, step ->
                Row(
                    Modifier.fillMaxWidth().clickable(onClick = step.onClick),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (step.done) {
                        Icon(Icons.Default.CheckCircle, "Done", tint = ConnectedGreen)
                    } else {
                        Icon(Icons.Default.RadioButtonUnchecked, "Open")
                    }
                    Column {
                        Text("${i + 1}. ${step.title}", style = MaterialTheme.typography.bodyLarge)
                        Text(step.hint, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            TextButton(onClick = onHide, modifier = Modifier.align(Alignment.End)) { Text("Hide") }
        }
    }
}
