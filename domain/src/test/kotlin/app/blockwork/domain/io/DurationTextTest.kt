package app.blockwork.domain.io

import app.blockwork.domain.Segment
import app.blockwork.domain.Workout
import app.blockwork.domain.training.WorkoutMetrics
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DurationTextTest {
    @Test fun parse() {
        assertThat(DurationText.parse("4:30")).isEqualTo(270)
        assertThat(DurationText.parse("45")).isEqualTo(2700)
        assertThat(DurationText.parse(" 0:30 ")).isEqualTo(30)
        assertThat(DurationText.parse("1:05:00")).isEqualTo(3900)
        listOf("", "0", "0:00", "4:75", "a", "4:", "-1", "1:2:3:4").forEach { assertThat(DurationText.parse(it)).isNull() }
    }

    @Test fun format() {
        assertThat(DurationText.format(270)).isEqualTo("4:30")
        assertThat(DurationText.format(3600)).isEqualTo("60:00")
    }

    @Test fun percent() {
        assertThat(DurationText.parsePercent("115")).isWithin(1e-9).of(1.15)
        assertThat(DurationText.parsePercent("87,5 %")).isWithin(1e-9).of(0.875)
        assertThat(DurationText.parsePercent("400")).isNull()
        assertThat(DurationText.parsePercent("x")).isNull()
        assertThat(DurationText.formatPercent(1.15)).isEqualTo("115")
    }

    @Test fun estimateSteadyHour() {
        val w = Workout("e", "Hour", segments = listOf(Segment.Steady(3600, 0.75)))
        val e = WorkoutMetrics.estimate(w, 200)
        assertThat(e.normalizedPower).isEqualTo(150)
        assertThat(e.intensityFactor).isEqualTo(0.75)
        assertThat(e.tss).isEqualTo(56)
    }
}
