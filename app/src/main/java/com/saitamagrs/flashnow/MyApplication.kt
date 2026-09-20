package com.saitamagrs.flashnow

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.google.android.gms.ads.MobileAds

class MyApplication : Application() {

    companion object {
        const val TIMER_CHANNEL_ID = "flashlight_timer_channel"
    }

    override fun onCreate() {
        super.onCreate()

        // Initialize AdMob SDK
        MobileAds.initialize(this) {}

        createNotificationChannels()
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