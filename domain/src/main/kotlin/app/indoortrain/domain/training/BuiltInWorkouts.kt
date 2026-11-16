package app.indoortrain.domain.training

import app.indoortrain.domain.Segment.Ramp
import app.indoortrain.domain.Segment.Repeat
import app.indoortrain.domain.Segment.Steady
import app.indoortrain.domain.Workout

/**
 * Workouts shipped with the app (read-only in the library).
 *
 * Interval sessions marked "based on" follow protocols from published studies, adapted to
 * fixed ERG targets relative to FTP. Only the training method is taken from the study; the
 * workouts themselves are our own.
 */
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

    val recovery30 = Workout(
        id = "builtin-recovery-30",
        name = "Recovery 30",
        description = "30 minutes very easy spinning at 50 % FTP. For the day after a hard session.",
        segments = listOf(Steady(1800, 0.50, "Easy")),
    )

    val endurance90 = Workout(
        id = "builtin-endurance-90",
        name = "Endurance 90",
        description = "90 minutes steady in zone 2.",
        segments = listOf(
            Ramp(300, 0.50, 0.65, "Warmup"),
            Steady(4800, 0.68, "Endurance"),
            Steady(300, 0.50, "Cooldown"),
        ),
    )

    val threshold2x20 = Workout(
        id = "builtin-2x20",
        name = "Threshold 2×20",
        description = "Two 20-minute blocks at 95 % FTP with 5 minutes recovery. A classic threshold session.",
        segments = listOf(
            Ramp(600, 0.50, 0.75, "Warmup"),
            Repeat(2, listOf(Steady(1200, 0.95, "Threshold"), Steady(300, 0.55, "Recovery"))),
            Steady(300, 0.50, "Cooldown"),
        ),
    )

    val overUnders = Workout(
        id = "builtin-over-unders",
        name = "Over-unders 3×12",
        description = "Three 12-minute blocks alternating 2 min at 95 % and 1 min at 105 % FTP, " +
            "5 minutes recovery between blocks. Trains holding power around threshold.",
        segments = listOf(
            Ramp(600, 0.50, 0.75, "Warmup"),
            overUnderBlock(),
            Steady(300, 0.55, "Recovery"),
            overUnderBlock(),
            Steady(300, 0.55, "Recovery"),
            overUnderBlock(),
            Ramp(300, 0.60, 0.45, "Cooldown"),
        ),
    )

    val fourByEight = Workout(
        id = "builtin-4x8",
        name = "4×8 long intervals",
        description = "Four 8-minute intervals at 100 % FTP with 2 minutes recovery. " +
            "Based on Seiler et al. (2013), where 4×8 min gave the largest gains of the interval formats compared.",
        segments = listOf(
            Ramp(600, 0.50, 0.75, "Warmup"),
            Steady(180, 0.55, "Easy"),
            Repeat(4, listOf(Steady(480, 1.00, "Interval"), Steady(120, 0.50, "Recovery"))),
            Ramp(300, 0.60, 0.45, "Cooldown"),
        ),
    )

    val thirtyThirty = Workout(
        id = "builtin-30-30",
        name = "30-30s 2×10",
        description = "Two sets of 10 × (30 s at 125 % / 30 s at 50 % FTP), 5 minutes between sets. " +
            "Based on Billat's 30-30 intervals for time near VO2max.",
        segments = listOf(
            Ramp(600, 0.50, 0.75, "Warmup"),
            thirtyThirtySet(),
            Steady(300, 0.55, "Recovery"),
            thirtyThirtySet(),
            Ramp(300, 0.60, 0.45, "Cooldown"),
        ),
    )

    val thirtyFifteen = Workout(
        id = "builtin-30-15",
        name = "30/15 short intervals 3×13",
        description = "Three sets of 13 × (30 s at 120 % / 15 s at 50 % FTP), 3 minutes between sets. " +
            "Based on Rønnestad et al. (2015), where short intervals beat 4×5 min in trained cyclists.",
        segments = listOf(
            Ramp(600, 0.50, 0.75, "Warmup"),
            Steady(180, 0.55, "Easy"),
            thirtyFifteenSet(),
            Steady(180, 0.50, "Set rest"),
            thirtyFifteenSet(),
            Steady(180, 0.50, "Set rest"),
            thirtyFifteenSet(),
            Ramp(300, 0.60, 0.45, "Cooldown"),
        ),
    )

    val twentyTen = Workout(
        id = "builtin-20-10",
        name = "20/10 intervals 2×8",
        description = "Two sets of 8 × (20 s at 150 % / 10 s at 40 % FTP), 5 minutes between sets. " +
            "After the Tabata et al. (1996) protocol, adapted to fixed ERG targets. Very hard – short but brutal.",
        segments = listOf(
            Ramp(600, 0.50, 0.75, "Warmup"),
            Steady(180, 0.55, "Easy"),
            twentyTenSet(),
            Steady(300, 0.50, "Recovery"),
            twentyTenSet(),
            Ramp(300, 0.60, 0.45, "Cooldown"),
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

    val all = listOf(
        ergCheck, rampTest,
        recovery30, endurance60, endurance90,
        sweetSpot3x10, threshold2x20, overUnders, fourByEight,
        fourByFour, thirtyThirty, thirtyFifteen, twentyTen,
    )

    fun byId(id: String): Workout? = all.firstOrNull { it.id == id }

    private fun overUnderBlock() =
        Repeat(4, listOf(Steady(120, 0.95, "Under"), Steady(60, 1.05, "Over")))

    private fun thirtyThirtySet() =
        Repeat(10, listOf(Steady(30, 1.25, "On"), Steady(30, 0.50, "Off")))

    private fun thirtyFifteenSet() =
        Repeat(13, listOf(Steady(30, 1.20, "On"), Steady(15, 0.50, "Off")))

    private fun twentyTenSet() =
        Repeat(8, listOf(Steady(20, 1.50, "On"), Steady(10, 0.40, "Off")))
}
