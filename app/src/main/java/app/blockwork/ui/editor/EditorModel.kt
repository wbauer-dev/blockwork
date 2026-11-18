package app.blockwork.ui.editor

import app.blockwork.domain.Segment
import app.blockwork.domain.io.DurationText

/**
 * Editable form of a workout's top-level segments. Fields are raw text so the user can type
 * freely; [toSegment] validates. Repeat blocks with exactly an on/off pair become [Intervals];
 * any other repeat is kept as a [Block] whose content is read-only.
 */
sealed interface EditorItem {
    val key: Long

    data class Steady(override val key: Long, val duration: String, val power: String, val label: String? = null) : EditorItem

    data class Ramp(
        override val key: Long,
        val duration: String,
        val from: String,
        val to: String,
        val label: String? = null,
    ) : EditorItem

    data class Intervals(
        override val key: Long,
        val times: String,
        val onDuration: String,
        val onPower: String,
        val offDuration: String,
        val offPower: String,
        val onLabel: String? = "On",
        val offLabel: String? = "Off",
    ) : EditorItem

    data class Free(override val key: Long, val duration: String, val label: String? = null) : EditorItem

    data class Block(override val key: Long, val times: String, val segments: List<Segment>) : EditorItem
}

object EditorModel {

    fun toItems(segments: List<Segment>, nextKey: () -> Long): List<EditorItem> = segments.map { s ->
        when (s) {
            is Segment.Steady -> EditorItem.Steady(nextKey(), d(s.durationSec), p(s.targetFtp), s.label)
            is Segment.Ramp -> EditorItem.Ramp(nextKey(), d(s.durationSec), p(s.fromFtp), p(s.toFtp), s.label)
            is Segment.FreeRide -> EditorItem.Free(nextKey(), d(s.durationSec), s.label)
            is Segment.Repeat -> {
                val on = s.segments.getOrNull(0) as? Segment.Steady
                val off = s.segments.getOrNull(1) as? Segment.Steady
                if (s.segments.size == 2 && on != null && off != null) {
                    EditorItem.Intervals(
                        nextKey(), s.times.toString(),
                        d(on.durationSec), p(on.targetFtp), d(off.durationSec), p(off.targetFtp), on.label, off.label,
                    )
                } else {
                    EditorItem.Block(nextKey(), s.times.toString(), s.segments)
                }
            }
        }
    }

    /** @return the segment, or an error message for the user. */
    fun toSegment(item: EditorItem, position: Int): Result<Segment> {
        fun err(msg: String) = Result.failure<Segment>(IllegalArgumentException("Interval $position: $msg"))
        fun dur(text: String) = DurationText.parse(text)
        fun pct(text: String) = DurationText.parsePercent(text)
        fun times(text: String) = text.trim().toIntOrNull()?.takeIf { it in 1..100 }
        return when (item) {
            is EditorItem.Steady -> {
                val d = dur(item.duration) ?: return err("invalid duration")
                val pw = pct(item.power) ?: return err("invalid power")
                Result.success(Segment.Steady(d, pw, item.label))
            }
            is EditorItem.Ramp -> {
                val d = dur(item.duration) ?: return err("invalid duration")
                val from = pct(item.from) ?: return err("invalid start power")
                val to = pct(item.to) ?: return err("invalid end power")
                Result.success(Segment.Ramp(d, from, to, item.label))
            }
            is EditorItem.Intervals -> {
                val n = times(item.times) ?: return err("repeats must be 1–100")
                val onD = dur(item.onDuration) ?: return err("invalid on duration")
                val onP = pct(item.onPower) ?: return err("invalid on power")
                val offD = dur(item.offDuration) ?: return err("invalid off duration")
                val offP = pct(item.offPower) ?: return err("invalid off power")
                Result.success(
                    Segment.Repeat(n, listOf(Segment.Steady(onD, onP, item.onLabel), Segment.Steady(offD, offP, item.offLabel))),
                )
            }
            is EditorItem.Free -> {
                val d = dur(item.duration) ?: return err("invalid duration")
                Result.success(Segment.FreeRide(d, item.label))
            }
            is EditorItem.Block -> {
                val n = times(item.times) ?: return err("repeats must be 1–100")
                Result.success(Segment.Repeat(n, item.segments))
            }
        }
    }

    /** All segments, or the first error. */
    fun toSegments(items: List<EditorItem>): Result<List<Segment>> {
        if (items.isEmpty()) return Result.failure(IllegalArgumentException("Add at least one interval"))
        return Result.success(
            items.mapIndexed { i, item -> toSegment(item, i + 1).getOrElse { return Result.failure(it) } },
        )
    }

    /** Valid segments only – for the live preview while typing. */
    fun previewSegments(items: List<EditorItem>): List<Segment> =
        items.mapIndexedNotNull { i, item -> toSegment(item, i + 1).getOrNull() }

    fun newSteady(key: Long) = EditorItem.Steady(key, "5:00", "75")
    fun newRamp(key: Long) = EditorItem.Ramp(key, "10:00", "50", "75")
    fun newIntervals(key: Long) = EditorItem.Intervals(key, "4", "4:00", "105", "2:00", "55")
    fun newFree(key: Long) = EditorItem.Free(key, "5:00", "Free ride")

    private fun d(sec: Int) = DurationText.format(sec)
    private fun p(f: Double) = DurationText.formatPercent(f)
}
