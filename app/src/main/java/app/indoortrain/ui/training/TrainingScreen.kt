package app.indoortrain.ui.training

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import android.content.res.Configuration
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalConfiguration
import app.indoortrain.domain.HeartRateZone
import app.indoortrain.ui.common.color
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.indoortrain.domain.devices.ControlResult
import app.indoortrain.domain.training.RideSummary
import app.indoortrain.domain.training.SessionPhase
import app.indoortrain.domain.training.SessionState
import app.indoortrain.data.SettingsRepository
import app.indoortrain.data.rides.RideRepository
import app.indoortrain.domain.training.FtpCheck
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.time.LocalDate
import app.indoortrain.data.TrainingPage
import app.indoortrain.data.UserSettings
import app.indoortrain.training.TrainingController
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import app.indoortrain.ui.common.ConnectedGreen
import app.indoortrain.ui.common.ConnectingAmber
import app.indoortrain.ui.common.HeartRateChart
import app.indoortrain.ui.common.KeepScreenOn
import app.indoortrain.ui.common.WorkoutProfile
import app.indoortrain.ui.common.formatDuration
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.math.roundToInt

@HiltViewModel
class TrainingViewModel @Inject constructor(
    val controller: TrainingController,
    private val settings: SettingsRepository,
    private val rides: RideRepository,
) : ViewModel() {
    val userSettings: StateFlow<UserSettings?> = settings.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _appliedFtp = MutableStateFlow<Int?>(null)
    val appliedFtp: StateFlow<Int?> = _appliedFtp.asStateFlow()

    private val _ftpCheck = MutableStateFlow<FtpCheckUi?>(null)
    val ftpCheck: StateFlow<FtpCheckUi?> = _ftpCheck.asStateFlow()

    /** Runs the FTP check once per finished ride (the ride must already be saved). */
    fun runFtpCheck(rideId: Long) {
        if (_ftpCheck.value?.rideId == rideId) return
        viewModelScope.launch {
            val s = settings.settings.first()
            _ftpCheck.value = FtpCheckUi(rideId, null, s.ftpWatts)
            val today = LocalDate.now()
            val result = withContext(Dispatchers.IO) {
                FtpCheck.analyze(
                    rides.fingerprintsSince(today.minusDays(FtpCheck.BASELINE_DAYS)),
                    s.ftpWatts, s.maxHeartRate, today,
                )
            }
            _ftpCheck.value = FtpCheckUi(rideId, result, s.ftpWatts)
        }
    }

    fun setPage(page: TrainingPage) {
        viewModelScope.launch { settings.setTrainingPage(page) }
    }

    fun applyFtp(watts: Int) {
        if (watts !in UserSettings.FTP_RANGE) return
        viewModelScope.launch {
            settings.setFtp(watts)
            _appliedFtp.value = watts
        }
    }
}

@Composable
fun TrainingScreen(onBack: () -> Unit, onDone: () -> Unit, viewModel: TrainingViewModel = hiltViewModel()) {
    val c = viewModel.controller
    val state by c.state.collectAsStateWithLifecycle()
    val settings by viewModel.userSettings.collectAsStateWithLifecycle()
    var confirmStop by remember { mutableStateOf(false) }
    KeepScreenOn()

    Scaffold { padding ->
        val s = state
        val content = Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp)
        when {
            s == null -> Column(content, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Header("Workout", null, onBack)
                Text("No workout running.")
                Button(onClick = onDone) { Text("Back") }
            }
            s.phase == SessionPhase.Finished -> Column(
                content.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Header(s.workoutName, null, onBack)
                val applied by viewModel.appliedFtp.collectAsStateWithLifecycle()
                val ftpCheck by viewModel.ftpCheck.collectAsStateWithLifecycle()
                val rideId = s.rideId
                // Ramp tests have their own result; other rides get the FTP check once saved.
                if (!s.isFtpTest && rideId != null && s.summary != null) {
                    LaunchedEffect(rideId) { viewModel.runFtpCheck(rideId) }
                }
                Summary(s, applied, viewModel::applyFtp, ftpCheck, onDone = { c.dismiss(); onDone() })
            }
            else -> Running(
                s,
                maxHeartRate = settings?.maxHeartRate,
                savedPage = settings?.trainingPage,
                modifier = content,
                onBack = onBack,
                onPageChange = viewModel::setPage,
                onPauseResume = { if (s.phase == SessionPhase.Paused) c.resume() else c.pause() },
                onSkip = { c.skipStep() },
                onExtend = { c.extendStep() },
                onIntensityUp = { c.increaseIntensity() },
                onIntensityDown = { c.decreaseIntensity() },
                onStop = { confirmStop = true },
            )
        }
    }

    if (confirmStop) {
        AlertDialog(
            onDismissRequest = { confirmStop = false },
            title = { Text("End workout?") },
            text = { Text("The ride so far is saved.") },
            confirmButton = { TextButton(onClick = { confirmStop = false; c.stop() }) { Text("End") } },
            dismissButton = { TextButton(onClick = { confirmStop = false }) { Text("Continue") } },
        )
    }
}

