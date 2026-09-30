package app.gyro.service

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/** Distinct vibration patterns, recognisable through a pocket or a jacket. */
class Haptics(context: Context) {

    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Vibrator::class.java)
    }

    /** Threshold alarm: two pulses. */
    fun alarm() = play(longArrayOf(0, 250, 120, 250))

    /** Tiltback imminent / wheel fault: three long pulses. */
    fun critical() = play(longArrayOf(0, 400, 100, 400, 100, 400))

    /** Short confirmation (e.g. reached a set speed). */
    fun tick() = play(longArrayOf(0, 60))

    private fun play(pattern: LongArray) {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        v.vibrate(VibrationEffect.createWaveform(pattern, -1))
    }
}
