package com.saitamagrs.flashnow.utils

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import java.util.LinkedList
import java.util.Queue

/**
 * Centralized, industry-standard permission manager for Flashlight Toolkit.
 *
 * Responsibilities:
 * - Version-aware permission checks (e.g. POST_NOTIFICATIONS on Android 13+)
 * - Live permission state queries for Settings and UI
 * - Feature-driven, just-in-time permission rationales
 * - Safe navigation to Android App Details Settings
 * - Backwards-compatible legacy queue support
 */
object PermissionManager {

    enum class PermissionState {
        ALLOWED,
        NOT_ALLOWED,
        NOT_REQUIRED
    }

    // =========================================================================
    // 1. Direct Permission Checks
    // =========================================================================

    fun isPermissionGranted(context: Context, permission: String): Boolean {
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    fun isCameraGranted(context: Context): Boolean {
        return isPermissionGranted(context, Manifest.permission.CAMERA)
    }

    fun isAudioGranted(context: Context): Boolean {
        return isPermissionGranted(context, Manifest.permission.RECORD_AUDIO)
    }

    fun isNotificationPermissionApplicable(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    }

    fun isNotificationGranted(context: Context): Boolean {
        return if (isNotificationPermissionApplicable()) {
            isPermissionGranted(context, Manifest.permission.POST_NOTIFICATIONS)
        } else {
            true // Notification permission is implicitly granted on Android 12 and below
        }
    }

    // Backward-compatible alias
    fun isNotificationPermissionGranted(context: Context): Boolean = isNotificationGranted(context)

    // =========================================================================
    // 2. High-Level Permission States (Live for UI/Settings)
    // =========================================================================

    fun getCameraState(context: Context): PermissionState {
        return if (isCameraGranted(context)) PermissionState.ALLOWED else PermissionState.NOT_ALLOWED
    }

    fun getAudioState(context: Context): PermissionState {
        return if (isAudioGranted(context)) PermissionState.ALLOWED else PermissionState.NOT_ALLOWED
    }

    fun getNotificationState(context: Context): PermissionState {
        return when {
            !isNotificationPermissionApplicable() -> PermissionState.NOT_REQUIRED
            isPermissionGranted(context, Manifest.permission.POST_NOTIFICATIONS) -> PermissionState.ALLOWED
            else -> PermissionState.NOT_ALLOWED
        }
    }

    // =========================================================================
    // 3. Navigation to App Settings
    // =========================================================================

    fun openAppSettings(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", context.packageName, null)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(context, "Unable to open system settings", Toast.LENGTH_SHORT).show()
        }
    }

