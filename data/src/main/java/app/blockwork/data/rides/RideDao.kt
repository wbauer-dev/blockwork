package app.blockwork.data.rides

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface RideDao {
    @Insert
    suspend fun insertRide(ride: RideEntity): Long

    @Update
    suspend fun updateRide(ride: RideEntity)

    @Query("SELECT * FROM rides WHERE id = :id")
    suspend fun ride(id: Long): RideEntity?

    @Query("SELECT * FROM rides ORDER BY startedAt DESC")
    fun observeRides(): Flow<List<RideEntity>>

    @Query("SELECT * FROM rides WHERE id = :id")
    fun observeRide(id: Long): Flow<RideEntity?>

    @Query("SELECT * FROM rides WHERE finished = 1 AND startedAt >= :fromMillis ORDER BY startedAt")
    suspend fun finishedRidesSince(fromMillis: Long): List<RideEntity>

    @Query("UPDATE rides SET intervalsState = :state, intervalsActivityId = :activityId, intervalsMessage = :message WHERE id = :id")
    suspend fun setIntervalsStatus(id: Long, state: String?, activityId: String?, message: String?)

    @Query("SELECT * FROM rides WHERE plannedSessionId IS NOT NULL AND finished = 1")
    fun observePlanRides(): Flow<List<RideEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSample(sample: RideSampleEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRrIntervals(intervals: List<RideRrIntervalEntity>)

    @Query("SELECT * FROM ride_samples WHERE rideId = :rideId ORDER BY elapsedSec")
    suspend fun samples(rideId: Long): List<RideSampleEntity>

    @Query("SELECT * FROM ride_rr_intervals WHERE rideId = :rideId ORDER BY idx")
    suspend fun rrIntervals(rideId: Long): List<RideRrIntervalEntity>

    @Query("DELETE FROM rides WHERE id = :id")
    suspend fun deleteRide(id: Long)

    @Query("DELETE FROM ride_samples")
    suspend fun deleteAllSamples()

    @Query("DELETE FROM ride_rr_intervals")
    suspend fun deleteAllRrIntervals()

    @Query("DELETE FROM rides")
    suspend fun deleteAllRides()

    /** Removes every ride and all recorded samples and RR intervals. */
    @Transaction
    suspend fun deleteAll() {
        deleteAllSamples()
        deleteAllRrIntervals()
        deleteAllRides()
    }
}