@Composable
private fun Header(title: String, subtitle: String?, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back (workout keeps running)") }
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        subtitle?.let { Text(it, style = MaterialTheme.typography.labelLarge) }
    }
}

/**
 * Fits on one phone screen (designed for ~750 dp usable height). Header, warnings, the next step
 * and the controls stay put; the display area between them is a carousel of [TrainingPage]s.
 * The last page the rider settled on is remembered for the next ride.
 */
@Composable
private fun Running(
    s: SessionState,
    maxHeartRate: Int?,
    savedPage: TrainingPage?,
    modifier: Modifier,
    onBack: () -> Unit,
    onPageChange: (TrainingPage) -> Unit,
    onPauseResume: () -> Unit,
    onSkip: () -> Unit,
    onExtend: () -> Unit,
    onIntensityUp: () -> Unit,
    onIntensityDown: () -> Unit,
    onStop: () -> Unit,
) {
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val pages = TrainingPage.entries
    val pager = rememberPagerState { pages.size }
    // Settings load asynchronously: restore the saved page once, then save each page the rider settles on.
    var restored by remember { mutableStateOf(false) }
    LaunchedEffect(savedPage) {
        if (!restored && savedPage != null) {
            pager.scrollToPage(savedPage.ordinal)
            restored = true
        }
    }
    LaunchedEffect(pager) {
        snapshotFlow { pager.settledPage }.collect { if (restored) onPageChange(pages[it]) }
    }

    val carousel = @Composable { m: Modifier ->
        Column(m, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth(), pageSpacing = 16.dp) { i ->
                val page = Modifier.fillMaxSize()
                when (pages[i]) {
                    TrainingPage.Numbers -> NumbersPage(s, maxHeartRate, landscape, page)
                    TrainingPage.Charts -> ChartsPage(s, maxHeartRate, page)
                }
            }
            PageDots(pager, pages)
        }
    }
    val controls = @Composable {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = onIntensityDown, contentPadding = CompactPadding) { Text("−5 %") }
            Text(
                "${(s.intensity * 100).roundToInt()} %",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
            )
            OutlinedButton(onClick = onIntensityUp, contentPadding = CompactPadding) { Text("+5 %") }
            FilledTonalButton(onClick = onExtend, contentPadding = CompactPadding) { Text("+1 min") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 8.dp)) {
            val paused = s.phase == SessionPhase.Paused
            Button(onClick = onPauseResume, modifier = Modifier.weight(2f).height(52.dp)) {
                Icon(if (paused) Icons.Default.PlayArrow else Icons.Default.Pause, null)
                Text(if (paused) " Resume" else " Pause")
            }
            FilledTonalButton(onClick = onSkip, modifier = Modifier.weight(1f).height(52.dp), contentPadding = CompactPadding) {
                Icon(Icons.Default.SkipNext, null)
                Text("Skip")
            }
            OutlinedButton(onClick = onStop, modifier = Modifier.weight(1f).height(52.dp), contentPadding = CompactPadding) {
                Icon(Icons.Default.Stop, null)
                Text("End")
            }
        }
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Header(s.workoutName, "${formatDuration(s.elapsedSec)} · ${formatDuration(s.remainingSec)} left", onBack)
        Warnings(s)
        if (landscape) {
            // Carousel left; what's next and the controls right.
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                carousel(Modifier.weight(1.3f))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    NextCard(s)
                    Spacer(Modifier.weight(1f))
                    controls()
                }
            }
        } else {
            carousel(Modifier.weight(1f))
            NextCard(s)
            controls()
        }
    }
}

