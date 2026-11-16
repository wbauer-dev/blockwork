package app.indoortrain.data.workouts

import android.util.Log
import app.indoortrain.domain.Workout
import app.indoortrain.domain.io.ImportResult
import app.indoortrain.domain.io.WorkoutImportException
import app.indoortrain.domain.io.WorkoutImporter
import app.indoortrain.domain.io.WorkoutJson
import app.indoortrain.domain.io.WorkoutValidator
import app.indoortrain.domain.training.BuiltInWorkouts
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

enum class WorkoutSource { BuiltIn, Imported, Custom }

data class LibraryWorkout(val workout: Workout, val source: WorkoutSource) {
    val editable: Boolean get() = source != WorkoutSource.BuiltIn
}

/** Built-in workouts (read-only) plus the user's imported and custom workouts. */
@Singleton
class WorkoutRepository @Inject constructor(private val dao: WorkoutDao) {

    private val builtIns = BuiltInWorkouts.all.map { LibraryWorkout(it, WorkoutSource.BuiltIn) }

    val workouts: Flow<List<LibraryWorkout>> = dao.observeAll().map { rows -> builtIns + rows.mapNotNull(::toLibrary) }

    suspend fun get(id: String): LibraryWorkout? =
        builtIns.firstOrNull { it.workout.id == id } ?: dao.get(id)?.let(::toLibrary)

    /** Validates and stores a user workout; throws [WorkoutImportException] if invalid. */
    suspend fun save(workout: Workout, source: WorkoutSource = WorkoutSource.Custom) {
        require(source != WorkoutSource.BuiltIn) { "Built-in workouts are read-only" }
        require(builtIns.none { it.workout.id == workout.id }) { "Built-in workouts are read-only" }
        WorkoutValidator.validate(workout)
        val now = System.currentTimeMillis()
        val existing = dao.get(workout.id)
        dao.upsert(
            WorkoutEntity(
                id = workout.id,
                name = workout.name,
                source = existing?.source ?: source.name,
                json = WorkoutJson.encode(workout),
                createdAt = existing?.createdAt ?: now,
                updatedAt = now,
            ),
        )
    }

    suspend fun delete(id: String) = dao.delete(id)

    /** Parses [text] (.zwo / .erg / .mrc / .json) and stores it as a new workout. */
    suspend fun import(fileName: String, text: String, userFtp: Int): ImportResult {
        val result = WorkoutImporter.import(fileName, text, newId(), userFtp)
        save(result.workout, WorkoutSource.Imported)
        return result
    }

    fun newId(): String = UUID.randomUUID().toString()

    private fun toLibrary(e: WorkoutEntity): LibraryWorkout? = try {
        LibraryWorkout(
            WorkoutJson.decode(e.json, e.id),
            runCatching { WorkoutSource.valueOf(e.source) }.getOrDefault(WorkoutSource.Custom),
        )
    } catch (ex: WorkoutImportException) {
        Log.w("WorkoutRepository", "Skipping unreadable workout ${e.id}", ex)
        null
    }
}
