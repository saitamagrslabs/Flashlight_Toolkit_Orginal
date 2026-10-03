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

        // Suppress notification alert if Timer, SOS, or Strobe pattern is active
        if (isAnyActivePatternRunning()) {
            Log.d(TAG, "Active flashlight mode running (Timer/SOS/Strobe); suppressing notification alert flash")
            return
        }

        triggerFlashPattern()
    }

    private fun isAnyActivePatternRunning(): Boolean {
        val timerManager = TimerManager(applicationContext)
        return timerManager.isTimerRunning() || FlashlightManager.isPatternActive()
    }

    private fun triggerFlashPattern() {
        isAlertActive = true
        handler.post {
            flashSequence()
        }
    }

    private fun flashSequence() {
        val appContext = applicationContext
        val alertStartTime = System.currentTimeMillis()
        val wasFlashlightOn = FlashlightManager.isFlashlightOn(appContext)

        FlashlightManager.setNotificationFlashing(true)

        val delays = longArrayOf(0, 150, 300, 450, 600, 750)

        for (i in delays.indices) {
            handler.postDelayed({
                if (!isAlertActive) return@postDelayed

                // Check if an external action occurred (user toggled flashlight manually or via OS)
                if (FlashlightManager.lastExternalActionTime > alertStartTime) {
                    Log.d(TAG, "External flashlight action detected during notification alert; aborting alert sequence")
                    isAlertActive = false
                    FlashlightManager.setNotificationFlashing(false)
                    return@postDelayed
                }

                // Check if Timer, SOS, or Strobe mode became active during the alert
                if (isAnyActivePatternRunning()) {
                    Log.d(TAG, "Active mode started during alert; aborting alert sequence")
                    isAlertActive = false
                    FlashlightManager.setNotificationFlashing(false)
                    return@postDelayed
                }

                val targetState = (i % 2 == 0)
                val controller = FlashlightManager.getInstance(appContext)
                if (targetState) {
                    controller.turnOn()
                } else {
                    controller.turnOff()
                }

                // On final step, restore previous state if no external user actions occurred
                if (i == delays.size - 1) {
                    FlashlightManager.setNotificationFlashing(false)
                    if (FlashlightManager.lastExternalActionTime <= alertStartTime && !isAnyActivePatternRunning()) {
                        if (wasFlashlightOn) {
                            FlashlightManager.turnOnFlashlight(appContext)
                        } else {
                            FlashlightManager.turnOffFlashlight(appContext)
                        }
                    }
                    isAlertActive = false
                }
            }, delays[i])
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isAlertActive = false
        FlashlightManager.setNotificationFlashing(false)
        handler.removeCallbacksAndMessages(null)
        Log.d(TAG, "Notification Alert Service destroyed")
    }
}