private val CompactPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)

// ---- Carousel pages. To add one: add a TrainingPage entry, then a branch in Running's pager. ----

/** Big power number, step / heart rate / cadence tiles and (portrait) the workout profile. */
@Composable
private fun NumbersPage(s: SessionState, maxHeartRate: Int?, landscape: Boolean, modifier: Modifier) {
    val zone = heartRateZone(s, maxHeartRate)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PowerCard(s, Modifier.weight(1.4f))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Tile(
                label = s.currentStep.label ?: "Interval",
                value = formatDuration(s.stepRemainingSec),
                unit = "",
                footer = "Step ${s.stepIndex + 1}/${s.steps.size}",
                modifier = Modifier.weight(1.2f),
            )
            Tile(
                if (zone != null) "Heart rate · Z${zone.number}" else "Heart rate",
                s.heartRateBpm?.toString(), "bpm",
                modifier = Modifier.weight(1f),
                tint = zone?.color(),
            )
            Tile("Cadence", s.cadenceRpm?.toString(), "rpm", modifier = Modifier.weight(1f))
        }
        // Landscape has no room left under the power card; the charts page has the profile.
        if (!landscape) Profile(s, Modifier.weight(1f))
    }
}

/** Compact numbers plus power and heart-rate history on a shared time axis. */
@Composable
private fun ChartsPage(s: SessionState, maxHeartRate: Int?, modifier: Modifier) {
    val zone = heartRateZone(s, maxHeartRate)
    val cell = Modifier.fillMaxHeight()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Tile(
                "Power",
                s.power3s?.toString(), "W",
                modifier = cell.weight(1.1f),
                footer = if (s.targetWatts == null && s.currentStep.freeRide) "Free ride" else "Target ${s.targetWatts ?: "–"}",
                tint = powerTint(s),
                valueSize = CompactValue,
            )
            // "107 bpm" does not fit these narrow tiles; units always go under the number so the row
            // height does not jump when heart rate or cadence reaches three digits.
            Tile(
                if (zone != null) "HR · Z${zone.number}" else "HR",
                s.heartRateBpm?.toString(), "bpm",
                modifier = cell.weight(1f),
                tint = zone?.color(),
                valueSize = CompactValue,
                unitBelow = true,
            )
            Tile("Cadence", s.cadenceRpm?.toString(), "rpm", modifier = cell.weight(1f), valueSize = CompactValue, unitBelow = true)
            Tile(
                s.currentStep.label ?: "Interval",
                formatDuration(s.stepRemainingSec), "",
                modifier = cell.weight(1.1f),
                footer = "Step ${s.stepIndex + 1}/${s.steps.size}",
                valueSize = CompactValue,
            )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            ChartLabel("Power · target")
            Profile(s, Modifier.weight(1.2f))
            ChartLabel(if (maxHeartRate != null) "Heart rate · zones" else "Heart rate")
            HeartRateChart(
                history = s.heartRateHistory,
                totalSec = s.steps.lastOrNull()?.endSec ?: s.totalDurationSec,
                maxHeartRate = maxHeartRate,
                progressSec = s.currentStep.startSec + s.stepElapsedSec,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
        }
    }
}

/** Workout profile with the ridden power and a progress marker. */
@Composable
private fun Profile(s: SessionState, modifier: Modifier) {
    WorkoutProfile(
        steps = s.steps,
        modifier = modifier.fillMaxWidth().heightIn(min = 48.dp),
        progressSec = s.currentStep.startSec + s.stepElapsedSec,
        powerHistory = s.powerHistory,
        ftp = s.ftp,
        intensity = s.intensity,
    )
}

