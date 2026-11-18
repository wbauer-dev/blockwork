package app.blockwork.data

import android.content.Context
import androidx.room.Room
import app.blockwork.data.rides.AppDatabase
import app.blockwork.data.rides.RideDao
import app.blockwork.data.rides.RoomRideRecorder
import app.blockwork.data.plans.PlanDao
import app.blockwork.data.workouts.WorkoutDao
import app.blockwork.domain.training.RideRecorder
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class DataModule {

    @Binds
    abstract fun rideRecorder(impl: RoomRideRecorder): RideRecorder

    companion object {
        @Provides
        @Singleton
        fun database(@ApplicationContext context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "blockwork.db").build()

        @Provides
        fun rideDao(db: AppDatabase): RideDao = db.rideDao()

        @Provides
        fun workoutDao(db: AppDatabase): WorkoutDao = db.workoutDao()

        @Provides
        fun planDao(db: AppDatabase): PlanDao = db.planDao()
    }
}
