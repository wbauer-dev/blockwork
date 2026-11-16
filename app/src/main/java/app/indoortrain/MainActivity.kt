package app.indoortrain

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.indoortrain.data.SettingsRepository
import app.indoortrain.data.ThemeMode
import androidx.compose.runtime.mutableStateOf
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.indoortrain.devices.BluetoothPermissions
import app.indoortrain.devices.DeviceManager
import app.indoortrain.training.TrainingController
import app.indoortrain.ui.devices.DevicesScreen
import app.indoortrain.ui.live.LiveScreen
import app.indoortrain.ui.plan.PlanScreen
import app.indoortrain.ui.plan.PlanSessionScreen
import app.indoortrain.ui.plan.PlanWizardScreen
import app.indoortrain.ui.settings.SettingsScreen
import app.indoortrain.ui.theme.IndoorTrainTheme
import app.indoortrain.ui.training.TrainingScreen
import app.indoortrain.ui.editor.WorkoutEditorScreen
import app.indoortrain.ui.history.HistoryScreen
import app.indoortrain.ui.importing.ExternalImport
import app.indoortrain.ui.importing.ExternalImportHolder
import app.indoortrain.ui.importing.ImportScreen
import androidx.core.content.IntentCompat
import app.indoortrain.ui.history.RideDetailScreen
import app.indoortrain.ui.workouts.WorkoutDetailScreen
import app.indoortrain.ui.workouts.WorkoutListScreen
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var deviceManager: DeviceManager
    @Inject lateinit var trainingController: TrainingController
    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var importHolder: ExternalImportHolder

    /** Set when a file was opened with / shared to the app. */
    private val openImport = mutableStateOf(false)

    /** Set when opened from the workout notification. */
    private val openTraining = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)
        setContent {
            val settings by settingsRepository.settings.collectAsStateWithLifecycle(initialValue = null)
            IndoorTrainTheme(mode = settings?.theme ?: ThemeMode.System) {
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
                LaunchedEffect(openImport.value) {
                    if (openImport.value) {
                        openImport.value = false
                        nav.navigate(Routes.IMPORT) { launchSingleTop = true }
                    }
                }
                NavHost(navController = nav, startDestination = Routes.LIVE) {
                    composable(Routes.IMPORT) {
                        ImportScreen(
                            onClose = { nav.popBackStack() },
                            onOpenWorkout = { id -> nav.navigate(Routes.workout(id)) { popUpTo(Routes.LIVE) } },
                            onOpenPlan = { nav.navigate(Routes.PLAN) { popUpTo(Routes.LIVE) } },
                        )
                    }
                    composable(Routes.LIVE) {
                        LiveScreen(
                            onOpenDevices = { nav.navigate(Routes.DEVICES) },
                            onOpenSettings = { nav.navigate(Routes.SETTINGS) },
                            onOpenWorkouts = { nav.navigate(Routes.WORKOUTS) },
                            onOpenTraining = { nav.navigate(Routes.TRAINING) { launchSingleTop = true } },
                            onOpenHistory = { nav.navigate(Routes.HISTORY) },
                            onOpenPlan = { nav.navigate(Routes.PLAN) },
                            onOpenPlanSession = { id -> nav.navigate(Routes.planSession(id)) },
                        )
                    }
                    composable(Routes.DEVICES) { DevicesScreen(onBack = { nav.popBackStack() }) }
                    composable(Routes.SETTINGS) { SettingsScreen(onBack = { nav.popBackStack() }) }
                    composable(Routes.WORKOUTS) {
                        WorkoutListScreen(
                            onBack = { nav.popBackStack() },
                            onOpen = { id -> nav.navigate(Routes.workout(id)) },
                            onNew = { nav.navigate(Routes.editor(null, copy = false)) },
                        )
                    }
                    composable(
                        Routes.WORKOUT,
                        arguments = listOf(navArgument("id") { type = NavType.StringType }),
                    ) {
                        WorkoutDetailScreen(
                            onBack = { nav.popBackStack() },
                            onStarted = { nav.navigate(Routes.TRAINING) { popUpTo(Routes.LIVE) } },
                            onEdit = { id, copy -> nav.navigate(Routes.editor(id, copy)) },
                        )
                    }
                    composable(
                        Routes.EDITOR,
                        arguments = listOf(
                            navArgument("id") { type = NavType.StringType; nullable = true; defaultValue = null },
                            navArgument("copy") { type = NavType.BoolType; defaultValue = false },
                        ),
                    ) {
                        WorkoutEditorScreen(
                            onBack = { nav.popBackStack() },
                            onSaved = { id ->
                                nav.navigate(Routes.workout(id)) { popUpTo(Routes.WORKOUTS) }
                            },
                        )
                    }
                    composable(Routes.PLAN) {
                        PlanScreen(
                            onBack = { nav.popBackStack() },
                            onNewPlan = { nav.navigate(Routes.PLAN_NEW) },
                            onOpenSession = { id -> nav.navigate(Routes.planSession(id)) },
                        )
                    }
                    composable(Routes.PLAN_NEW) {
                        PlanWizardScreen(
                            onBack = { nav.popBackStack() },
                            onCreated = { nav.navigate(Routes.PLAN) { popUpTo(Routes.LIVE) } },
                        )
                    }
                    composable(Routes.PLAN_SESSION, arguments = listOf(navArgument("id") { type = NavType.StringType })) {
                        PlanSessionScreen(
                            onBack = { nav.popBackStack() },
                            onStarted = { nav.navigate(Routes.TRAINING) { popUpTo(Routes.LIVE) } },
                        )
                    }
                    composable(Routes.HISTORY) {
                        HistoryScreen(onBack = { nav.popBackStack() }, onOpen = { id -> nav.navigate("ride/$id") })
                    }
                    composable(Routes.RIDE, arguments = listOf(navArgument("id") { type = NavType.LongType })) {
                        RideDetailScreen(onBack = { nav.popBackStack() })
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
        if (intent == null) return
        if (intent.getBooleanExtra(EXTRA_OPEN_TRAINING, false)) openTraining.value = true
        val request = when (intent.action) {
            Intent.ACTION_VIEW -> intent.data?.let { ExternalImport.FromUri(it) }
            Intent.ACTION_SEND ->
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let { ExternalImport.FromUri(it) }
                    ?: intent.getStringExtra(Intent.EXTRA_TEXT)?.let { ExternalImport.FromText(it) }
            else -> null
        }
        if (request != null) {
            intent.action = null // handle once (e.g. not again after rotation)
            importHolder.pending.value = request
            openImport.value = true
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
        const val WORKOUTS = "workouts"
        const val TRAINING = "training"
        const val IMPORT = "import"
        const val PLAN = "plan"
        const val PLAN_NEW = "plan/new"
        const val PLAN_SESSION = "plan/session/{id}"
        const val HISTORY = "history"
        const val RIDE = "ride/{id}"
        const val WORKOUT = "workout/{id}"
        const val EDITOR = "editor?id={id}&copy={copy}"

        fun workout(id: String) = "workout/${Uri.encode(id)}"

        fun planSession(id: String) = "plan/session/${Uri.encode(id)}"

        fun editor(id: String?, copy: Boolean) =
            if (id == null) "editor" else "editor?id=${Uri.encode(id)}&copy=$copy"
    }

    companion object {
        const val EXTRA_OPEN_TRAINING = "open_training"
    }
}
