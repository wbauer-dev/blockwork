package app.blockwork.domain.io

import app.blockwork.domain.Segment
import app.blockwork.domain.Workout
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * ERG (.erg, absolute watts) and MRC (.mrc, percent of FTP) course files: a list of
 * (minutes, value) points joined by straight lines.
 */
object ErgParser {

    private enum class Unit { Watts, Percent }

    /** @param userFtp used to convert watts to % FTP when the file has no FTP header. */
    fun parse(text: String, id: String, fallbackName: String, userFtp: Int, isMrc: Boolean): ImportResult {
        val warnings = mutableListOf<String>()
        val header = mutableMapOf<String, String>()
        val points = mutableListOf<Pair<Double, Double>>()
        var unit: Unit? = if (isMrc) Unit.Percent else null
        var section = ""

        for (raw in text.lineSequence()) {
            val line = raw.substringBefore(';').trim()
            if (line.isEmpty()) continue
            if (line.startsWith("[") && line.endsWith("]")) {
                section = line.uppercase()
                continue
            }
            when (section) {
                "[COURSE HEADER]" -> {
                    if ('=' in line) {
                        header[line.substringBefore('=').trim().uppercase()] = line.substringAfter('=').trim()
                    } else {
                        val cols = line.uppercase().split(Regex("\\s+"))
                        if (cols.size >= 2 && cols[0] == "MINUTES") {
                            unit = when (cols[1]) {
                                "WATTS" -> Unit.Watts
                                "PERCENT" -> Unit.Percent
                                else -> throw WorkoutImportException("Unsupported unit '${cols[1]}'")
                            }
                        }
                    }
                }
                "[COURSE DATA]" -> {
                    val cols = line.split(Regex("\\s+"))
                    val t = cols.getOrNull(0)?.toDoubleOrNull()
                    val v = cols.getOrNull(1)?.toDoubleOrNull()
                    if (t == null || v == null) throw WorkoutImportException("Invalid data line: '$line'")
                    points += t to v
                }
            }
        }
        if (points.size < 2) throw WorkoutImportException("File contains no course data")
        val u = unit ?: throw WorkoutImportException("File does not say whether values are WATTS or PERCENT")

        val divisor = when (u) {
            Unit.Percent -> 100.0
            Unit.Watts -> {
                val fileFtp = header["FTP"]?.toDoubleOrNull()?.takeIf { it > 0 }
                if (fileFtp == null) warnings += "No FTP in file – watts converted with your FTP of $userFtp W"
                fileFtp ?: userFtp.toDouble()
            }
        }

        val segments = mutableListOf<Segment>()
        for ((a, b) in points.zipWithNext()) {
            if (b.first < a.first) throw WorkoutImportException("Times in the file are not in order")
            val duration = ((b.first - a.first) * 60).roundToInt()
            if (duration <= 0) continue
            val from = a.second / divisor
            val to = b.second / divisor
            val last = segments.lastOrNull()
            segments += when {
                abs(from - to) < 1e-6 && last is Segment.Steady && abs(last.targetFtp - from) < 1e-6 -> {
                    segments.removeAt(segments.lastIndex)
                    last.copy(durationSec = last.durationSec + duration)
                }
                abs(from - to) < 1e-6 -> Segment.Steady(duration, from)
                else -> Segment.Ramp(duration, from, to)
            }
        }

        val name = header["DESCRIPTION"]?.takeIf { it.isNotBlank() }
            ?: header["FILE NAME"]?.substringBeforeLast('.')?.takeIf { it.isNotBlank() }
            ?: fallbackName
        val workout = Workout(id, name, header["DESCRIPTION"].orEmpty(), segments)
        WorkoutValidator.validate(workout)
        return ImportResult(workout, warnings)
    }
}
