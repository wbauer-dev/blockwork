package app.indoortrain.domain.training

import app.indoortrain.domain.Segment.Ramp
import app.indoortrain.domain.Segment.Repeat
import app.indoortrain.domain.Segment.Steady
import app.indoortrain.domain.Workout

/** Workouts shipped with the app until the library/import (phase 3) exists. */
object BuiltInWorkouts {

    val ergCheck = Workout(
        id = "builtin-erg-check",
        name = "ERG check (10 min)",
        description = "Short test: easy spin with four 30 s efforts to check ERG response.",
        segments = listOf(
            Steady(120, 0.50, "Warmup"),
            Repeat(4, listOf(Steady(30, 1.00, "On"), Steady(30, 0.60, "Off"))),
            Steady(240, 0.50, "Cooldown"),
        ),
    )

    val fourByFour = Workout(
        id = "builtin-4x4",
        name = "4×4 VO2max",
        description = "Four 4-minute intervals at 115 % FTP with 4 minutes recovery.",
        segments = listOf(
            Ramp(600, 0.50, 0.75, "Warmup"),
            Steady(180, 0.55, "Easy"),
            Repeat(4, listOf(Steady(240, 1.15, "VO2max"), Steady(240, 0.50, "Recovery"))),
            Ramp(300, 0.60, 0.45, "Cooldown"),
        ),
    )

    val sweetSpot3x10 = Workout(
        id = "builtin-ss-3x10",
        name = "Sweet Spot 3×10",
        description = "Three 10-minute blocks at 90 % FTP with 5 minutes recovery.",
        segments = listOf(
            Ramp(600, 0.50, 0.75, "Warmup"),
            Repeat(3, listOf(Steady(600, 0.90, "Sweet spot"), Steady(300, 0.55, "Recovery"))),
            Steady(300, 0.50, "Cooldown"),
        ),
    )

    val endurance60 = Workout(
        id = "builtin-endurance-60",
        name = "Endurance 60",
        description = "One hour steady in zone 2.",
        segments = listOf(
            Ramp(300, 0.50, 0.65, "Warmup"),
            Steady(3000, 0.68, "Endurance"),
            Steady(300, 0.50, "Cooldown"),
        ),
    )

    val all = listOf(ergCheck, fourByFour, sweetSpot3x10, endurance60)

    fun byId(id: String): Workout? = all.firstOrNull { it.id == id }
}
