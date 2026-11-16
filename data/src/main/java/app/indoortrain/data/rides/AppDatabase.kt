package app.indoortrain.data.rides

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [RideEntity::class, RideSampleEntity::class], version = 1, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun rideDao(): RideDao
}
