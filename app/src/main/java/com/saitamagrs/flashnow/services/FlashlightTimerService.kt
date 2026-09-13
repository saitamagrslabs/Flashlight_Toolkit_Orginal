package com.saitamagrs.flashnow.services

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContentProviderCompat.requireContext
import com.saitamagrs.flashnow.MainActivity
import com.saitamagrs.flashnow.R
import com.saitamagrs.flashnow.utils.FlashlightManager
import kotlinx.coroutines.*

class FlashlightTimerService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wakeLock: PowerManager.WakeLock? = null

    @Volatile
    private var isTimerRunning = false
    private var isPaused = false
    private var remainingMillis: Long = 0
    private var totalDurationMillis: Long = 0
    private var countDownTimer: CountDownTimer? = null

    companion object {
        private const val TAG = "FlashlightTimerService"
        const val ACTION_START_TIMER = "com.saitamagrs.flashnow.ACTION_START_TIMER"
        const val ACTION_STOP_TIMER = "com.saitamagrs.flashnow.ACTION_STOP_TIMER"
        const val ACTION_PAUSE_TIMER = "com.saitamagrs.flashnow.ACTION_PAUSE_TIMER"
        const val ACTION_RESUME_TIMER = "com.saitamagrs.flashnow.ACTION_RESUME_TIMER"
        const val ACTION_TIMER_FINISHED = "com.saitamagrs.flashnow.ACTION_TIMER_FINISHED"
        const val ACTION_TIMER_TICK = "com.saitamagrs.flashnow.ACTION_TIMER_TICK"
        const val ACTION_CLOSE_APP = "ACTION_CLOSE_APP"

        const val EXTRA_DURATION_MINUTES = "EXTRA_DURATION_MINUTES"
        const val NOTIFICATION_ID = 101
        const val CHANNEL_ID = "flashlight_timer_channel"
        const val PACKAGE_NAME = "com.saitamagrs.flashnow"
        private const val WAKELOCK_BUFFER_MILLIS = 60000L // 60 seconds safety buffer

        const val PREFS_NAME = "timer_prefs"
        const val PREF_KEY_TIMER_RUNNING = "timer_running"
        const val PREF_KEY_IS_PAUSED = "is_paused"
        const val PREF_KEY_TOTAL_DURATION = "total_duration"
        const val PREF_KEY_REMAINING_TIME = "remaining_time"
        const val PREF_KEY_END_TIME = "end_time_millis"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        // WakeLock is intentionally not acquired here.
        // It is acquired after timer duration is received and validated in onStartCommand.
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        Log.d(TAG, "onStartCommand: action=$action, flags=$flags, startId=$startId")

        if (intent == null || action == null) {
            // Android system service recreation due to START_STICKY or started without action
            restoreTimerState(autoResumeIfRunning = true, resumeIfPaused = false)
            return START_STICKY
        }

        when (action) {
            ACTION_START_TIMER -> {
                val mins = intent.getLongExtra(EXTRA_DURATION_MINUTES, 0L)
                if (mins > 0) {
                    // Cancel any existing timer and reset state safely on start
                    countDownTimer?.cancel()
                    countDownTimer = null
                    totalDurationMillis = mins * 60 * 1000L
                    remainingMillis = totalDurationMillis
                    isTimerRunning = true
                    isPaused = false
                    resumeTimer() // Start logic
                } else {
                    Log.w(TAG, "ACTION_START_TIMER received with invalid duration: $mins minutes")
                }
            }
            ACTION_STOP_TIMER -> stopTimer()
            ACTION_PAUSE_TIMER -> {
                if (!isTimerRunning) {
                    restoreTimerState(autoResumeIfRunning = false, resumeIfPaused = false)
                }
                pauseTimer()
            }
            ACTION_RESUME_TIMER -> {
                if (!isTimerRunning) {
                    restoreTimerState(autoResumeIfRunning = true, resumeIfPaused = true)
                } else if (isPaused) {
                    resumeTimer()
                } else {
                    Log.d(TAG, "ACTION_RESUME_TIMER received but timer is already running")
                }
            }
            else -> {
                Log.w(TAG, "Unknown action received: $action")
                if (!isTimerRunning) {
                    restoreTimerState(autoResumeIfRunning = true, resumeIfPaused = false)
                }
            }
        }
        return START_STICKY
    }

    private fun startCountDown(millis: Long) {
        countDownTimer?.cancel()
        countDownTimer = null
        countDownTimer = object : CountDownTimer(millis, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                if (isTimerRunning && !isPaused) {
                    remainingMillis = millisUntilFinished
                    updatePersistedRemainingTime(remainingMillis)
                    broadcastTick(remainingMillis, totalDurationMillis)
                }
            }
            override fun onFinish() {
                if (isTimerRunning && !isPaused) finishTimer()
            }
        }.start()
    }

    private fun pauseTimer() {
        if (isTimerRunning && !isPaused) {
            isPaused = true
            countDownTimer?.cancel()
            countDownTimer = null
            saveTimerState(
                isRunning = true,
                isPaused = true,
                totalDuration = totalDurationMillis,
                remainingTime = remainingMillis,
                endTime = 0L
            )
            try { FlashlightManager.turnOffFlashlight(this) } catch (e: Exception) {}
            releaseWakeLock()
            updateNotification() // Updates UI to "Paused"
            Log.d(TAG, "Timer Paused")
        }
    }

    private fun resumeTimer() {
        if (isTimerRunning) {
            isPaused = false
            countDownTimer?.cancel()
            countDownTimer = null
            val endTime = System.currentTimeMillis() + remainingMillis
            saveTimerState(
                isRunning = true,
                isPaused = false,
                totalDuration = totalDurationMillis,
                remainingTime = remainingMillis,
                endTime = endTime
            )
            acquireWakeLock(remainingMillis)
            try { FlashlightManager.turnOnFlashlight(this) } catch (e: Exception) {}
            startCountDown(remainingMillis)
            updateForegroundNotification() // Sets up Foreground status + live countdown
            Log.d(TAG, "Timer Resumed")
        }
    }

    private fun stopTimer() {
        isTimerRunning = false
        isPaused = false
        countDownTimer?.cancel()
        countDownTimer = null
        finishTimer()
    }

    private fun finishTimer() {
        isTimerRunning = false
        isPaused = false
        countDownTimer?.cancel()
        countDownTimer = null
        try { FlashlightManager.turnOffFlashlight(this) } catch (e: Exception) {}
        clearTimerPreferences()
        broadcastTimerFinished()
        closeApp()
        releaseWakeLock()
        //stopForeground(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    private fun restoreTimerState(autoResumeIfRunning: Boolean, resumeIfPaused: Boolean = false) {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val isRunning = prefs.getBoolean(PREF_KEY_TIMER_RUNNING, false)
        val isPausedPref = prefs.getBoolean(PREF_KEY_IS_PAUSED, false)
        val totalDuration = prefs.getLong(PREF_KEY_TOTAL_DURATION, 0L)
        val remainingTime = prefs.getLong(PREF_KEY_REMAINING_TIME, 0L)
        val endTime = prefs.getLong(PREF_KEY_END_TIME, 0L)

        // SCENARIO I: If there is NO active timer
        if (!isRunning) {
            Log.d(TAG, "restoreTimerState: No active timer in preferences.")
            stopSelf()
            return
        }

        // SCENARIO H / Requirement 11: Invalid or corrupted state
        if (totalDuration <= 0L || remainingTime < 0L || remainingTime > totalDuration) {
            Log.w(
                TAG,
                "restoreTimerState: Invalid persisted state (total=$totalDuration, remaining=$remainingTime, isPaused=$isPausedPref). Clearing state."
            )
            clearTimerPreferences()
            stopSelf()
            return
        }

        // SCENARIO G: Active timer marked paused
        if (isPausedPref) {
            if (remainingTime <= 0L) {
                Log.d(TAG, "restoreTimerState: Paused timer has no remaining time. Finishing.")
                finishTimer()
                return
            }

            Log.d(
                TAG,
                "restoreTimerState: Restoring paused timer (remaining=$remainingTime, total=$totalDuration, resumeIfPaused=$resumeIfPaused)"
            )
            countDownTimer?.cancel()
            countDownTimer = null
            totalDurationMillis = totalDuration
            remainingMillis = remainingTime
            isTimerRunning = true
            isPaused = true

            if (resumeIfPaused) {
                resumeTimer()
            } else {
                // Do not automatically turn the flashlight on.
                // Do not automatically resume the countdown.
                // Do not acquire a WakeLock unless the timer is actually resumed.
                updateForegroundNotification()
            }
            return
        }

        // SCENARIO F: Active timer NOT paused
        val effectiveRemaining: Long = if (endTime > 0L) {
            val now = System.currentTimeMillis()
            val timeUntilEnd = endTime - now
            if (timeUntilEnd <= 0L) {
                Log.d(TAG, "restoreTimerState: Timer finished while process was killed (endTime=$endTime, now=$now).")
                finishTimer()
                return
            } else if (timeUntilEnd <= totalDuration) {
                timeUntilEnd
            } else {
                remainingTime
            }
        } else {
            remainingTime
        }

        if (effectiveRemaining <= 0L) {
            Log.d(TAG, "restoreTimerState: Effective remaining time <= 0. Finishing timer.")
            finishTimer()
            return
        }

        Log.d(TAG, "restoreTimerState: Restoring active timer (remaining=$effectiveRemaining, total=$totalDuration)")
        countDownTimer?.cancel()
        countDownTimer = null
        totalDurationMillis = totalDuration
        remainingMillis = effectiveRemaining
        isTimerRunning = true
        isPaused = false

        if (autoResumeIfRunning) {
            resumeTimer()
        }
    }

    private fun saveTimerState(
        isRunning: Boolean,
        isPaused: Boolean,
        totalDuration: Long,
        remainingTime: Long,
        endTime: Long = 0L
    ) {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().apply {
            putBoolean(PREF_KEY_TIMER_RUNNING, isRunning)
            putBoolean(PREF_KEY_IS_PAUSED, isPaused)
            putLong(PREF_KEY_TOTAL_DURATION, totalDuration)
            putLong(PREF_KEY_REMAINING_TIME, remainingTime)
            putLong(PREF_KEY_END_TIME, endTime)
            apply()
        }
    }

    private fun updatePersistedRemainingTime(remaining: Long) {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().apply {
            putLong(PREF_KEY_REMAINING_TIME, remaining)
            apply()
        }
    }

    private fun updateForegroundNotification() {
        val notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, createNotification())
    }

    private fun createNotification(): Notification {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("fragment", "timer")
        }
        val pendingIntent = PendingIntent.getActivity(this, 0, launchIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val stopIntent = Intent(this, FlashlightTimerService::class.java).apply { action = ACTION_STOP_TIMER }
        val stopPendingIntent = PendingIntent.getService(this, 1, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_timer)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pendingIntent)
            .addAction(R.drawable.ic_stop, "Stop", stopPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setSilent(true)

        if (isPaused) {
            builder.setContentTitle("🔦 Timer Paused")
            builder.setContentText("Paused at: ${formatTime(remainingMillis)}")
            builder.setUsesChronometer(false)
        } else {
            builder.setContentTitle("🔦 Timer Running")
            builder.setContentText("Counting down...")
            builder.setUsesChronometer(true)
            builder.setChronometerCountDown(true)
            builder.setWhen(System.currentTimeMillis() + remainingMillis)
        }

        return builder.build()
    }

    private fun acquireWakeLock(durationMillis: Long) {
        if (durationMillis <= 0) {
            Log.w(TAG, "Cannot acquire WakeLock with non-positive duration: $durationMillis")
            return
        }

        try {
            // Safely release any existing WakeLock before acquiring a new one to prevent leaks
            releaseWakeLock()

            val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (powerManager == null) {
                Log.e(TAG, "PowerManager not available, cannot acquire WakeLock")
                return
            }

            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "FlashNow:TimerWakeLock"
            ).apply {
                setReferenceCounted(false)
            }

            val timeout = durationMillis + WAKELOCK_BUFFER_MILLIS
            wakeLock?.acquire(timeout)
            Log.d(TAG, "WakeLock acquired with timeout: $timeout ms")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire WakeLock", e)
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
                Log.d(TAG, "WakeLock released")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to release WakeLock", e)
        } finally {
            wakeLock = null
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Flashlight Timer", NotificationManager.IMPORTANCE_HIGH).apply {
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun broadcastTick(remaining: Long, total: Long) {
        val intent = Intent(ACTION_TIMER_TICK).apply {
            setPackage(PACKAGE_NAME)
            putExtra("remaining_time", remaining)
            putExtra("total_duration", total)
        }
        sendBroadcast(intent)
    }

    private fun broadcastTimerFinished() {
        sendBroadcast(Intent(ACTION_TIMER_FINISHED).apply { setPackage(PACKAGE_NAME) })
    }

    private fun closeApp() {
        sendBroadcast(Intent(ACTION_CLOSE_APP).apply { setPackage(PACKAGE_NAME) })
    }

    private fun clearTimerPreferences() {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().apply {
            putBoolean(PREF_KEY_TIMER_RUNNING, false)
            putBoolean(PREF_KEY_IS_PAUSED, false)
            putLong(PREF_KEY_TOTAL_DURATION, 0L)
            putLong(PREF_KEY_REMAINING_TIME, 0L)
            putLong(PREF_KEY_END_TIME, 0L)
            apply()
        }
    }

    private fun formatTime(ms: Long): String {
        val s = ms / 1000
        return String.format(java.util.Locale.US, "%02d:%02d", s / 60, s % 60)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        stopTimer()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        super.onDestroy()
        countDownTimer?.cancel()
        countDownTimer = null
        serviceScope.cancel()
        releaseWakeLock()
    }

    override fun onBind(intent: Intent): IBinder? = null
}