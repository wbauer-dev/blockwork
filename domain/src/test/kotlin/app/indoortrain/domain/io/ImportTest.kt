package app.indoortrain.domain.io

import app.indoortrain.domain.Segment
import app.indoortrain.domain.training.BuiltInWorkouts
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

private fun resource(name: String) =
    requireNotNull(ImportTest::class.java.getResource("/workouts/$name")) { name }.readText()

class ImportTest {

    @Test fun zwo() {
        val r = WorkoutImporter.import("over_unders.zwo", resource("sample.zwo"), "id1", userFtp = 200)
        val w = r.workout
        assertThat(w.id).isEqualTo("id1")
        assertThat(w.name).isEqualTo("Over-Unders")
        assertThat(w.description).isEqualTo("Synthetic test workout")
        assertThat(w.segments).containsExactly(
            Segment.Ramp(600, 0.40, 0.75, "Warmup"),
            Segment.Steady(300, 0.88),
            Segment.Repeat(3, listOf(Segment.Steady(120, 1.05, "On"), Segment.Steady(60, 0.95, "Off"))),
            Segment.FreeRide(180, "Free ride"),
            Segment.Ramp(300, 0.65, 0.30, "Cooldown"), // reordered to go down
        ).inOrder()
        assertThat(w.durationSec).isEqualTo(600 + 300 + 540 + 180 + 300)
        assertThat(r.warnings).containsExactly("Ignored unsupported element <SomethingNew>")
        assertThat(w.flatten().single { it.freeRide }.durationSec).isEqualTo(180)
    }

    @Test fun zwoRejectsRunAndBrokenFiles() {
        val run = resource("sample.zwo").replace("<sportType>bike", "<sportType>run")
        assertThat(assertThrows(WorkoutImportException::class.java) {
            WorkoutImporter.import("x.zwo", run, "i", 200)
        }).hasMessageThat().contains("bike")
        assertThrows(WorkoutImportException::class.java) { WorkoutImporter.import("x.zwo", "<workout_file>", "i", 200) }
        assertThrows(WorkoutImportException::class.java) {
            WorkoutImporter.import("x.zwo", "<workout_file><workout><SteadyState Power=\"1\"/></workout></workout_file>", "i", 200)
        }
    }

    @Test fun zwoRejectsDoctype() {
        val xxe = """<?xml version="1.0"?><!DOCTYPE x [<!ENTITY e SYSTEM "file:///etc/passwd">]>
            <workout_file><name>&e;</name><workout><SteadyState Duration="60" Power="0.5"/></workout></workout_file>"""
        assertThrows(WorkoutImportException::class.java) { WorkoutImporter.import("x.zwo", xxe, "i", 200) }
    }

    @Test fun ergWithFileFtp() {
        val r = WorkoutImporter.import("sample.erg", resource("sample.erg"), "e", userFtp = 300)
        assertThat(r.warnings).isEmpty()
        assertThat(r.workout.name).isEqualTo("Synthetic ERG test")
        assertThat(r.workout.segments).containsExactly(
            Segment.Steady(300, 0.5),
            Segment.Steady(300, 1.0),
            Segment.Steady(120, 0.5),
            Segment.Ramp(300, 0.4, 0.8),
        ).inOrder()
    }

    @Test fun ergWithoutFtpUsesUserFtp() {
        val text = resource("sample.erg").lines().filterNot { it.startsWith("FTP") }.joinToString("\n")
        val r = WorkoutImporter.import("sample.erg", text, "e", userFtp = 125)
        assertThat(r.warnings.single()).contains("125 W")
        assertThat((r.workout.segments[1] as Segment.Steady).targetFtp).isWithin(1e-9).of(2.0)
    }

    @Test fun mrcMergesEqualSteadies() {
        val w = WorkoutImporter.import("sample.mrc", resource("sample.mrc"), "m", 200).workout
        assertThat(w.segments).containsExactly(
            Segment.Steady(600, 0.5),
            Segment.Steady(1200, 0.9),
            Segment.Steady(300, 0.5),
        ).inOrder()
    }

    @Test fun ergErrors() {
        assertThrows(WorkoutImportException::class.java) {
            WorkoutImporter.import("x.erg", "[COURSE HEADER]\nMINUTES WATTS\n[END COURSE HEADER]\n[COURSE DATA]\n0 100\n[END COURSE DATA]", "i", 200)
        }
        assertThrows(WorkoutImportException::class.java) {
            WorkoutImporter.import("x.erg", "[COURSE HEADER]\n[END COURSE HEADER]\n[COURSE DATA]\n0 100\n5 100\n[END COURSE DATA]", "i", 200)
        }
    }

    @Test fun jsonRoundTripForAllBuiltIns() {
        BuiltInWorkouts.all.forEach { w ->
            val decoded = WorkoutJson.decode(WorkoutJson.encode(w), w.id)
            assertThat(decoded.segments).isEqualTo(w.segments)
            assertThat(decoded.name).isEqualTo(w.name)
        }
        val json = WorkoutJson.encode(BuiltInWorkouts.ergCheck)
        assertThat(json).contains("\"format\":\"indoortrain-workout\"")
        assertThat(WorkoutImporter.import("x.json", json, "j", 200).workout.id).isEqualTo("j")
    }

    @Test fun validation() {
        val bad = """{"format":"indoortrain-workout","version":1,"name":"x","segments":[{"type":"steady","duration":60,"power":5.0}]}"""
        assertThat(assertThrows(WorkoutImportException::class.java) { WorkoutJson.decode(bad, "i") })
            .hasMessageThat().contains("out of range")
        assertThrows(WorkoutImportException::class.java) { WorkoutJson.decode("{not json", "i") }
        assertThrows(WorkoutImportException::class.java) { WorkoutImporter.import("x.txt", "hello", "i", 200) }
        assertThrows(WorkoutImportException::class.java) { WorkoutImporter.import("x.zwo", "  ", "i", 200) }
    }
}
