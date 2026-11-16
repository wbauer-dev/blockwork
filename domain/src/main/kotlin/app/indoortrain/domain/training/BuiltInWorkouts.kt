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

    /**
     * Ramp test: +6 % FTP every minute until you can't hold the target, then press End.
     * New FTP = 75 % of the best 1-minute power.
     */
    val rampTest = Workout(
        id = "builtin-ramp-test",
        name = "FTP ramp test",
        description = "Ride until you can no longer hold the target, then press End. " +
            "Your new FTP is 75 % of your best 1-minute power. Spin easy afterwards.",
        segments = listOf(Steady(300, 0.45, "Warmup")) +
            (0 until 25).map { i -> Steady(60, 0.50 + 0.06 * i, "Ramp ${i + 1}") },
        isFtpTest = true,
    )

    val all = listOf(ergCheck, rampTest, fourByFour, sweetSpot3x10, endurance60)

    fun byId(id: String): Workout? = all.firstOrNull { it.id == id }
}
