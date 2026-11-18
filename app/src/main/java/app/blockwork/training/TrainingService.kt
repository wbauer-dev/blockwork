package app.blockwork.training

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import app.blockwork.MainActivity
import app.blockwork.R
import app.blockwork.domain.training.SessionPhase
import app.blockwork.domain.training.SessionState
import app.blockwork.ui.common.formatDuration
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Keeps the process (BLE connections + session timer) alive during a workout while the
 * screen is off or the app is in the background, and shows an ongoing notification.
 */
@AndroidEntryPoint
class TrainingService : LifecycleService() {

    @Inject lateinit var controller: TrainingController

    private var wakeLock: PowerManager.WakeLock? = null
    private var foreground = false
    private var lastNotificationKey: Any? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_PAUSE -> controller.pause()
            ACTION_RESUME -> controller.resume()
            ACTION_SKIP -> controller.skipStep()
        }
        if (!foreground) {
            goForeground()
            lifecycleScope.launch {
                controller.state.collect { state ->
                    if (state == null || state.phase == SessionPhase.Finished) {
                        stopSelf()
                    } else {
                        updateNotification(state)
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun goForeground() {
        foreground = true
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(controller.state.value),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } catch (e: RuntimeException) {
            // e.g. Bluetooth permission missing in simulation mode: the workout still runs while visible.
            Log.w(TAG, "startForeground failed", e)
        }
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Blockwork:training")
            .apply { acquire(MAX_WAKE_LOCK_MS) }
    }

    private fun updateNotification(state: SessionState) {
        // Refresh on step/phase change and every 5 s – enough for a glance, cheap on battery.
        val key = Triple(state.stepIndex, state.phase, state.stepRemainingSec / 5)
        if (key == lastNotificationKey) return
        lastNotificationKey = key
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(state))
    }

    private fun buildNotification(state: SessionState?): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_OPEN_TRAINING, true)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_workout)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(open)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (state == null) return builder.setContentTitle("Workout starting…").build()

        if (state.phase == SessionPhase.Ready) {
            return builder
                .setContentTitle(state.workoutName)
                .setContentText(if (state.waitingForPedaling) "Start pedaling to begin" else "Starting…")
                .addAction(0, "Start", actionIntent(ACTION_RESUME, 1))
                .build()
        }
        val paused = state.phase == SessionPhase.Paused
        val label = state.currentStep.label ?: "Step ${state.stepIndex + 1}"
        val title = when {
            state.autoPaused -> "Auto-paused – ${state.workoutName}"
            paused -> "Paused – ${state.workoutName}"
            else -> state.workoutName
        }
        builder
            .setContentTitle(title)
            .setContentText(
                "$label · ${state.targetWatts ?: "–"} W · ${formatDuration(state.stepRemainingSec)} left",
            )
            .setProgress(state.totalDurationSec, state.totalDurationSec - state.remainingSec, false)
            .addAction(
                0,
                if (paused) "Resume" else "Pause",
                actionIntent(if (paused) ACTION_RESUME else ACTION_PAUSE, 1),
            )
            .addAction(0, "Skip step", actionIntent(ACTION_SKIP, 2))
        return builder.build()
    }

    private fun actionIntent(action: String, requestCode: Int) = PendingIntent.getService(
        this, requestCode,
        Intent(this, TrainingService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    override fun onDestroy() {
        wakeLock?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "TrainingService"
        const val CHANNEL_ID = "training"
        private const val NOTIFICATION_ID = 1
        private const val MAX_WAKE_LOCK_MS = 5 * 60 * 60 * 1000L
        private const val ACTION_PAUSE = "app.blockwork.action.PAUSE"
        private const val ACTION_RESUME = "app.blockwork.action.RESUME"
        private const val ACTION_SKIP = "app.blockwork.action.SKIP"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, TrainingService::class.java))
        }

        fun createChannel(context: Context) {
            val channel = NotificationChannel(CHANNEL_ID, "Workout", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shows the running workout"
                setShowBadge(false)
            }
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }
}
