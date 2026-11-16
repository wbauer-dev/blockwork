package app.indoortrain.data.rides

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase
import app.indoortrain.data.plans.PlanDao
import app.indoortrain.data.plans.PlanEntity
import app.indoortrain.data.workouts.WorkoutDao
import app.indoortrain.data.workouts.WorkoutEntity

@Database(
    entities = [RideEntity::class, RideSampleEntity::class, WorkoutEntity::class, PlanEntity::class],
    version = 4,
    exportSchema = true,
    autoMigrations = [
        AutoMigration(from = 1, to = 2),
        AutoMigration(from = 2, to = 3),
        AutoMigration(from = 3, to = 4),
    ],
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun rideDao(): RideDao
    abstract fun workoutDao(): WorkoutDao
    abstract fun planDao(): PlanDao
}
