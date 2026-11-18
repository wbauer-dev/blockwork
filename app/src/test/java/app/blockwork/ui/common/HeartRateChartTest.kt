package app.blockwork.ui.common

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Synthetic heart-rate values only. */
class HeartRateChartTest {

    @Test fun rangeFromMaxHeartRate() {
        // half of max HR up to max HR, rounded to 10s
        assertThat(bpmRange(listOf(120, null, 150), maxHeartRate = 185)).isEqualTo(90 to 190)
    }

    @Test fun rangeWidensToIncludeReadings() {
        assertThat(bpmRange(listOf(80, 195), maxHeartRate = 185)).isEqualTo(70 to 200)
    }

    @Test fun rangeWithoutMaxHeartRate() {
        assertThat(bpmRange(listOf(130, 140, 150), maxHeartRate = null)).isEqualTo(120 to 160)
        // no readings yet: a sensible default window
        assertThat(bpmRange(listOf(null, null), maxHeartRate = null)).isEqualTo(70 to 170)
        // flat readings still get at least 40 bpm of height
        val (lo, hi) = bpmRange(listOf(100, 100), maxHeartRate = null)
        assertThat(hi - lo).isAtLeast(40)
        assertThat(lo).isAtMost(95)
        assertThat(hi).isAtLeast(105)
    }
}
