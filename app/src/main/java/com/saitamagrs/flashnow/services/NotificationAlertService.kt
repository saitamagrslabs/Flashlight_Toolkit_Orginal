package com.saitamagrs.flashnow.services

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.saitamagrs.flashnow.utils.AppConstants
import com.saitamagrs.flashnow.utils.FlashlightManager
import com.saitamagrs.flashnow.utils.TimerManager

class NotificationAlertService : NotificationListenerService() {

    companion object {
        private const val TAG = "NotificationAlertService"
        const val ACTION_NOTIFICATION_ACCESS_CHANGED = "notification_access_changed"
    }

    private val handler = Handler(Looper.getMainLooper())
    @Volatile
    private var isAlertActive = false

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Notification Alert Service created")
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

        // Prevent overlapping notification alerts
        if (isAlertActive) {
            Log.d(TAG, "Alert pattern currently active; ignoring overlapping notification from ${sbn.packageName}")
            return
        }

        // Do not fight active flashlight modes (e.g., Timer)
        val timerManager = TimerManager(applicationContext)
        if (timerManager.isTimerRunning()) {
            Log.d(TAG, "Flashlight Timer active; suppressing notification alert flash")
            return
        }

        triggerFlashPattern()
    }

    private fun triggerFlashPattern() {
        isAlertActive = true
        handler.post {
            flashSequence()
        }
    }

    private fun flashSequence() {
        val appContext = applicationContext
        val wasFlashlightOn = FlashlightManager.isFlashlightOn(appContext)

        val delays = longArrayOf(0, 150, 300, 450, 600, 750)

        for (i in delays.indices) {
            handler.postDelayed({
                if (!isAlertActive) return@postDelayed

                if (i % 2 == 0) {
                    FlashlightManager.turnOnFlashlight(appContext)
                } else {
                    FlashlightManager.turnOffFlashlight(appContext)
                }

                // Restore previous state on pattern completion
                if (i == delays.size - 1) {
                    if (wasFlashlightOn) {
                        FlashlightManager.turnOnFlashlight(appContext)
                    } else {
                        FlashlightManager.turnOffFlashlight(appContext)
                    }
                    isAlertActive = false
                }
            }, delays[i])
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isAlertActive = false
        handler.removeCallbacksAndMessages(null)
        Log.d(TAG, "Notification Alert Service destroyed")
    }
}