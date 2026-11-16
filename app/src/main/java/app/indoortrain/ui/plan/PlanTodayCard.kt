package app.indoortrain.ui.plan

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.indoortrain.domain.plan.LinkedRide
import app.indoortrain.domain.plan.PlanProgress
import app.indoortrain.domain.plan.SessionStatus
import app.indoortrain.domain.plan.TrainingPlan
import app.indoortrain.ui.common.formatMinutes
import java.time.LocalDate

/** Live screen card: today's planned session (or the next one). Hidden without a plan. */
@Composable
fun PlanTodayCard(
    plan: TrainingPlan?,
    rides: List<LinkedRide>,
    onOpenPlan: () -> Unit,
    onOpenSession: (String) -> Unit,
) {
    if (plan == null) return
    val today = remember { LocalDate.now() }
    val todays = plan.sessionsOn(today)
    val todayDone = todays.isNotEmpty() && todays.all { PlanProgress.status(plan, it, today, rides) == SessionStatus.Done }
    val next = PlanProgress.nextSession(plan, today, rides)
    val target = next ?: todays.firstOrNull()

    Card(
        Modifier.fillMaxWidth().clickable { target?.let { onOpenSession(it.id) } ?: onOpenPlan() },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(14.dp)) {
            val title = when {
                today.isAfter(plan.endDate) -> "Plan finished – tap to create the next one"
                next != null && plan.dateOf(next) == today -> "Today"
                todayDone -> "Today's session done ✓ · Next: ${next?.let { plan.dateOf(it).dayLabel() } ?: "–"}"
                next != null -> "Next: ${plan.dateOf(next).dayLabel()}"
                else -> "All sessions done"
            }
            Text(title, style = MaterialTheme.typography.labelLarge)
            next?.let { s ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(s.workout.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Text(formatMinutes(s.workout.durationSec), style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}
