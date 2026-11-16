package app.indoortrain

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import app.indoortrain.devices.BluetoothPermissions
import app.indoortrain.devices.DeviceManager
import app.indoortrain.ui.devices.DevicesScreen
import app.indoortrain.ui.live.LiveScreen
import app.indoortrain.ui.settings.SettingsScreen
import app.indoortrain.ui.theme.IndoorTrainTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var deviceManager: DeviceManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            IndoorTrainTheme {
                val nav = rememberNavController()
                NavHost(navController = nav, startDestination = Routes.LIVE) {
                    composable(Routes.LIVE) {
                        LiveScreen(
                            onOpenDevices = { nav.navigate(Routes.DEVICES) },
                            onOpenSettings = { nav.navigate(Routes.SETTINGS) },
                        )
                    }
                    composable(Routes.DEVICES) { DevicesScreen(onBack = { nav.popBackStack() }) }
                    composable(Routes.SETTINGS) { SettingsScreen(onBack = { nav.popBackStack() }) }
                }
            }
        }
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
    }
}