    fun showGoToSettingsDialog(context: Context) {
        AlertDialog.Builder(context)
            .setTitle("Permission Required")
            .setMessage("You have permanently denied a required permission. To use this feature, please open app settings and grant the permission manually.")
            .setPositiveButton("Open Settings") { _, _ ->
                openAppSettings(context)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // =========================================================================
    // 4. Feature-Specific Rationale & Permanent Denial Dialogs
    // =========================================================================

    fun getPermissionRationale(permission: String): String {
        return when (permission) {
            Manifest.permission.CAMERA ->
                "Camera access is required for the optical receiver in Morse Communicator to detect incoming light pulses. No photos or videos are ever taken or stored."
            Manifest.permission.RECORD_AUDIO ->
                "Microphone access is required for Clap Detection to listen for clap trigger sounds. No audio is ever recorded, stored, or transmitted."
            Manifest.permission.POST_NOTIFICATIONS ->
                "Notification access is required to show the timer countdown status and background alerts."
            else ->
                "This permission is required for the requested feature to function correctly."
        }
    }

    fun getPermissionDeniedExplanation(permission: String): String {
        return when (permission) {
            Manifest.permission.CAMERA ->
                "Camera permission is currently disabled. To use the Morse optical receiver, please allow Camera access in App Settings."
            Manifest.permission.RECORD_AUDIO ->
                "Microphone permission is currently disabled. To use Clap Detection, please allow Microphone access in App Settings."
            Manifest.permission.POST_NOTIFICATIONS ->
                "Notification permission is currently disabled. To receive timer alerts, please allow Notifications in App Settings."
            else ->
                "This permission is currently disabled. Please enable it in App Settings to use this feature."
        }
    }

    fun showRationaleDialog(
        context: Context,
        permission: String,
        onContinue: () -> Unit
    ) {
        showRationaleDialog(context, permission, onContinue, null)
    }

    fun showRationaleDialog(
        context: Context,
        permission: String,
        onContinue: () -> Unit,
        onCancel: (() -> Unit)?
    ) {
        AlertDialog.Builder(context)
            .setTitle("Permission Needed")
            .setMessage(getPermissionRationale(permission))
            .setPositiveButton("Continue") { _, _ -> onContinue() }
            .setNegativeButton("Not Now") { dialog, _ ->
                dialog.dismiss()
                onCancel?.invoke()
            }
            .setCancelable(false)
            .show()
    }

    fun showPermanentlyDeniedDialog(
        context: Context,
        permission: String,
        onCancel: (() -> Unit)? = null
    ) {
        AlertDialog.Builder(context)
            .setTitle("Permission Required")
            .setMessage(getPermissionDeniedExplanation(permission))
            .setPositiveButton("Open Settings") { _, _ ->
                openAppSettings(context)
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
                onCancel?.invoke()
            }
            .show()
    }

    // =========================================================================
    // 5. Backward Compatibility (Queue System for Unit Tests / Legacy Flows)
    // =========================================================================

    private val permissionQueue: Queue<String> = LinkedList()
    var lastRequestedPermission: String? = null
        private set

    fun addPermissionToQueue(context: Context, permission: String) {
        if (!isPermissionGranted(context, permission)) {
            if (!permissionQueue.contains(permission)) {
                permissionQueue.add(permission)
            }
        }
    }

    fun processNextPermission(activity: Activity, permissionLauncher: ActivityResultLauncher<String>) {
        val permission = permissionQueue.poll()
        if (permission != null) {
            lastRequestedPermission = permission
            showRationaleAndRequest(activity, permission, permissionLauncher)
        } else {
            lastRequestedPermission = null
        }
    }

    fun isQueueEmpty(): Boolean = permissionQueue.isEmpty()

    fun showRationaleAndRequest(activity: Activity, permission: String, permissionLauncher: ActivityResultLauncher<String>) {
        val rationale = getPermissionRationale(permission)

        AlertDialog.Builder(activity)
            .setTitle("Permission Required")
            .setMessage(rationale)
            .setPositiveButton("Continue") { _, _ ->
                permissionLauncher.launch(permission)
            }
            .setNegativeButton("Not Now") { dialog, _ ->
                dialog.dismiss()
                permissionQueue.clear()
            }
            .setCancelable(false)
            .show()
    }

    fun checkAndRequestNotificationPermission(activity: Activity, launcher: ActivityResultLauncher<String>) {
        if (isNotificationPermissionApplicable()) {
            val permission = Manifest.permission.POST_NOTIFICATIONS
            if (!isPermissionGranted(activity, permission)) {
                addPermissionToQueue(activity, permission)
                processNextPermission(activity, launcher)
            }
        }
    }

    // =========================================================================
    // 6. Special Permission Handlers (Exact Alarms & Battery Optimizations)
    // =========================================================================

    fun canScheduleExactAlarms(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            return alarmManager.canScheduleExactAlarms()
        }
        return true
    }

    fun showExactAlarmPermissionDialog(context: Context) {
        AlertDialog.Builder(context)
            .setTitle("Special Access Required")
            .setMessage("To guarantee the timer works when the app is closed, please enable the 'Alarms & reminders' permission for Flashlight Toolkit.")
            .setPositiveButton("Go to Settings") { _, _ ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM))
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            return powerManager.isIgnoringBatteryOptimizations(context.packageName)
        }
        return true
    }

    fun showBatteryOptimizationDialog(context: Context) {
        AlertDialog.Builder(context)
            .setTitle("Special Access Recommended")
            .setMessage("For the timer to work reliably on your device, please allow Flashlight Toolkit to run without battery restrictions.")
            .setPositiveButton("Go to Settings") { _, _ ->
                openAppSettings(context)
            }
            .setNegativeButton("Later", null)
            .show()
    }
}