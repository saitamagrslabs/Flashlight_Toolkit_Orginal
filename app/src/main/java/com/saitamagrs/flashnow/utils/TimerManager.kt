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

            Log.d("TimerManager", "Stop command sent to foreground service")

        } catch (e: Exception) {
            Log.e("TimerManager", "Error stopping timer", e)
        }
    }

    fun isTimerRunning(): Boolean {
        // You can check service state if needed
        return false
    }

    companion object {
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