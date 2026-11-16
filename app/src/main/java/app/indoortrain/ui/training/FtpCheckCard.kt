package app.indoortrain.ui.training

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.indoortrain.domain.training.EstimateUnavailable
import app.indoortrain.domain.training.FtpCheck
import app.indoortrain.domain.training.FtpCheckResult
import app.indoortrain.domain.training.FtpVerdict
import kotlin.math.abs
import kotlin.math.roundToInt

/** Result of the post-workout FTP check; [result] is null while it runs. */
data class FtpCheckUi(val rideId: Long, val result: FtpCheckResult?, val ftp: Int)

@Composable
fun FtpCheckCard(ui: FtpCheckUi) {
    val r = ui.result
    val hint = r != null && (r.verdict == FtpVerdict.Up || r.verdict == FtpVerdict.Down)
    Card(
        Modifier.fillMaxWidth(),
        colors = if (hint) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer) else CardDefaults.cardColors(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (r == null) {
                Text("Checking your FTP…", style = MaterialTheme.typography.bodyMedium)
                return@Column
            }
            Text(
                when (r.verdict) {
                    FtpVerdict.Up -> "Your FTP may have gone up"
                    FtpVerdict.Down -> "Your FTP may be set too high"
                    FtpVerdict.NoChange -> "FTP check: no clear sign of a change"
                    FtpVerdict.NotEnoughData -> "FTP check: not enough data yet"
                },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            reasons(r).forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }

            val e = r.estimate
            if (e != null) {
                Text(
                    "Estimated FTP: ${e.watts} W (range ${e.low}–${e.high} W) · current ${ui.ftp} W",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Small(
                    "Extrapolated from your heart rate vs power in steady parts of the last " +
                        "${FtpCheck.ESTIMATE_DAYS / 7} weeks, assuming a threshold heart rate of " +
                        "${e.thresholdHrLow}–${e.thresholdHrHigh} bpm (${pct(FtpCheck.THRESHOLD_LOW)}–" +
                        "${pct(FtpCheck.THRESHOLD_HIGH)} of your max). A rough estimate, not a test.",
                )
            } else {
                when (r.estimateUnavailable) {
                    EstimateUnavailable.NoMaxHeartRate ->
                        Small("Set your maximum heart rate in Settings to also get an FTP estimate.")
                    EstimateUnavailable.NotEnoughData ->
                        Small(
                            "No FTP estimate yet: it needs about 20 minutes of steady riding with heart rate " +
                                "at clearly different power levels within the last ${FtpCheck.ESTIMATE_DAYS / 7} weeks.",
                        )
                    null -> Unit
                }
            }

            when (r.verdict) {
                FtpVerdict.Up, FtpVerdict.Down -> Text(
                    "Ride the FTP ramp test on a rested day to confirm before changing your FTP.",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                FtpVerdict.NotEnoughData -> Small(
                    "The check compares heart rate at the same power across rides with a heart-rate strap, " +
                        "and how you adjust the intensity in hard workouts. It needs a few weeks of rides.",
                )
                FtpVerdict.NoChange -> Unit
            }
        }
    }
}

private fun reasons(r: FtpCheckResult): List<String> = buildList {
    r.hrTrend?.let { t ->
        val bpm = abs(t.deltaBpm).roundToInt()
        add(
            when {
                bpm == 0 -> "Heart rate at the same power is unchanged from 3–9 weeks ago."
                t.deltaBpm < 0 -> "Heart rate at the same power is $bpm bpm lower than 3–9 weeks ago."
                else -> "Heart rate at the same power is $bpm bpm higher than 3–9 weeks ago."
            },
        )
    }
    r.intensity?.let { t ->
        val of = "of your last ${t.hardRides} hard workouts"
        add(
            when {
                t.raised > 0 && t.lowered > 0 ->
                    "Mixed: you raised the intensity in ${t.raised} and lowered it or ended early in ${t.lowered} $of."
                t.raised > 0 -> "You raised the intensity in ${t.raised} $of."
                t.lowered > 0 -> "You lowered the intensity or ended early in ${t.lowered} $of."
                else -> "Your last ${t.hardRides} hard workouts went as planned."
            },
        )
    }
}

private fun pct(f: Double) = "${(f * 100).roundToInt()} %"

@Composable
private fun Small(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
