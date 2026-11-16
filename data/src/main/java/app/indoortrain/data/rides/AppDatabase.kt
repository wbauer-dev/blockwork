package app.indoortrain.data.rides

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase
import app.indoortrain.data.workouts.WorkoutDao
import app.indoortrain.data.workouts.WorkoutEntity

@Database(
    entities = [RideEntity::class, RideSampleEntity::class, WorkoutEntity::class],
    version = 3,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3)],
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun rideDao(): RideDao
    abstract fun workoutDao(): WorkoutDao
}
