package com.saitamagrs.flashnow.services

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationCompat
import com.saitamagrs.flashnow.R
import com.saitamagrs.flashnow.utils.AppConstants
import com.saitamagrs.flashnow.utils.FlashlightController

class NotificationAlertService : NotificationListenerService() {

    companion object {
        private const val TAG = "NotificationAlertService"
        const val ACTION_NOTIFICATION_ACCESS_CHANGED = "notification_access_changed"
    }

    private lateinit var flashlightController: FlashlightController
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Notification Alert Service created")
        flashlightController = FlashlightController(applicationContext)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.d(TAG, "Notification listener connected")

        // Notify that permission is granted
        sendBroadcast(android.content.Intent(ACTION_NOTIFICATION_ACCESS_CHANGED))
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Log.d(TAG, "Notification listener disconnected")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null) return

        Log.d(TAG, "Notification received from: ${sbn.packageName}")

        // Check if service is enabled in user preferences
        val sharedPrefs = getSharedPreferences(AppConstants.PREFS_NAME, Context.MODE_PRIVATE)
        val isEnabled = sharedPrefs.getBoolean(AppConstants.KEY_NOTIFICATION_SWITCH, false)

        if (!isEnabled) {
            return
        }

        // Skip ongoing notifications
        if (sbn.isOngoing) {
            return
        }

        // Optional: Filter specific apps if needed
        // if (sbn.packageName == "com.whatsapp" || sbn.packageName == "com.facebook.messenger") {

        triggerFlashPattern()
    }

    private fun triggerFlashPattern() {
        // Simple flash pattern: 3 quick flashes
        handler.post {
            flashSequence()
        }
    }

    private fun flashSequence() {
        val delays = longArrayOf(0, 200, 400, 600, 800, 1000)

        for (i in delays.indices) {
            handler.postDelayed({
                if (i % 2 == 0) {
                    flashlightController.turnOn()
                } else {
                    flashlightController.turnOff()
                }
            }, delays[i])
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        Log.d(TAG, "Notification Alert Service destroyed")
    }
}