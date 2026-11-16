package app.indoortrain.domain.plan

import app.indoortrain.domain.Segment
import app.indoortrain.domain.Workout
import app.indoortrain.domain.training.BuiltInWorkouts
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Parameterized workouts for the plan generator. Interval sessions follow progression
 * tables: a higher [level] means more reps, then longer reps, then higher intensity.
 * Every session is warmup + main set + endurance filler + cooldown and lasts exactly the
 * requested time.
 */
object WorkoutTemplates {

    /** reps × repMin at pct FTP with restMin between reps. */
    private data class Prog(val reps: Int, val repMin: Int, val pct: Double, val restMin: Int)

    private val sweetSpot = listOf(
        Prog(3, 8, 0.88, 4), Prog(3, 10, 0.88, 4), Prog(3, 12, 0.89, 4), Prog(2, 15, 0.90, 5),
        Prog(2, 18, 0.90, 5), Prog(2, 20, 0.91, 5), Prog(3, 15, 0.92, 5), Prog(2, 25, 0.92, 5), Prog(3, 20, 0.93, 5),
    )
    private val threshold = listOf(
        Prog(3, 6, 0.95, 4), Prog(3, 8, 0.97, 4), Prog(4, 8, 0.98, 4), Prog(3, 10, 1.00, 5),
        Prog(4, 10, 1.00, 5), Prog(3, 12, 1.00, 5), Prog(2, 15, 1.02, 6), Prog(3, 15, 1.02, 6), Prog(2, 20, 1.00, 8),
    )
    private val vo2max = listOf(
        Prog(4, 3, 1.10, 3), Prog(5, 3, 1.10, 3), Prog(6, 3, 1.10, 3), Prog(4, 4, 1.12, 4),
        Prog(5, 4, 1.12, 4), Prog(6, 4, 1.12, 4), Prog(5, 4, 1.15, 4), Prog(6, 4, 1.15, 4), Prog(5, 5, 1.12, 5),
    )
    private val tempo = listOf(
        Prog(2, 12, 0.80, 5), Prog(2, 15, 0.80, 5), Prog(2, 20, 0.82, 5), Prog(3, 15, 0.83, 5),
        Prog(2, 25, 0.84, 5), Prog(3, 20, 0.85, 5), Prog(2, 30, 0.85, 5), Prog(3, 25, 0.85, 5),
    )

    fun create(type: SessionType, level: Int, minutes: Int, id: String, context: String): Workout = when (type) {
        SessionType.Endurance -> endurance(id, minutes, level, "Endurance", context)
        SessionType.LongRide -> endurance(id, minutes, level, "Long ride", context)
        SessionType.Recovery -> steady(id, "Recovery spin $minutes min", minutes, 0.50, "$context Very easy spin to recover.")
        SessionType.Tempo -> intervals(id, "Tempo", tempo, level, minutes, context)
        SessionType.SweetSpot -> intervals(id, "Sweet spot", sweetSpot, level, minutes, context)
        SessionType.Threshold -> intervals(id, "Threshold", threshold, level, minutes, context)
        SessionType.Vo2Max -> intervals(id, "VO2max", vo2max, level, minutes, context)
        SessionType.FtpTest -> BuiltInWorkouts.rampTest.copy(
            id = id,
            description = "$context ${BuiltInWorkouts.rampTest.description}",
        )
    }

    private fun endurance(id: String, minutes: Int, level: Int, title: String, context: String): Workout {
        val pct = 0.65 + 0.01 * min(level, 5)
        val warm = 5 * 60
        val cool = 5 * 60
        return Workout(
            id = id,
            name = "$title $minutes min",
            description = "$context Steady zone 2 at ${pctText(pct)} FTP.",
            segments = listOf(
                Segment.Ramp(warm, 0.50, pct, "Warmup"),
                Segment.Steady(minutes * 60 - warm - cool, pct, "Endurance"),
                Segment.Ramp(cool, pct, 0.45, "Cooldown"),
            ),
        )
    }

    private fun steady(id: String, name: String, minutes: Int, pct: Double, description: String) = Workout(
        id = id,
        name = name,
        description = description,
        segments = listOf(Segment.Steady(minutes * 60, pct, "Easy")),
    )

    private fun intervals(id: String, title: String, table: List<Prog>, level: Int, minutes: Int, context: String): Workout {
        val warm = (if (minutes >= 45) 10 else 8) * 60
        val cool = 5 * 60
        val available = minutes * 60 - warm - cool
        val p = fit(table[level.coerceIn(0, table.lastIndex)], available)
        val main = p.reps * p.repMin * 60 + (p.reps - 1) * p.restMin * 60
        val filler = available - main
        val segments = buildList {
            add(Segment.Ramp(warm, 0.50, 0.75, "Warmup"))
            val on = Segment.Steady(p.repMin * 60, p.pct, title)
            if (p.reps == 1) {
                add(on)
            } else {
                add(Segment.Repeat(p.reps - 1, listOf(on, Segment.Steady(p.restMin * 60, 0.55, "Recovery"))))
                add(on)
            }
            if (filler > 0) add(Segment.Steady(filler, 0.65, "Endurance"))
            add(Segment.Ramp(cool, 0.60, 0.45, "Cooldown"))
        }
        return Workout(
            id = id,
            name = "$title ${p.reps}×${p.repMin}",
            description = "$context ${p.reps} × ${p.repMin} min at ${pctText(p.pct)} FTP" +
                (if (p.reps > 1) " with ${p.restMin} min easy between." else "."),
            segments = segments,
        )
    }

    /**
     * If the main set does not fit into [availableSec], picks the variant with fewer and/or
     * shorter reps that keeps the most work time (ties: more reps). Rest stays unchanged.
     */
    private fun fit(p: Prog, availableSec: Int): Prog {
        fun total(q: Prog) = (q.reps * q.repMin + (q.reps - 1) * q.restMin) * 60
        if (total(p) <= availableSec) return p
        val candidates = (p.reps downTo 1).flatMap { reps ->
            (p.repMin downTo 1).map { repMin -> p.copy(reps = reps, repMin = repMin) }
        }.filter { total(it) <= availableSec }
        return candidates.maxWithOrNull(compareBy<Prog>({ it.reps * it.repMin }, { it.reps }))
            ?: p.copy(reps = 1, repMin = max(1, availableSec / 60))
    }

    private fun pctText(f: Double) = "${(f * 100).roundToInt()} %"
}
