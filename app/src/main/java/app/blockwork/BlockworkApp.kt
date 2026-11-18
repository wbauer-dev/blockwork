package app.blockwork

import android.app.Application
import app.blockwork.training.TrainingService
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class BlockworkApp : Application() {
    override fun onCreate() {
        super.onCreate()
        TrainingService.createChannel(this)
    }
}