/** One dot per page; tapping a dot jumps to that page. */
@Composable
private fun PageDots(pager: PagerState, pages: List<TrainingPage>) {
    val scope = rememberCoroutineScope()
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        pages.forEachIndexed { i, page ->
            val selected = pager.currentPage == i
            Box(
                Modifier
                    .size(28.dp)
                    .clickable(onClickLabel = "Show ${page.title}") { scope.launch { pager.animateScrollToPage(i) } }
                    .semantics { contentDescription = "${page.title} page" + if (selected) ", shown" else "" },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier.size(if (selected) 10.dp else 8.dp).clip(CircleShape).background(
                        if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    ),
                )
            }
        }
    }
}

private fun heartRateZone(s: SessionState, maxHeartRate: Int?): HeartRateZone? =
    s.heartRateBpm?.let { bpm -> maxHeartRate?.let { HeartRateZone.forBpm(bpm, it) } }

private val CompactValue = 26.sp

@Composable
private fun ChartLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Under / on / over target colouring for the power display; null without a target or reading. */
@Composable
private fun powerTint(s: SessionState): Color? {
    val target = s.targetWatts
    val power = s.power3s
    if (target == null || power == null || target <= 0) return null
    val ratio = power.toDouble() / target
    return when {
        ratio < 0.95 -> ConnectingAmber
        ratio > 1.05 -> MaterialTheme.colorScheme.error
        else -> ConnectedGreen
    }
}

