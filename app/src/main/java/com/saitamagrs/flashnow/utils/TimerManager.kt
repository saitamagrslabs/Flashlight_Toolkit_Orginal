package com.saitamagrs.flashnow.utils

import android.content.Context
import android.content.Intent
import android.util.Log
import com.saitamagrs.flashnow.services.FlashlightTimerService

data class TimerPreset(
    val minutes: Int,
    val title: String,
    val description: String
)

class TimerManager(private val context: Context) {

    fun startTimer(durationMinutes: Int) {
        Log.d("TimerManager", "Starting FOREGROUND timer for $durationMinutes minutes")

        try {
            // Start foreground service - THIS IS CRUCIAL
            val serviceIntent = Intent(context, FlashlightTimerService::class.java).apply {
                action = FlashlightTimerService.ACTION_START_TIMER
                putExtra(FlashlightTimerService.EXTRA_DURATION_MINUTES, durationMinutes.toLong())
            }

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }

            Log.d("TimerManager", "Foreground service started - App will stay alive")

        } catch (e: Exception) {
            Log.e("TimerManager", "Error starting foreground timer", e)
            throw e
        }
    }

    fun stopTimer() {
        Log.d("TimerManager", "Stopping timer")

        try {
            val serviceIntent = Intent(context, FlashlightTimerService::class.java).apply {
                action = FlashlightTimerService.ACTION_STOP_TIMER
            }
            context.startService(serviceIntent)

            // Reset lock mode state in preferences to prevent stale lock mode
            resetLockModeState()

            Log.d("TimerManager", "Stop command sent to foreground service")

        } catch (e: Exception) {
            Log.e("TimerManager", "Error stopping timer", e)
        }
    }

    fun isTimerRunning(): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(PREF_KEY_TIMER_RUNNING, false)
    }

    private fun resetLockModeState() {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(PREF_KEY_LOCK_MODE_ENABLED, false).apply()
    }

    companion object {
        private const val PREFS_NAME = "timer_prefs"
        private const val PREF_KEY_TIMER_RUNNING = "timer_running"
        private const val PREF_KEY_LOCK_MODE_ENABLED = "lock_mode_enabled"

        val presetDurations = listOf(
            TimerPreset(1, "1 mins", "Quick test"),
            TimerPreset(5, "5 mins", "Quick use"),
            TimerPreset(10, "10 mins", "Short task"),
            TimerPreset(15, "15 mins", "Short task"),
            TimerPreset(30, "30 mins", "Medium task"),
            TimerPreset(60, "1 hour", "Long task"),
        )
    }
}