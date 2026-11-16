package app.indoortrain.ui.history

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class NiceCeilingTest {
    @Test fun roundsUpToNiceSteps() {
        assertThat(niceCeiling(230.0, 100.0)).isEqualTo(250.0)
        assertThat(niceCeiling(410.0, 100.0)).isEqualTo(500.0)
        assertThat(niceCeiling(100.0, 100.0)).isEqualTo(100.0)
        assertThat(niceCeiling(0.0, 100.0)).isEqualTo(100.0)
        assertThat(niceCeiling(3.2, 2.0)).isEqualTo(5.0)
        assertThat(niceCeiling(1.1, 2.0)).isEqualTo(2.0)
        assertThat(niceCeiling(11.0, 2.0)).isEqualTo(20.0)
    }
}
