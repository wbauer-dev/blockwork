package app.blockwork.data.plans

import android.util.Log
import app.blockwork.domain.io.PlanJson
import app.blockwork.domain.io.WorkoutImportException
import app.blockwork.domain.plan.PlanGenerator
import app.blockwork.domain.plan.PlanRequest
import app.blockwork.domain.plan.TrainingPlan
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.DayOfWeek
import java.time.LocalDate
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlanRepository @Inject constructor(private val dao: PlanDao) {

    val activePlan: Flow<TrainingPlan?> = dao.observeActive().map { e ->
        e?.let {
            try {
                PlanJson.decode(it.json, it.id, LocalDate.parse(it.startDate), keepIds = true)
            } catch (ex: WorkoutImportException) {
                Log.w("PlanRepository", "Stored plan unreadable", ex)
                null
            }
        }
    }

    /** Generates a plan from the wizard input and makes it the active plan. */
    suspend fun create(request: PlanRequest): TrainingPlan =
        PlanGenerator.generate(request, newId()).also { save(it) }

    /** Imports a plan file; it starts today if today is Monday, otherwise next Monday. */
    suspend fun import(text: String, today: LocalDate = LocalDate.now()): TrainingPlan {
        val start = if (today.dayOfWeek == DayOfWeek.MONDAY) today else TrainingPlan.mondayOf(today).plusWeeks(1)
        return PlanJson.decode(text, newId(), start).also { save(it) }
    }

    fun export(plan: TrainingPlan): String = PlanJson.encode(plan)

    suspend fun delete() = dao.deleteAll()

    private suspend fun save(plan: TrainingPlan) {
        dao.replace(PlanEntity(plan.id, plan.startDate.toString(), PlanJson.encode(plan, includeIds = true), System.currentTimeMillis()))
    }

    private fun newId() = "plan-" + UUID.randomUUID().toString().take(8)
}
