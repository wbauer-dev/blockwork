package app.indoortrain.data

import android.content.Context
import androidx.room.Room
import app.indoortrain.data.rides.AppDatabase
import app.indoortrain.data.rides.RideDao
import app.indoortrain.data.rides.RoomRideRecorder
import app.indoortrain.domain.training.RideRecorder
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
            Room.databaseBuilder(context, AppDatabase::class.java, "indoortrain.db").build()

        @Provides
        fun rideDao(db: AppDatabase): RideDao = db.rideDao()
    }
}
