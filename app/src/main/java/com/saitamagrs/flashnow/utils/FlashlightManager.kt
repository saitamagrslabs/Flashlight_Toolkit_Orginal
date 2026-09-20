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
    
    @Synchronized
    fun getInstance(context: Context): FlashlightController {
        return instance ?: FlashlightController(context.applicationContext).also {
            instance = it

            // Register for flashlight state changes
            it.addOnFlashlightStateChangeListener { isOn ->
                stateChangeListeners.forEach { listener ->
                    listener(isOn)
                }
            }

            Log.d("FlashlightManager", "New FlashlightController instance created")
        }
    }

    @Synchronized
    fun release() {
        instance?.release()
        instance = null
        stateChangeListeners.clear()
        Log.d("FlashlightManager", "FlashlightManager released")
    }

    @Synchronized
    fun isInitialized(): Boolean {
        return instance != null
    }

    // Helper methods for common operations
    fun turnOnFlashlight(context: Context) {
        getInstance(context).turnOn()
        // Notify listeners
        stateChangeListeners.forEach { it(true) }
    }

    fun turnOffFlashlight(context: Context) {
        getInstance(context).turnOff()
        // Notify listeners - FIXED: should be false
        stateChangeListeners.forEach { it(false) }
    }

    fun isFlashlightOn(context: Context): Boolean {
        return getInstance(context).isFlashlightOn()
    }

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