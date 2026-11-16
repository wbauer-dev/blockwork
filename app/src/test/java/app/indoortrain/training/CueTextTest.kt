package app.indoortrain.training

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CueTextTest {
    @Test fun describe() {
        assertThat(CuePlayer.describe(240, 280, false, "VO2max")).isEqualTo("4 minutes at 280 watts")
        assertThat(CuePlayer.describe(60, 150, false, null)).isEqualTo("1 minute at 150 watts")
        assertThat(CuePlayer.describe(30, null, true, "Free ride")).isEqualTo("30 seconds free ride")
        assertThat(CuePlayer.describe(90, 200, false, null)).isEqualTo("1 minute 30 seconds at 200 watts")
    }
}
