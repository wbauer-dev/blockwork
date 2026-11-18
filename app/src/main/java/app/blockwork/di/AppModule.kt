package app.blockwork.di

import android.content.Context
import app.blockwork.ble.BleHeartRateMonitor
import app.blockwork.ble.BleScanner
import app.blockwork.ble.BleSmartTrainer
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Qualifier
import javax.inject.Singleton

/** Process-wide scope for work that outlives screens (device connections, later the training session). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    @ApplicationScope
    fun applicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Provides
    @Singleton
    fun heartRateMonitor(@ApplicationContext context: Context) = BleHeartRateMonitor(context)

    @Provides
    @Singleton
    fun smartTrainer(@ApplicationContext context: Context) = BleSmartTrainer(context)

    @Provides
    @Singleton
    fun scanner(@ApplicationContext context: Context) = BleScanner(context)
}
