package app.indoortrain

import android.app.Application
import app.indoortrain.training.TrainingService
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class IndoorTrainApp : Application() {
    override fun onCreate() {
        super.onCreate()
        TrainingService.createChannel(this)
    }
}
