package app.blockwork.data.plans

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/** The active training plan in the plan JSON format (at most one row). */
@Entity(tableName = "plans")
data class PlanEntity(
    @PrimaryKey val id: String,
    val startDate: String,
    val json: String,
    val createdAt: Long,
)

@Dao
interface PlanDao {
    @Query("SELECT * FROM plans ORDER BY createdAt DESC LIMIT 1")
    fun observeActive(): Flow<PlanEntity?>

    @Query("DELETE FROM plans")
    suspend fun deleteAll()

    @Insert
    suspend fun insert(plan: PlanEntity)

    /** Rewrites a plan in place (id and createdAt stay); returns the number of rows changed. */
    @Query("UPDATE plans SET startDate = :startDate, json = :json WHERE id = :id")
    suspend fun update(id: String, startDate: String, json: String): Int

    @Transaction
    suspend fun replace(plan: PlanEntity) {
        deleteAll()
        insert(plan)
    }
}
