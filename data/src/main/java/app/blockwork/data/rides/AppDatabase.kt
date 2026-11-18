package app.blockwork.data.rides

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase
import app.blockwork.data.plans.PlanDao
import app.blockwork.data.plans.PlanEntity
import app.blockwork.data.workouts.WorkoutDao
import app.blockwork.data.workouts.WorkoutEntity

@Database(
    entities = [RideEntity::class, RideSampleEntity::class, RideRrIntervalEntity::class, WorkoutEntity::class, PlanEntity::class],
    version = 7,
    exportSchema = true,
    autoMigrations = [
        AutoMigration(from = 1, to = 2),
        AutoMigration(from = 2, to = 3),
        AutoMigration(from = 3, to = 4),
        AutoMigration(from = 4, to = 5),
        AutoMigration(from = 5, to = 6),
        AutoMigration(from = 6, to = 7),
    ],
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun rideDao(): RideDao
    abstract fun workoutDao(): WorkoutDao
    abstract fun planDao(): PlanDao
}
