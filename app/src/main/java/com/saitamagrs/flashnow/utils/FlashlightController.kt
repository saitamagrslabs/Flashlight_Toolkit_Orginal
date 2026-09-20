package com.saitamagrs.flashnow.utils

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi

class FlashlightController(private val context: Context) {

    private val appContext = context.applicationContext
    private var cameraId: String? = null
    private val cameraManager: CameraManager by lazy {
        appContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    }

    @Volatile private var isFlashOn = false
    private val stateChangeCallbacks = mutableListOf<(Boolean) -> Unit>()

    // ── TorchCallback ─────────────────────────────────────────────────────────
    //
    // PREVIOUS BUG: The old code had an `isControlledByApp` flag that SUPPRESSED
    // listener notifications whenever the app itself changed the torch state.
    // This meant:
    //   1. When the OS killed the torch on screen-lock, isControlledByApp was
    //      still true from the last app-initiated turnOn() call.
    //   2. The callback fired with enabled=false (OS killed it).
    //   3. But because isControlledByApp was true, we swallowed the notification.
    //   4. isFlashOn stayed = true even though the torch was physically off.
    //   5. When the service tried to turn it off, it saw isFlashOn=true but the
    //      hardware was already off, causing inconsistent state.
    //
    // FIX: Always update isFlashOn and always notify listeners for every
    // state change — whether triggered by the app or by the OS.
    // The service and UI can decide what to do with that information.

    @RequiresApi(Build.VERSION_CODES.M)
    private val torchCallback = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
            super.onTorchModeChanged(cameraId, enabled)
            if (cameraId != this@FlashlightController.cameraId) return

            val previousState = isFlashOn
            isFlashOn = enabled

            Log.d("FlashlightController", "TorchCallback: enabled=$enabled (was $previousState)")

            // Always notify ALL listeners of every state change.
            // This lets the UI and service react if the OS kills the torch.
            stateChangeCallbacks.toList().forEach { callback ->
                try { callback(enabled) }
                catch (e: Exception) { Log.e("FlashlightController", "Error in callback", e) }
            }
        }

        override fun onTorchModeUnavailable(cameraId: String) {
            super.onTorchModeUnavailable(cameraId)
            if (cameraId != this@FlashlightController.cameraId) return
            Log.w("FlashlightController", "Torch unavailable for cameraId=$cameraId")
            isFlashOn = false
            stateChangeCallbacks.toList().forEach { callback ->
                try { callback(false) }
                catch (e: Exception) { Log.e("FlashlightController", "Error in unavailable callback", e) }
            }
        }
    }

    // ── Init ──────────────────────────────────────────────────────────────────

    init {
        initializeCamera()
    }

    private fun initializeCamera() {
        try {
            cameraId = getCameraId()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && cameraId != null) {
                cameraManager.registerTorchCallback(torchCallback, null)
                Log.d("FlashlightController", "TorchCallback registered for cameraId=$cameraId")
            }
        } catch (e: Exception) {
            Log.e("FlashlightController", "Error initializing camera", e)
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    fun turnOn() {
        safeTorchOperation {
            cameraManager.setTorchMode(cameraId!!, true)
            isFlashOn = true
            Log.d("FlashlightController", "turnOn() called → torch ON")
        }
    }

    fun turnOff() {
        safeTorchOperation {
            cameraManager.setTorchMode(cameraId!!, false)
            isFlashOn = false
            Log.d("FlashlightController", "turnOff() called → torch OFF")
        }
    }

    fun isFlashlightOn(): Boolean = isFlashOn

    fun addOnFlashlightStateChangeListener(callback: (Boolean) -> Unit) {
        if (!stateChangeCallbacks.contains(callback)) stateChangeCallbacks.add(callback)
    }

    fun removeOnFlashlightStateChangeListener(callback: (Boolean) -> Unit) {
        stateChangeCallbacks.remove(callback)
    }

    fun release() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try { cameraManager.unregisterTorchCallback(torchCallback) }
            catch (e: Exception) { Log.e("FlashlightController", "Error unregistering callback", e) }
        }
        stateChangeCallbacks.clear()
        turnOff()
        Log.d("FlashlightController", "released")
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private fun safeTorchOperation(operation: () -> Unit) {
        if (cameraId == null) {
            Log.w("FlashlightController", "cameraId is null — cannot operate torch")
            return
        }
        try {
            operation()
        } catch (e: Exception) {
            Log.e("FlashlightController", "Torch operation failed", e)
        }
    }

    private fun getCameraId(): String? {
        return try {
            cameraManager.cameraIdList.firstOrNull { id ->
                val chars = cameraManager.getCameraCharacteristics(id)
                chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                        chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            }
        } catch (e: Exception) {
            Log.e("FlashlightController", "Error getting camera ID", e)
            null
        }
    }
}