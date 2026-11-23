package app.blockwork.ui.plan

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.blockwork.data.plans.PlanRepository
import app.blockwork.domain.plan.LinkedRide
import app.blockwork.domain.plan.PlanEdit
import app.blockwork.domain.plan.PlanEditor
import app.blockwork.domain.plan.PlanProgress
import app.blockwork.domain.plan.PlannedSession
import app.blockwork.domain.plan.SessionStatus
import app.blockwork.domain.plan.TrainingPlan
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/** Feedback after a reschedule; [undo] is the plan before a successful edit. */
data class PlanNotice(val text: String, val undo: TrainingPlan? = null)

/** Stores [PlanEditor] results and keeps the previous plan for one undo. Owned by a ViewModel. */
class PlanEdits(private val plans: PlanRepository, private val scope: CoroutineScope) {
    private val _notice = MutableStateFlow<PlanNotice?>(null)
    val notice: StateFlow<PlanNotice?> = _notice.asStateFlow()

    fun apply(before: TrainingPlan, edit: PlanEdit, success: String) {
        when (edit) {
            is PlanEdit.Rejected -> _notice.value = PlanNotice(edit.describe(before))
            is PlanEdit.Ok -> if (edit.plan != before) {
                scope.launch {
                    _notice.value = if (plans.update(edit.plan)) {
                        PlanNotice(success, undo = before)
                    } else {
                        PlanNotice("The plan was changed elsewhere – nothing was moved.")
                    }
                }
            }
        }
    }

    fun undo(notice: PlanNotice) {
        _notice.value = null
        notice.undo?.let { scope.launch { plans.update(it) } }
    }

    fun dismiss() {
        _notice.value = null
    }
}

fun PlanEdit.Rejected.describe(plan: TrainingPlan): String {
    val session = sessionId?.let(plan::session)
    val what = session?.let { "${it.workout.name} (${plan.dateOf(it).dayLabel()})" } ?: "A session"
    return when (reason) {
        PlanEdit.Reason.UnknownSession -> "This session is no longer in the plan."
        PlanEdit.Reason.AlreadyDone -> "$what is done and stays where it is."
        PlanEdit.Reason.InThePast -> "$what would move before today."
        PlanEdit.Reason.DayTaken -> "${session?.let { plan.dateOf(it).dayLabel() } ?: "That day"} already has ${session?.workout?.name ?: "a session"}."
        PlanEdit.Reason.TooLong -> "The plan would be longer than ${TrainingPlan.MAX_WEEKS} weeks."
    }
}

/** Shows [notice] as a snackbar, with Undo for successful edits. */
@Composable
fun PlanNoticeSnackbar(notice: PlanNotice?, host: SnackbarHostState, edits: PlanEdits) {
    LaunchedEffect(notice) {
        if (notice == null) return@LaunchedEffect
        host.currentSnackbarData?.dismiss()
        val result = host.showSnackbar(
            notice.text,
            actionLabel = if (notice.undo != null) "Undo" else null,
            withDismissAction = notice.undo == null,
            duration = if (notice.undo != null) SnackbarDuration.Long else SnackbarDuration.Short,
        )
        if (result == SnackbarResult.ActionPerformed) edits.undo(notice) else edits.dismiss()
    }
}

