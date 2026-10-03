package com.saitamagrs.flashnow.utils

import android.content.Context
import android.util.Log

/**
 * Singleton manager that provides a single instance of FlashlightController
 * to ensure consistent flashlight state across the app
 */
object FlashlightManager {

    @Volatile
    private var instance: FlashlightController? = null

    private val stateChangeListeners = mutableListOf<(Boolean) -> Unit>()

    @Volatile
    private var isSosRunning = false

    @Volatile
    private var isStrobeRunning = false

    @Volatile
    private var isNotificationFlashing = false

    @Volatile
    var lastExternalActionTime: Long = 0L
        private set

    @Synchronized
    fun getInstance(context: Context): FlashlightController {
        return instance ?: FlashlightController(context.applicationContext).also {
            instance = it

            // Register for flashlight state changes
            it.addOnFlashlightStateChangeListener { isOn ->
                markExternalAction()
                stateChangeListeners.forEach { listener ->
                    listener(isOn)
                }
            }

            Log.d("FlashlightManager", "New FlashlightController instance created")
        }
    }

    fun markExternalAction() {
        if (!isNotificationFlashing) {
            lastExternalActionTime = System.currentTimeMillis()
        }
    }

    fun setNotificationFlashing(flashing: Boolean) {
        isNotificationFlashing = flashing
    }

    fun isNotificationFlashing(): Boolean = isNotificationFlashing

    @Synchronized
    fun release() {
        instance?.release()
        instance = null
        stateChangeListeners.clear()
        isSosRunning = false
        isStrobeRunning = false
        isNotificationFlashing = false
        Log.d("FlashlightManager", "FlashlightManager released")
    }

    @Synchronized
    fun isInitialized(): Boolean {
        return instance != null
    }

    // Helper methods for common operations
    fun turnOnFlashlight(context: Context) {
        markExternalAction()
        getInstance(context).turnOn()
        stateChangeListeners.forEach { it(true) }
    }

    fun turnOffFlashlight(context: Context) {
        markExternalAction()
        getInstance(context).turnOff()
        stateChangeListeners.forEach { it(false) }
    }

    fun isFlashlightOn(context: Context): Boolean {
        return getInstance(context).isFlashlightOn()
    }

    // Active Pattern Tracking (SOS / Strobe)
    fun setSosActive(active: Boolean) {
        if (active) markExternalAction()
        isSosRunning = active
    }

    fun isSosActive(): Boolean = isSosRunning

    fun setStrobeActive(active: Boolean) {
        if (active) markExternalAction()
        isStrobeRunning = active
    }

    fun isStrobeActive(): Boolean = isStrobeRunning

    fun isPatternActive(): Boolean = isSosRunning || isStrobeRunning

    // Add listener for UI updates
    fun addFlashlightStateListener(listener: (Boolean) -> Unit) {
        if (!stateChangeListeners.contains(listener)) {
            stateChangeListeners.add(listener)
        }
    }

    fun removeFlashlightStateListener(listener: (Boolean) -> Unit) {
        stateChangeListeners.remove(listener)
    }
}