package app.indoortrain.training

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.VibrationEffect
import android.os.VibratorManager
import android.util.Log
import app.indoortrain.domain.training.Cue
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Beeps (media stream, so they mix with music/headphones) and short vibrations. */
@Singleton
class CuePlayer @Inject constructor(@ApplicationContext context: Context) {

    private val tones: ToneGenerator? by lazy {
        try {
            ToneGenerator(AudioManager.STREAM_MUSIC, 90)
        } catch (e: RuntimeException) {
            Log.w("CuePlayer", "ToneGenerator unavailable", e)
            null
        }
    }

    private val vibrator = context.getSystemService(VibratorManager::class.java)?.defaultVibrator

    fun play(cue: Cue) {
        when (cue) {
            Cue.Countdown -> beep(ToneGenerator.TONE_PROP_BEEP, 150, vibrateMs = 60)
            Cue.StepStart -> beep(ToneGenerator.TONE_PROP_BEEP2, 450, vibrateMs = 250)
            Cue.Finished -> beep(ToneGenerator.TONE_PROP_ACK, 800, vibrateMs = 500)
        }
    }

    private fun beep(tone: Int, durationMs: Int, vibrateMs: Long) {
        tones?.startTone(tone, durationMs)
        vibrator?.vibrate(VibrationEffect.createOneShot(vibrateMs, VibrationEffect.DEFAULT_AMPLITUDE))
    }
}
