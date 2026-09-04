package com.immineal.hdri360.device

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Feedback for the part of the sphere nobody can watch.
 *
 * A full sphere includes the zenith, and shooting the zenith means holding the
 * phone overhead with the screen facing the floor. There is no way to see the
 * guidance at that point, so if the only signal is on the screen the top of the
 * sphere is a matter of guesswork - which is exactly where a hole is least
 * recoverable, because it is the sky.
 *
 * So: one tick when the aim lands, one firmer one when the frames are safely
 * stored, and a triple when the sphere is finished. That is enough to shoot
 * blind by feel.
 */
class Haptics(context: Context) {

    private val vibrator: Vibrator? = try {
        if (Build.VERSION.SDK_INT >= 31)
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        else @Suppress("DEPRECATION") context.getSystemService(Vibrator::class.java)
    } catch (e: Exception) {
        null
    }

    private val available = vibrator?.hasVibrator() == true

    /** On target: light, because it happens often and means "hold". */
    fun onTarget() = play(longArrayOf(0, 12), intArrayOf(0, 90))

    /** Frames stored: firmer, because it means "you may move on". */
    fun captured() = play(longArrayOf(0, 45), intArrayOf(0, 200))

    /** A direction given up on, so a hole is never silent. */
    fun missed() = play(longArrayOf(0, 30, 90, 30), intArrayOf(0, 160, 0, 160))

    /** The sphere is complete. */
    fun finished() = play(longArrayOf(0, 60, 80, 60, 80, 140),
                          intArrayOf(0, 200, 0, 200, 0, 255))

    private fun play(timings: LongArray, amplitudes: IntArray) {
        if (!available) return
        try {
            val effect = if (Build.VERSION.SDK_INT >= 26)
                VibrationEffect.createWaveform(timings, amplitudes, -1)
            else null
            if (effect != null) vibrator?.vibrate(effect)
            else @Suppress("DEPRECATION") vibrator?.vibrate(timings, -1)
        } catch (e: Exception) {
            // Feedback that throws is worse than no feedback.
        }
    }
}
