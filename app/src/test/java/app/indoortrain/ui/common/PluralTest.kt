package app.indoortrain.ui.common

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PluralTest {
    @Test fun plural() {
        assertThat(plural(0, "ride")).isEqualTo("0 rides")
        assertThat(plural(1, "ride")).isEqualTo("1 ride")
        assertThat(plural(2, "ride")).isEqualTo("2 rides")
        assertThat(noun(1, "ride")).isEqualTo("ride")
        assertThat(noun(5, "ride")).isEqualTo("rides")
    }
}
