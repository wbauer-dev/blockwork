package app.blockwork.ui.editor

import app.blockwork.domain.Segment
import app.blockwork.domain.training.BuiltInWorkouts
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class EditorModelTest {
    private var k = 0L
    private val key = { k++ }

    @Test fun roundTripBuiltIns() {
        BuiltInWorkouts.all.forEach { w ->
            val items = EditorModel.toItems(w.segments, key)
            val back = EditorModel.toSegments(items).getOrThrow()
            // Percent text is whole numbers; all built-ins use whole percents.
            assertThat(back.map { it.durationSec }).isEqualTo(w.segments.map { it.durationSec })
            assertThat(back).hasSize(w.segments.size)
        }
    }

    @Test fun onOffRepeatBecomesIntervalsOtherRepeatsBecomeBlocks() {
        val pair = Segment.Repeat(3, listOf(Segment.Steady(60, 1.2, "On"), Segment.Steady(60, 0.5, "Off")))
        val triple = Segment.Repeat(2, listOf(Segment.Steady(60, 1.2), Segment.Steady(30, 0.5), Segment.Steady(30, 0.9)))
        val items = EditorModel.toItems(listOf(pair, triple), key)
        assertThat(items[0]).isInstanceOf(EditorItem.Intervals::class.java)
        assertThat(items[1]).isInstanceOf(EditorItem.Block::class.java)
        assertThat(EditorModel.toSegments(items).getOrThrow()).containsExactly(pair, triple).inOrder()
    }

    @Test fun errorsNameTheInterval() {
        val items = listOf(EditorModel.newSteady(1), EditorItem.Steady(2, "5:00", "abc"))
        val error = EditorModel.toSegments(items).exceptionOrNull()!!
        assertThat(error).hasMessageThat().isEqualTo("Interval 2: invalid power")
        assertThat(EditorModel.previewSegments(items)).hasSize(1)
        assertThat(EditorModel.toSegments(emptyList()).isFailure).isTrue()
    }

    @Test fun newItemsAreValid() {
        val items = listOf(EditorModel.newSteady(1), EditorModel.newRamp(2), EditorModel.newIntervals(3), EditorModel.newFree(4))
        assertThat(EditorModel.toSegments(items).isSuccess).isTrue()
    }
}
