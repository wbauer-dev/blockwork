package app.indoortrain.data.rides

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSample(sample: RideSampleEntity)

    @Query("SELECT * FROM ride_samples WHERE rideId = :rideId ORDER BY elapsedSec")
    suspend fun samples(rideId: Long): List<RideSampleEntity>

    @Query("DELETE FROM rides WHERE id = :id")
    suspend fun deleteRide(id: Long)
}
