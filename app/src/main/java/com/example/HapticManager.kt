package com.example

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

class HapticManager(private val context: Context) {
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
        vibratorManager?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    /**
     * Trigger a short, light tap haptic feedback (e.g., Send button click).
     */
    fun triggerLightTap() {
        try {
            vibrator?.let {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    it.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
                } else {
                    it.vibrate(VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE))
                }
            }
        } catch (e: Throwable) {
            // Defensively catch and ignore to avoid crashes on unsupported/virtual hardware
        }
    }

    /**
     * Trigger a success double-pulse haptic feedback (e.g., task execution success).
     */
    fun triggerSuccess() {
        try {
            vibrator?.let {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    it.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_DOUBLE_CLICK))
                } else {
                    val timings = longArrayOf(0, 50, 100, 50)
                    val amplitudes = intArrayOf(0, VibrationEffect.DEFAULT_AMPLITUDE, 0, VibrationEffect.DEFAULT_AMPLITUDE)
                    it.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
                }
            }
        } catch (e: Throwable) {
            // Defensively catch and ignore to avoid crashes on unsupported/virtual hardware
        }
    }

    /**
     * Trigger a heavy, distinctive buzz haptic feedback (e.g., error state).
     */
    fun triggerError() {
        try {
            vibrator?.let {
                it.vibrate(VibrationEffect.createOneShot(300, 220)) // 300ms at higher amplitude
            }
        } catch (e: Throwable) {
            // Defensively catch and ignore to avoid crashes on unsupported/virtual hardware
        }
    }
}
