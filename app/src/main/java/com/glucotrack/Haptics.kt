package com.glucotrack

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Short vibrations marking what the NFC reader is doing.
 *
 * Reading a sensor is silent and takes a second or two, with the phone held against an arm where
 * the screen usually cannot be seen. Without a physical cue there is no way to tell a successful
 * scan from a missed one — which is exactly how a sensor that had stopped responding went
 * unnoticed for a day.
 *
 * Each outcome gets a distinguishable pattern, so the difference is audible through the hand
 * rather than something to be checked afterwards.
 */
class Haptics(context: Context) {

    private val vibrator: Vibrator? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }.getOrNull()

    /** A tag has entered the field and reading has begun. */
    fun scanStarted() = vibrate(longArrayOf(0, 25))

    /** A reading was decoded and stored: two quick taps. */
    fun scanSucceeded() = vibrate(longArrayOf(0, 30, 80, 30))

    /** The scan failed: one longer buzz, clearly different from success. */
    fun scanFailed() = vibrate(longArrayOf(0, 200))

    private fun vibrate(pattern: LongArray) {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        runCatching {
            v.vibrate(VibrationEffect.createWaveform(pattern, -1))
        }
    }
}
