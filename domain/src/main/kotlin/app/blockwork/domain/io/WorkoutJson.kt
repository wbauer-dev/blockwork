package app.blockwork.domain.io

import app.blockwork.domain.Segment
import app.blockwork.domain.Workout
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * The app's own workout format (also used for storage and export). Example:
 * ```
 * {"format":"blockwork-workout","version":1,"name":"4x4","segments":[
 *   {"type":"ramp","duration":600,"from":0.5,"to":0.75},
 *   {"type":"repeat","times":4,"segments":[{"type":"steady","duration":240,"power":1.15}]}]}
 * ```
 * Power values are FTP fractions (1.0 = 100 % FTP), durations in seconds.
 */
object WorkoutJson {
    const val FORMAT = "blockwork-workout"

    /** Written by the app before it was renamed from IndoorTrain; still accepted on import. */
    const val LEGACY_FORMAT = "indoortrain-workout"
    const val VERSION = 1

    private val json = Json {
        classDiscriminator = "type"
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(workout: Workout): String = json.encodeToString(WorkoutFile.serializer(), toFile(workout))

    internal fun toFile(workout: Workout) =
        WorkoutFile(name = workout.name, description = workout.description, segments = workout.segments.map(::toDto))

    /** Converts and validates an embedded workout (used by the plan format). */
    internal fun fromFile(file: WorkoutFile, id: String): Workout =
        Workout(id, file.name, file.description, file.segments.map(::fromDto)).also(WorkoutValidator::validate)

    /** Parses and validates; the returned workout gets [id]. */
    fun decode(text: String, id: String): Workout {
        val file = try {
            json.decodeFromString(WorkoutFile.serializer(), text)
        } catch (e: SerializationException) {
            throw WorkoutImportException("Not a valid workout file: ${e.message?.lineSequence()?.firstOrNull()}", e)
        } catch (e: IllegalArgumentException) {
            throw WorkoutImportException("Not a valid workout file", e)
        }
        if (file.format != FORMAT && file.format != LEGACY_FORMAT) throw WorkoutImportException("Unknown file format '${file.format}'")
        if (file.version > VERSION) throw WorkoutImportException("File was created by a newer app version")
        return Workout(id, file.name, file.description, file.segments.map(::fromDto)).also(WorkoutValidator::validate)
    }

    @Serializable
    internal data class WorkoutFile(
        val format: String = FORMAT,
        val version: Int = VERSION,
        val name: String,
        val description: String = "",
        val segments: List<SegmentDto>,
    )

    @Serializable
    internal sealed class SegmentDto {
        @Serializable
        @SerialName("steady")
        data class Steady(val duration: Int, val power: Double, val label: String? = null) : SegmentDto()

        @Serializable
        @SerialName("ramp")
        data class Ramp(val duration: Int, val from: Double, val to: Double, val label: String? = null) : SegmentDto()

        @Serializable
        @SerialName("free")
        data class Free(val duration: Int, val label: String? = null) : SegmentDto()

        @Serializable
        @SerialName("repeat")
        data class Repeat(val times: Int, val segments: List<SegmentDto>) : SegmentDto()
    }

    private fun toDto(s: Segment): SegmentDto = when (s) {
        is Segment.Steady -> SegmentDto.Steady(s.durationSec, s.targetFtp, s.label)
        is Segment.Ramp -> SegmentDto.Ramp(s.durationSec, s.fromFtp, s.toFtp, s.label)
        is Segment.FreeRide -> SegmentDto.Free(s.durationSec, s.label)
        is Segment.Repeat -> SegmentDto.Repeat(s.times, s.segments.map(::toDto))
    }

    private fun fromDto(d: SegmentDto): Segment = when (d) {
        is SegmentDto.Steady -> Segment.Steady(d.duration, d.power, d.label)
        is SegmentDto.Ramp -> Segment.Ramp(d.duration, d.from, d.to, d.label)
        is SegmentDto.Free -> Segment.FreeRide(d.duration, d.label)
        is SegmentDto.Repeat -> Segment.Repeat(d.times, d.segments.map(::fromDto))
    }
}
