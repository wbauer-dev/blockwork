package app.indoortrain

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import app.indoortrain.devices.BluetoothPermissions
import app.indoortrain.devices.DeviceManager
import app.indoortrain.training.TrainingController
import app.indoortrain.ui.devices.DevicesScreen
import app.indoortrain.ui.live.LiveScreen
import app.indoortrain.ui.settings.SettingsScreen
import app.indoortrain.ui.theme.IndoorTrainTheme
import app.indoortrain.ui.training.TrainingScreen
import app.indoortrain.ui.workouts.WorkoutListScreen
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var deviceManager: DeviceManager
    @Inject lateinit var trainingController: TrainingController

    /** Set when opened from the workout notification. */
    private val openTraining = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)
        setContent {
            IndoorTrainTheme {
                val nav = rememberNavController()
                LaunchedEffect(openTraining.value) {
                    if (openTraining.value) {
                        openTraining.value = false
                        if (trainingController.state.value != null &&
                            nav.currentDestination?.route != Routes.TRAINING
                        ) {
                            nav.navigate(Routes.TRAINING) { launchSingleTop = true }
                        }
                    }
                }
                NavHost(navController = nav, startDestination = Routes.LIVE) {
                    composable(Routes.LIVE) {
                        LiveScreen(
                            onOpenDevices = { nav.navigate(Routes.DEVICES) },
                            onOpenSettings = { nav.navigate(Routes.SETTINGS) },
                            onOpenWorkouts = { nav.navigate(Routes.WORKOUTS) },
                            onOpenTraining = { nav.navigate(Routes.TRAINING) { launchSingleTop = true } },
                        )
                    }
                    composable(Routes.DEVICES) { DevicesScreen(onBack = { nav.popBackStack() }) }
                    composable(Routes.SETTINGS) { SettingsScreen(onBack = { nav.popBackStack() }) }
                    composable(Routes.WORKOUTS) {
                        WorkoutListScreen(
                            onBack = { nav.popBackStack() },
                            onStarted = {
                                nav.navigate(Routes.TRAINING) { popUpTo(Routes.LIVE) }
                            },
                        )
                    }
                    composable(Routes.TRAINING) {
                        TrainingScreen(
                            onBack = { nav.popBackStack(Routes.LIVE, inclusive = false) },
                            onDone = { nav.popBackStack(Routes.LIVE, inclusive = false) },
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_OPEN_TRAINING, false) == true) openTraining.value = true
    }

    override fun onResume() {
        super.onResume()
        // Permissions may have been changed in system settings while the app was in the background.
        deviceManager.onPermissionsChanged(BluetoothPermissions.granted(this))
    }

    private object Routes {
        const val LIVE = "live"
        const val DEVICES = "devices"
        const val SETTINGS = "settings"
        const val WORKOUTS = "workouts"
        const val TRAINING = "training"
    }

    companion object {
        const val EXTRA_OPEN_TRAINING = "open_training"
    }
}
