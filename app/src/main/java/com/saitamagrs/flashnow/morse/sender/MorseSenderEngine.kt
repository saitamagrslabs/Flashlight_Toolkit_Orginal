package com.saitamagrs.flashnow.morse.sender

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Log
import com.saitamagrs.flashnow.morse.core.MorseProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Optical Morse Code Sender Engine.
 * Converts input text into preamble-framed flashlight optical signals.
 */
class MorseSenderEngine(private val context: Context) {

    companion object {
        private const val TAG = "MorseSenderEngine"
    }

    private val scope = CoroutineScope(Dispatchers.Default)
    private var transmissionJob: Job? = null

    private val _isTransmitting = MutableStateFlow(false)
    val isTransmitting: StateFlow<Boolean> = _isTransmitting.asStateFlow()

    private val _progressPercent = MutableStateFlow(0)
    val progressPercent: StateFlow<Int> = _progressPercent.asStateFlow()

    private val _statusText = MutableStateFlow("Idle")
    val statusText: StateFlow<String> = _statusText.asStateFlow()

    private var cameraId: String? = null
    private val cameraManager: CameraManager? by lazy {
        try {
            context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
        } catch (e: Exception) {
            Log.e(TAG, "Error obtaining CameraManager", e)
            null
        }
    }

    init {
        findFlashCameraId()
    }

    private fun findFlashCameraId() {
        try {
            val cm = cameraManager ?: return
            for (id in cm.cameraIdList) {
                val chars = cm.getCameraCharacteristics(id)
                val hasFlash = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                val facing = chars.get(CameraCharacteristics.LENS_FACING)
                if (hasFlash && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    cameraId = id
                    break
                }
            }
            if (cameraId == null && cm.cameraIdList.isNotEmpty()) {
                // Fallback to first camera with flash
                for (id in cm.cameraIdList) {
                    val chars = cm.getCameraCharacteristics(id)
                    if (chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true) {
                        cameraId = id
                        break
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error locating flash camera ID", e)
        }
    }

    /**
     * Toggles flashlight torch state safely.
     */
    private fun setTorchState(enabled: Boolean) {
        val id = cameraId ?: return
        try {
            cameraManager?.setTorchMode(id, enabled)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set torch mode to $enabled", e)
        }
    }

    /**
     * Starts transmitting text as optical Morse code.
     * @param text Message to transmit
     * @param repeat True to loop transmission until stopped
     */
    fun startTransmission(text: String, repeat: Boolean = false) {
        if (text.isBlank()) {
            _statusText.value = "Error: Message is empty"
            return
        }

        stopTransmission()

        transmissionJob = scope.launch {
            _isTransmitting.value = true
            _statusText.value = "Transmitting: '$text'"
            _progressPercent.value = 0

            val framePulses = MorseProtocol.buildFrame(text)
            val totalPulses = framePulses.size

            try {
                do {
                    for ((index, pulse) in framePulses.withIndex()) {
                        setTorchState(pulse.isOn)
                        val progress = ((index + 1).toFloat() / totalPulses * 100).toInt()
                        _progressPercent.value = progress

                        delay(pulse.durationMs)
                    }
                } while (repeat && _isTransmitting.value)

                _statusText.value = "Transmission complete"
            } catch (e: Exception) {
                Log.e(TAG, "Transmission interrupted", e)
                _statusText.value = "Transmission interrupted"
            } finally {
                setTorchState(false)
                _isTransmitting.value = false
                _progressPercent.value = 100
            }
        }
    }

    /**
     * Stops any active optical transmission and turns off torch.
     */
    fun stopTransmission() {
        transmissionJob?.cancel()
        transmissionJob = null
        setTorchState(false)
        _isTransmitting.value = false
        _statusText.value = "Stopped"
    }

    /**
     * Clean up resources.
     */
    fun release() {
        stopTransmission()
    }
}