/**
 * Day picker for one session: free days move it, a day with another open session swaps the two.
 * Covers a week either side of the session's day, never before today.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RescheduleSheet(
    plan: TrainingPlan,
    session: PlannedSession,
    rides: List<LinkedRide>,
    today: LocalDate,
    onMove: (LocalDate) -> Unit,
    onSwap: (PlannedSession) -> Unit,
    onDismiss: () -> Unit,
) {
    val current = plan.dateOf(session)
    val first = maxOf(today, current.minusDays(7))
    val last = maxOf(today, current).plusDays(7)
    val days = remember(first, last) { generateSequence(first) { it.plusDays(1) }.takeWhile { !it.isAfter(last) }.toList() }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            "Move ${session.workout.name}",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Text(
            "Pick a free day, or a day with another session to swap the two.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        LazyColumn(Modifier.padding(bottom = 16.dp)) {
            items(days, key = { it.toEpochDay() }) { day ->
                val other = plan.sessionsOn(day).firstOrNull()
                val otherDone = other != null && PlanProgress.status(plan, other, today, rides) == SessionStatus.Done
                val (label, enabled) = when {
                    day == current -> "current day" to false
                    other == null -> "free" to true
                    otherDone -> "${other.workout.name} · done" to false
                    else -> "swap with ${other.workout.name}" to true
                }
                Row(
                    Modifier.fillMaxWidth()
                        .clickable(enabled = enabled) { if (other == null) onMove(day) else onSwap(other) }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline
                    Text(day.dayLabel() + if (day == today) " · today" else "", style = MaterialTheme.typography.bodyLarge, color = color)
                    Text(label, style = MaterialTheme.typography.bodyMedium, color = color, textAlign = TextAlign.End)
                }
                HorizontalDivider()
            }
        }
    }
}

/** Moves every open session from a day on by a number of days, with a live preview of the result. */
@Composable
fun ShiftPlanDialog(
    plan: TrainingPlan,
    rides: List<LinkedRide>,
    today: LocalDate,
    onShift: (from: LocalDate, days: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var from by remember { mutableStateOf(maxOf(today, plan.startDate)) }
    var days by remember { mutableIntStateOf(1) }
    val result = remember(plan, rides, from, days) { PlanEditor.shift(plan, from, days, today, rides) }
    val moving = remember(plan, rides, from) {
        plan.sessions.count {
            !plan.dateOf(it).isBefore(from) && PlanProgress.status(plan, it, today, rides) != SessionStatus.Done
        }
    }
    val canShift = result is PlanEdit.Ok && result.plan != plan

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Shift plan") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Moves every session that is not done yet, from the chosen day on.", style = MaterialTheme.typography.bodyMedium)
                Stepper(
                    label = "From",
                    value = from.dayLabel(),
                    onDecrease = { from = from.minusDays(1) },
                    onIncrease = { from = from.plusDays(1) },
                    canDecrease = from.isAfter(plan.startDate),
                    canIncrease = from.isBefore(plan.endDate),
                )
                Stepper(
                    label = "By",
                    value = shiftLabel(days),
                    onDecrease = { days = (days - 1).takeIf { it != 0 } ?: -1 },
                    onIncrease = { days = (days + 1).takeIf { it != 0 } ?: 1 },
                    canDecrease = days > -14,
                    canIncrease = days < 28,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { days = 7 }) { Text("+1 week") }
                    TextButton(onClick = { days = 14 }) { Text("+2 weeks") }
                }
                Text(
                    when (result) {
                        is PlanEdit.Rejected -> result.describe(plan)
                        is PlanEdit.Ok -> if (!canShift) {
                            "No open sessions from ${from.dayLabel()} on."
                        } else {
                            "$moving session${if (moving == 1) "" else "s"} move · plan ends ${result.plan.endDate.dayLabel()}"
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (result is PlanEdit.Rejected) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onShift(from, days) }, enabled = canShift) { Text("Shift") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

fun shiftLabel(days: Int): String {
    val n = abs(days)
    val amount = if (n % 7 == 0) "${n / 7} week${if (n == 7) "" else "s"}" else "$n day${if (n == 1) "" else "s"}"
    return if (days < 0) "$amount earlier" else "$amount later"
}

/** "Moved to Wed 25 Nov" style text for a session that went from [from] to [to]. */
fun moveLabel(from: LocalDate, to: LocalDate): String {
    val diff = ChronoUnit.DAYS.between(from, to).toInt()
    return "Moved to ${to.dayLabel()} (${shiftLabel(diff)})"
}

@Composable
private fun Stepper(
    label: String,
    value: String,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
    canDecrease: Boolean,
    canIncrease: Boolean,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(0.25f))
        IconButton(onClick = onDecrease, enabled = canDecrease) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Earlier")
        }
        Text(value, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, modifier = Modifier.weight(0.5f))
        IconButton(onClick = onIncrease, enabled = canIncrease) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Later")
        }
    }
}
