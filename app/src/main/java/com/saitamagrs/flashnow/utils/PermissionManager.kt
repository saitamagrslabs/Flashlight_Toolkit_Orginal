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
import androidx.activity.result.ActivityResultLauncher
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import java.util.LinkedList
import java.util.Queue

object PermissionManager {

    private val permissionQueue: Queue<String> = LinkedList()
    var lastRequestedPermission: String? = null
        private set

    fun addPermissionToQueue(context: Context, permission: String) {
        if (ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED) {
            if (!permissionQueue.contains(permission)) {
                permissionQueue.add(permission)
            }
        }
    }

    fun processNextPermission(activity: Activity, permissionLauncher: ActivityResultLauncher<String>) {
        if (permissionQueue.isNotEmpty()) {
            val permission = permissionQueue.poll()
            lastRequestedPermission = permission
            showRationaleAndRequest(activity, permission, permissionLauncher)
        } else {
            lastRequestedPermission = null
        }
    }
    
    fun isQueueEmpty(): Boolean = permissionQueue.isEmpty()

    fun showRationaleAndRequest(activity: Activity, permission: String, permissionLauncher: ActivityResultLauncher<String>) {
        val rationale = getRationaleForPermission(permission)

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

    private fun getRationaleForPermission(permission: String): String {
        return when (permission) {
            Manifest.permission.CAMERA ->
                "To turn the flashlight on and off, this app needs access to your device's camera hardware."
            Manifest.permission.RECORD_AUDIO ->
                "To use the 'Clap to Turn On' feature, this app needs access to the microphone to listen for clap sounds. No audio is ever recorded or stored."
            Manifest.permission.POST_NOTIFICATIONS ->
                "On newer Android versions, notifications are required to show the timer status and for the app to run reliably in the background."
            else ->
                "This app requires some permissions to function correctly."
        }
    }
    fun checkAndRequestNotificationPermission(activity: Activity, launcher: ActivityResultLauncher<String>) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val permission = Manifest.permission.POST_NOTIFICATIONS
            if (ContextCompat.checkSelfPermission(activity, permission) != PackageManager.PERMISSION_GRANTED) {
                addPermissionToQueue(activity, permission)
                processNextPermission(activity, launcher)
            }
        }
    }
    fun isNotificationPermissionGranted(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else {
            true // Permissions are granted by default on older versions
        }
    }
    fun showGoToSettingsDialog(context: Context) {
        AlertDialog.Builder(context)
            .setTitle("Permission Required")
            .setMessage("You have permanently denied a required permission. To use this feature, please go to app settings and grant the permission manually.")
            .setPositiveButton("Go to Settings") { _, _ ->
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", context.packageName, null)
                }
                context.startActivity(intent)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // --- Special Permission Handlers ---

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
            .setMessage("To guarantee the timer works when the app is closed, please enable the 'Alarms & reminders' permission for FlashNow.")
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
            .setMessage("For the timer to work reliably on your device, please allow FlashNow to run without battery restrictions.")
            .setPositiveButton("Go to Settings") { _, _ ->
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", context.packageName, null)
                }
                context.startActivity(intent)
            }
            .setNegativeButton("Later", null)
            .show()
    }
}