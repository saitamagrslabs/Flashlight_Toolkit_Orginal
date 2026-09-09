package com.saitamagrs.flashnow

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.google.android.gms.ads.MobileAds
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import android.content.Intent
import com.saitamagrs.flashnow.services.FlashlightTimerService
class MyApplication : Application() {

    companion object {
        const val TIMER_CHANNEL_ID = "flashlight_timer_channel"
    }

    override fun onCreate() {
        super.onCreate()

        // Initialize AdMob SDK
        MobileAds.initialize(this) {}

        createNotificationChannels()
        // OBSERVE APP FOREGROUND/BACKGROUND STATE
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                // App opened - Resume timer/flash
                val intent = Intent(this@MyApplication, FlashlightTimerService::class.java).apply {
                    action = FlashlightTimerService.ACTION_RESUME_TIMER
                }
                startService(intent)
            }

            override fun onStop(owner: LifecycleOwner) {
                // App minimized - Pause timer/flash
                val intent = Intent(this@MyApplication, FlashlightTimerService::class.java).apply {
                    action = FlashlightTimerService.ACTION_PAUSE_TIMER
                }
                startService(intent)
            }
        })



    }
    private fun sendCommandToService(action: String) {
        val intent = Intent(this, FlashlightTimerService::class.java).apply {
            this.action = action
        }
        startService(intent)
    }
    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Timer notification channel
            val timerChannel = NotificationChannel(
                TIMER_CHANNEL_ID,
                "Flashlight Timer",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications for the flashlight timer"
                setSound(null, null)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }

            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(timerChannel)
        }
    }
}