package si.skladisce.app.util

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

class FeedbackManager(private val context: Context) {

    fun playSuccessBeep() {
        runCatching {
            val tg = ToneGenerator(AudioManager.STREAM_MUSIC, 95)
            tg.startTone(ToneGenerator.TONE_PROP_BEEP, 85)
        }
    }

    fun playShelfChime() {
        runCatching {
            val tg = ToneGenerator(AudioManager.STREAM_MUSIC, 95)
            tg.startTone(ToneGenerator.TONE_PROP_ACK, 120)
        }
    }

    fun vibrateSuccess() {
        runCatching {
            val vibrator = getVibrator() ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 60, 40, 60), -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(longArrayOf(0, 60, 40, 60), -1)
            }
        }
    }

    fun vibrateShort() {
        runCatching {
            val vibrator = getVibrator() ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(45, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(45)
            }
        }
    }

    private fun getVibrator(): Vibrator? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }
}
