package app.indoortrain.ui.common

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RideChartTest {

    @Test fun niceStepPicksOneTwoOrFive() {
        assertThat(niceStep(400, maxTicks = 4)).isEqualTo(100)
        assertThat(niceStep(430, maxTicks = 4)).isEqualTo(200)
        assertThat(niceStep(100, maxTicks = 4)).isEqualTo(50)
        assertThat(niceStep(40, maxTicks = 4)).isEqualTo(10)
        assertThat(niceStep(1000, maxTicks = 4)).isEqualTo(500)
        assertThat(niceStep(0, maxTicks = 4)).isEqualTo(1)
    }

    @Test fun timeTickStepFitsMaxTicks() {
        assertThat(timeTickStep(3600, maxTicks = 6)).isEqualTo(600)
        assertThat(timeTickStep(3600, maxTicks = 4)).isEqualTo(900)
        assertThat(timeTickStep(45 * 60, maxTicks = 5)).isEqualTo(600)
        assertThat(timeTickStep(90, maxTicks = 5)).isEqualTo(30)
        assertThat(timeTickStep(5 * 3600, maxTicks = 5)).isEqualTo(3600)
        assertThat(timeTickStep(100 * 3600, maxTicks = 3)).isEqualTo(7200)
    }
}