@Composable
private fun Warnings(s: SessionState) {
    val messages = buildList {
        if (s.phase == SessionPhase.Paused) add("Paused – trainer at easy resistance")
        if (!s.trainerConnected) add("Trainer disconnected – reconnecting…")
        when (s.controlProblem) {
            null, ControlResult.Success -> Unit
            ControlResult.NotConnected -> Unit
            else -> add("Trainer did not accept the target (${s.controlProblem}) – retrying")
        }
    }
    messages.forEach {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
            Text(it, Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun PowerCard(s: SessionState, modifier: Modifier = Modifier) {
    val target = s.targetWatts
    val power = s.power3s
    val tint = powerTint(s)
    Card(
        modifier.fillMaxWidth(),
        colors = if (tint != null) {
            CardDefaults.cardColors(containerColor = tint.copy(alpha = 0.25f))
        } else {
            CardDefaults.cardColors()
        },
    ) {
        Column(
            Modifier.fillMaxSize().padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("Power (3 s)", style = MaterialTheme.typography.labelLarge)
            Text(power?.toString() ?: "--", fontSize = 84.sp, fontWeight = FontWeight.Bold, lineHeight = 90.sp)
            Text(
                if (target == null && s.currentStep.freeRide) "Free ride – no ERG" else "Target ${target ?: "–"} W",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun NextCard(s: SessionState) {
    val next = s.nextStep
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            if (next == null) {
                Text("Last interval", style = MaterialTheme.typography.titleMedium)
            } else {
                Text(
                    "Next in ${formatDuration(s.stepRemainingSec)}",
                    style = MaterialTheme.typography.labelLarge,
                )
                Text(
                    "${next.label ?: "Interval"} · ${formatDuration(next.durationSec)} " +
                        (if (next.freeRide) "free ride" else "@ ${s.nextStepWatts} W") +
                        if (next.isRamp) " (ramp)" else "",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun Tile(
    label: String,
    value: String?,
    unit: String,
    modifier: Modifier = Modifier,
    footer: String? = null,
    tint: Color? = null,
    valueSize: TextUnit = 34.sp,
    unitBelow: Boolean = false,
) {
    Card(
        modifier,
        colors = if (tint != null) CardDefaults.cardColors(containerColor = tint.copy(alpha = 0.3f)) else CardDefaults.cardColors(),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            ValueWithUnit(value ?: "--", unit, valueSize, unitBelow)
            footer?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

/**
 * Value with its unit on the same baseline; the unit moves under the value when both do not fit
 * the width ("1150 W" in a narrow tile), or always with [unitBelow].
 */
@Composable
private fun ValueWithUnit(value: String, unit: String, valueSize: TextUnit, unitBelow: Boolean) {
    val valueText = @Composable {
        Text(value, fontSize = valueSize, fontWeight = FontWeight.Bold, lineHeight = valueSize * 1.12f, maxLines = 1, softWrap = false)
    }
    if (unit.isEmpty()) {
        valueText()
        return
    }
    Layout(
        content = {
            valueText()
            Text(unit, style = MaterialTheme.typography.labelMedium, maxLines = 1, softWrap = false)
        },
    ) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val v = measurables[0].measure(loose)
        val u = measurables[1].measure(loose)
        val gap = 3.dp.roundToPx()
        if (!unitBelow && v.width + gap + u.width <= constraints.maxWidth) {
            val baseline = maxOf(v[FirstBaseline], u[FirstBaseline])
            val vy = baseline - v[FirstBaseline]
            val uy = baseline - u[FirstBaseline]
            val width = v.width + gap + u.width
            layout(width, maxOf(vy + v.height, uy + u.height)) {
                v.place(0, vy)
                u.place(v.width + gap, uy)
            }
        } else {
            val width = maxOf(v.width, u.width)
            layout(width, v.height + u.height) {
                v.place((width - v.width) / 2, 0)
                u.place((width - u.width) / 2, v.height)
            }
        }
    }
}

@Composable
private fun Summary(
    s: SessionState,
    appliedFtp: Int?,
    onApplyFtp: (Int) -> Unit,
    ftpCheck: FtpCheckUi?,
    onDone: () -> Unit,
) {
    Text(
        when {
            s.isFtpTest -> "Ramp test finished"
            s.completed -> "Workout complete"
            else -> "Workout ended"
        },
        style = MaterialTheme.typography.headlineMedium,
    )
    WorkoutProfile(
        steps = s.steps,
        modifier = Modifier.fillMaxWidth().height(110.dp),
        powerHistory = s.powerHistory,
        ftp = s.ftp,
        intensity = s.intensity,
    )
    val sum: RideSummary? = s.summary
    if (sum == null) {
        Text("Saving…")
    } else {
        val estimate = sum.rampTestFtp
        if (s.isFtpTest && estimate != null) {
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Estimated FTP: $estimate W", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(
                        "75 % of your best 1-minute power (${sum.bestPower1Min} W). Current FTP: ${s.ftp} W.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (appliedFtp == estimate) {
                        Text("FTP updated to $estimate W.", fontWeight = FontWeight.SemiBold)
                    } else if (estimate in UserSettings.FTP_RANGE) {
                        Button(onClick = { onApplyFtp(estimate) }) { Text("Use $estimate W as FTP") }
                    }
                }
            }
        }
        if (ftpCheck != null && ftpCheck.rideId == s.rideId) FtpCheckCard(ftpCheck)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SummaryLine("Duration", formatDuration(sum.durationSec))
                SummaryLine("Average power", sum.avgPower?.let { "$it W" })
                SummaryLine("Normalized power", sum.normalizedPower?.let { "$it W" })
                SummaryLine("Max power", sum.maxPower?.let { "$it W" })
                SummaryLine("Best 1 min", sum.bestPower1Min?.let { "$it W" })
                SummaryLine("Average heart rate", sum.avgHeartRate?.let { "$it bpm" })
                SummaryLine("Max heart rate", sum.maxHeartRate?.let { "$it bpm" })
                SummaryLine("Average cadence", sum.avgCadence?.let { "$it rpm" })
                SummaryLine("Energy", "${sum.energyKj} kJ")
                SummaryLine("Intensity factor", sum.intensityFactor?.let { "%.2f".format(it) })
                SummaryLine("TSS", sum.tss?.toString())
                SummaryLine("Distance", "%.1f km".format(sum.distanceMeters / 1000.0))
            }
        }
        Text("The ride is saved on this phone.", style = MaterialTheme.typography.bodySmall)
    }
    Button(onClick = onDone, modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) { Text("Done") }
}

@Composable
private fun SummaryLine(label: String, value: String?) {
    Row {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Text(value ?: "–", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
    }
}
