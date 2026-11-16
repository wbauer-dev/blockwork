package app.indoortrain.domain.io

import app.indoortrain.domain.plan.PlanGoal
import app.indoortrain.domain.plan.PlanWeek
import app.indoortrain.domain.plan.PlannedSession
import app.indoortrain.domain.plan.SessionType
import app.indoortrain.domain.plan.TrainingPlan
import app.indoortrain.domain.plan.WeekKind
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * Plan export/import format: weeks → sessions (weekday + embedded workout in the
 * [WorkoutJson] format). Dates are not stored; the importer chooses the start Monday.
 */
object PlanJson {
    const val FORMAT = "indoortrain-plan"
    const val VERSION = 1
    private const val MAX_WEEKS = 52

    private val json = Json {
        classDiscriminator = "type"
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(plan: TrainingPlan): String = json.encodeToString(
        PlanFile.serializer(),
        PlanFile(
            name = plan.name,
            goal = plan.goal.name,
            weeks = plan.weeks.map { w ->
                WeekDto(w.kind.name, w.sessions.map { SessionDto(it.day.name, it.type.name, WorkoutJson.toFile(it.workout)) })
            },
        ),
    )

    /** Parses and validates; session and workout ids are derived from [planId]. */
    fun decode(text: String, planId: String, startMonday: LocalDate): TrainingPlan {
        val file = try {
            json.decodeFromString(PlanFile.serializer(), text)
        } catch (e: SerializationException) {
            throw WorkoutImportException("Not a valid plan file: ${e.message?.lineSequence()?.firstOrNull()}", e)
        } catch (e: IllegalArgumentException) {
            throw WorkoutImportException("Not a valid plan file", e)
        }
        if (file.format != FORMAT) throw WorkoutImportException("Not a training plan file (format '${file.format}')")
        if (file.version > VERSION) throw WorkoutImportException("Plan was created by a newer app version")
        if (file.weeks.isEmpty() || file.weeks.size > MAX_WEEKS) throw WorkoutImportException("Plan must have 1–$MAX_WEEKS weeks")
        val weeks = file.weeks.mapIndexed { index, w ->
            val sessions = w.sessions.map { s ->
                val day = enumOrFail<DayOfWeek>(s.day, "weekday")
                val id = "$planId-w${index + 1}-${day.name.lowercase().take(3)}"
                PlannedSession(id, index, day, enumOrFail(s.type, "session type"), WorkoutJson.fromFile(s.workout, id))
            }
            if (sessions.map { it.day }.distinct().size != sessions.size) {
                throw WorkoutImportException("Week ${index + 1} has two sessions on the same day")
            }
            PlanWeek(index, enumOrFail(w.kind, "week kind"), sessions.sortedBy { it.day })
        }
        if (weeks.all { it.sessions.isEmpty() }) throw WorkoutImportException("Plan has no sessions")
        return TrainingPlan(
            id = planId,
            name = file.name.ifBlank { "Imported plan" },
            goal = runCatching { PlanGoal.valueOf(file.goal) }.getOrDefault(PlanGoal.GeneralFitness),
            startDate = TrainingPlan.mondayOf(startMonday),
            weeks = weeks,
        )
    }

    private inline fun <reified E : Enum<E>> enumOrFail(value: String, what: String): E =
        enumValues<E>().firstOrNull { it.name.equals(value, ignoreCase = true) }
            ?: throw WorkoutImportException("Unknown $what '$value'")

    @Serializable
    private data class PlanFile(
        val format: String = FORMAT,
        val version: Int = VERSION,
        val name: String,
        val goal: String,
        val weeks: List<WeekDto>,
    )

    @Serializable
    private data class WeekDto(val kind: String = WeekKind.Build.name, val sessions: List<SessionDto>)

    @Serializable
    private data class SessionDto(val day: String, val type: String = SessionType.Endurance.name, val workout: WorkoutJson.WorkoutFile)
}
