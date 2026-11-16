package app.indoortrain.domain.training

import app.indoortrain.domain.io.WorkoutValidator
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToInt

class BuiltInWorkoutsTest {

    @Test fun allValidWithUniqueIdsAndNames() {
        val all = BuiltInWorkouts.all
        assertThat(all).hasSize(13)
        assertThat(all.map { it.id }).containsNoDuplicates()
        assertThat(all.map { it.name }).containsNoDuplicates()
        all.forEach { w ->
            WorkoutValidator.validate(w)
            assertWithMessage(w.id).that(w.id).startsWith("builtin-")
            assertWithMessage(w.id).that(w.description).isNotEmpty()
            assertThat(BuiltInWorkouts.byId(w.id)).isSameInstanceAs(w)
        }
    }

    /** The editor shows whole percents; built-ins must round-trip through it unchanged. */
    @Test fun targetsAreWholePercents() {
        BuiltInWorkouts.all.filter { !it.isFtpTest }.forEach { w ->
            w.flatten().flatMap { listOf(it.fromFtp, it.toFtp) }.forEach { f ->
                assertWithMessage(w.id).that(abs(f * 100 - (f * 100).roundToInt())).isLessThan(1e-9)
            }
        }
    }

    @Test fun durations() {
        fun min(w: app.indoortrain.domain.Workout) = w.durationSec / 60.0
        assertThat(min(BuiltInWorkouts.recovery30)).isEqualTo(30.0)
        assertThat(min(BuiltInWorkouts.endurance90)).isEqualTo(90.0)
        assertThat(min(BuiltInWorkouts.threshold2x20)).isEqualTo(65.0)
        assertThat(min(BuiltInWorkouts.overUnders)).isEqualTo(61.0)
        assertThat(min(BuiltInWorkouts.fourByEight)).isEqualTo(58.0)
        assertThat(min(BuiltInWorkouts.thirtyThirty)).isEqualTo(40.0)
        assertThat(min(BuiltInWorkouts.thirtyFifteen)).isEqualTo(53.25)
        assertThat(min(BuiltInWorkouts.twentyTen)).isEqualTo(31.0)
        // 30/15: 3 sets × 13 reps of 30 s at 120 % FTP
        assertThat(BuiltInWorkouts.thirtyFifteen.flatten().count { it.fromFtp == 1.20 && it.durationSec == 30 }).isEqualTo(39)
    }
}
