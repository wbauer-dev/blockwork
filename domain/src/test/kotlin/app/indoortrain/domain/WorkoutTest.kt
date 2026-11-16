package app.indoortrain.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class WorkoutTest {

    private val fourByFour = Workout(
        id = "4x4",
        name = "4x4 VO2max",
        segments = listOf(
            Segment.Ramp(600, 0.50, 0.75, "Warmup"),
            Segment.Repeat(
                4,
                listOf(Segment.Steady(240, 1.15, "On"), Segment.Steady(240, 0.50, "Off")),
            ),
            Segment.Steady(300, 0.45, "Cooldown"),
        ),
    )

    @Test fun durationIncludesRepeats() {
        assertThat(fourByFour.durationSec).isEqualTo(600 + 4 * 480 + 300)
    }

    @Test fun flattenExpandsRepeatsWithContinuousStartTimes() {
        val steps = fourByFour.flatten()
        assertThat(steps).hasSize(1 + 8 + 1)
        assertThat(steps.map { it.label }.take(3)).containsExactly("Warmup", "On", "Off").inOrder()
        steps.zipWithNext().forEach { (a, b) -> assertThat(b.startSec).isEqualTo(a.endSec) }
        assertThat(steps.last().endSec).isEqualTo(fourByFour.durationSec)
    }

    @Test fun rampTargetIsInterpolated() {
        val warmup = fourByFour.flatten().first()
        assertThat(warmup.targetWattsAt(0, ftp = 200)).isEqualTo(100)
        assertThat(warmup.targetWattsAt(300, ftp = 200)).isEqualTo(125)
        assertThat(warmup.targetWattsAt(600, ftp = 200)).isEqualTo(150)
    }

    @Test fun powerZones() {
        assertThat(PowerZone.forWatts(100, 250)).isEqualTo(PowerZone.Z1) // 40 %
        assertThat(PowerZone.forWatts(225, 250)).isEqualTo(PowerZone.Z3) // 90 % (inclusive)
        assertThat(PowerZone.forWatts(250, 250)).isEqualTo(PowerZone.Z4)
        assertThat(PowerZone.forWatts(400, 250)).isEqualTo(PowerZone.Z6)
    }
}
