package app.blockwork.domain.io

import app.blockwork.domain.plan.PlanGoal
import app.blockwork.domain.plan.PlanWeek
import app.blockwork.domain.plan.PlannedSession
import app.blockwork.domain.plan.SessionType
import app.blockwork.domain.plan.TrainingPlan
import app.blockwork.domain.plan.WeekKind
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * Plan export/import format: weeks → sessions (weekday + embedded workout in the
 * [WorkoutJson] format). Dates are not stored; the importer chooses the start Monday.
 *
 * Session ids are normally derived from plan id, week and weekday. The app's own storage
 * keeps them in the file instead (`includeIds` / `keepIds`), because recorded rides link to
 * a session by id and a rescheduled session must keep its id. Exported files never carry
 * ids, so an imported plan cannot pick up ride links from another plan.
 */
object PlanJson {
    const val FORMAT = "blockwork-plan"

    /** Written by the app before it was renamed from IndoorTrain; still accepted on import. */
    const val LEGACY_FORMAT = "indoortrain-plan"

    /** Cheap content sniffing: true if [text] looks like a plan file (current or legacy format). */
    fun looksLikePlan(text: String): Boolean = text.contains("\"$FORMAT\"") || text.contains("\"$LEGACY_FORMAT\"")
    const val VERSION = 1
    private const val MAX_WEEKS = TrainingPlan.MAX_WEEKS

    private val json = Json {
        classDiscriminator = "type"
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** [includeIds] is for the app's own storage only; exports must leave it off. */
    fun encode(plan: TrainingPlan, includeIds: Boolean = false): String = json.encodeToString(
        PlanFile.serializer(),
        PlanFile(
            name = plan.name,
            goal = plan.goal.name,
            weeks = plan.weeks.map { w ->
                WeekDto(w.kind.name, w.sessions.map {
                    SessionDto(it.day.name, it.type.name, WorkoutJson.toFile(it.workout), it.id.takeIf { includeIds })
                })
            },
        ),
    )

    /**
     * Parses and validates. Session and workout ids are derived from [planId]; with [keepIds]
     * (stored plans only) ids written by `encode(includeIds = true)` are used instead.
     */
    fun decode(text: String, planId: String, startMonday: LocalDate, keepIds: Boolean = false): TrainingPlan {
        val file = try {
            json.decodeFromString(PlanFile.serializer(), text)
        } catch (e: SerializationException) {
            throw WorkoutImportException("Not a valid plan file: ${e.message?.lineSequence()?.firstOrNull()}", e)
        } catch (e: IllegalArgumentException) {
            throw WorkoutImportException("Not a valid plan file", e)
        }
        if (file.format != FORMAT && file.format != LEGACY_FORMAT) throw WorkoutImportException("Not a training plan file (format '${file.format}')")
        if (file.version > VERSION) throw WorkoutImportException("Plan was created by a newer app version")
        if (file.weeks.isEmpty() || file.weeks.size > MAX_WEEKS) throw WorkoutImportException("Plan must have 1–$MAX_WEEKS weeks")
        val weeks = file.weeks.mapIndexed { index, w ->
            val sessions = w.sessions.map { s ->
                val day = enumOrFail<DayOfWeek>(s.day, "weekday")
                val id = s.id?.takeIf { keepIds && it.isNotBlank() } ?: "$planId-w${index + 1}-${day.name.lowercase().take(3)}"
                val type = enumOrFail<SessionType>(s.type, "session type")
                // The workout format has no FTP test flag; the session type carries it.
                val workout = WorkoutJson.fromFile(s.workout, id).let { if (type == SessionType.FtpTest) it.copy(isFtpTest = true) else it }
                PlannedSession(id, index, day, type, workout)
            }
            if (sessions.map { it.day }.distinct().size != sessions.size) {
                throw WorkoutImportException("Week ${index + 1} has two sessions on the same day")
            }
            PlanWeek(index, enumOrFail(w.kind, "week kind"), sessions.sortedBy { it.day })
        }
        if (weeks.all { it.sessions.isEmpty() }) throw WorkoutImportException("Plan has no sessions")
        val ids = weeks.flatMap { w -> w.sessions.map { it.id } }
        if (ids.distinct().size != ids.size) throw WorkoutImportException("Plan has duplicate session ids")
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

    @OptIn(ExperimentalSerializationApi::class)
    @Serializable
    private data class SessionDto(
        val day: String,
        val type: String = SessionType.Endurance.name,
        val workout: WorkoutJson.WorkoutFile,
        /** Only present in stored plans (see class doc); omitted from exports. */
        @EncodeDefault(EncodeDefault.Mode.NEVER) val id: String? = null,
    )
}
