package org.narsil.android

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Buzzes made straight on the vibrator.  The view's own touch feedback is dropped whenever the system's "touch
 * feedback" setting is off, and so are short buzzes made without saying what they are for, so every buzz here goes
 * through the vibrator marked as an alarm, scaled by the two strengths in Options: one for taps and presses, one for the fight and big moments.
 * Phones without amplitude control (this one) can only vary the length, so the strengths scale that too.
 */
object Haptics {
    @Volatile var tapScale = 1f
    @Volatile var fightScale = 1f
    private var vibrator: Vibrator? = null
    /** Marked as an alarm: the system sorts short buzzes as "touch" and drops them when touch feedback is off, whatever the app asks. */
    private val loud = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()

    fun init(context: Context) {
        vibrator = if (Build.VERSION.SDK_INT >= 31) context.getSystemService(VibratorManager::class.java)?.defaultVibrator
                   else @Suppress("DEPRECATION") context.getSystemService(Vibrator::class.java)
    }

    /** One buzz: [ms] long at [amplitude] (1..255), both multiplied by [scale]. */
    private fun once(ms: Int, amplitude: Int, scale: Float) =
        pulse(longArrayOf(0, (ms * scale).toLong().coerceAtLeast(5)), intArrayOf(0, (amplitude * scale).toInt().coerceIn(1, 255)), scale = 1f)

    /** The keyboard-style tick under every finger-down. */
    fun tap() = once(20, 130, tapScale)

    /** A firmer click: a press that has been held, a run or rest starting. */
    fun press() = once(40, 220, tapScale)

    /** A pattern as written for a strength of 1: durations and amplitudes both grow with [scale]. */
    fun pulse(timings: LongArray, amplitudes: IntArray, scale: Float = fightScale) {
        val v = vibrator?.takeIf { it.hasVibrator() } ?: return
        val t = LongArray(timings.size) { if (it == 0) 0 else (timings[it] * scale).toLong().coerceAtLeast(if (amplitudes[it] > 0) 5 else 0) }
        val a = IntArray(amplitudes.size) { if (amplitudes[it] == 0) 0 else (amplitudes[it] * scale).toInt().coerceIn(1, 255) }
        if (Build.VERSION.SDK_INT < 26) @Suppress("DEPRECATION") v.vibrate(t, -1)
        else @Suppress("DEPRECATION") v.vibrate(if (v.hasAmplitudeControl()) VibrationEffect.createWaveform(t, a, -1) else VibrationEffect.createWaveform(t, -1), loud)
    }
}
