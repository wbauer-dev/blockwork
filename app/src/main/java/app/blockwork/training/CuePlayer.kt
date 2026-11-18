package app.blockwork.training

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.VibrationEffect
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import android.util.Log
import app.blockwork.data.SettingsRepository
import app.blockwork.data.UserSettings
import app.blockwork.di.ApplicationScope
import app.blockwork.domain.training.Cue
import app.blockwork.ui.common.plural
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Plays cues according to the user's settings: beeps (media stream, so they mix with music
 * and headphones), short vibrations and optional spoken announcements.
 */
@Singleton
class CuePlayer @Inject constructor(
    @ApplicationContext private val context: Context,
    settings: SettingsRepository,
    @ApplicationScope scope: CoroutineScope,
) {
    private val settings: StateFlow<UserSettings> =
        settings.settings.stateIn(scope, SharingStarted.Eagerly, UserSettings())

    private val tones: ToneGenerator? by lazy {
        try {
            ToneGenerator(AudioManager.STREAM_MUSIC, 90)
        } catch (e: RuntimeException) {
            Log.w(TAG, "ToneGenerator unavailable", e)
            null
        }
    }

    private val vibrator = context.getSystemService(VibratorManager::class.java)?.defaultVibrator

    private var tts: TextToSpeech? = null
    private var ttsReady = false

    fun play(cue: Cue) {
        val s = settings.value
        when (cue) {
            is Cue.Countdown -> feedback(s, ToneGenerator.TONE_PROP_BEEP, 150, 60)
            is Cue.StepStart -> feedback(s, ToneGenerator.TONE_PROP_BEEP2, 450, 250)
            is Cue.Upcoming -> if (s.cueVoice) {
                speak("In ${cue.inSeconds} seconds: " + describe(cue.durationSec, cue.targetWatts, cue.freeRide, cue.label))
            }
            is Cue.Finished -> {
                feedback(s, ToneGenerator.TONE_PROP_ACK, 800, 500)
                if (s.cueVoice) speak(if (cue.completed) "Workout complete. Well done." else "Workout ended.")
            }
        }
    }

    /** Call when a workout starts so the speech engine is ready for the first announcement. */
    fun prepare() {
        if (settings.value.cueVoice && tts == null) {
            tts = TextToSpeech(context) { status ->
                ttsReady = status == TextToSpeech.SUCCESS
                if (ttsReady) tts?.language = Locale.US
            }
        }
    }

    fun release() {
        tts?.shutdown()
        tts = null
        ttsReady = false
    }

    private fun feedback(s: UserSettings, tone: Int, durationMs: Int, vibrateMs: Long) {
        if (s.cueSound) tones?.startTone(tone, durationMs)
        if (s.cueVibration) vibrator?.vibrate(VibrationEffect.createOneShot(vibrateMs, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    private fun speak(text: String) {
        prepare()
        if (ttsReady) tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "cue")
    }

    companion object {
        private const val TAG = "CuePlayer"

        /** "4 minutes at 280 watts", "30 seconds free ride". */
        fun describe(durationSec: Int, watts: Int?, freeRide: Boolean, label: String?): String {
            val duration = when {
                durationSec % 60 == 0 -> plural(durationSec / 60, "minute")
                durationSec < 60 -> plural(durationSec, "second")
                else -> plural(durationSec / 60, "minute") + " " + plural(durationSec % 60, "second")
            }
            return when {
                freeRide -> "$duration free ride"
                watts != null -> "$duration at $watts watts"
                else -> duration + (label?.let { ", $it" } ?: "")
            }
        }
    }
}
