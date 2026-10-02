package com.saitamagrs.flashnow.morse.receiver

import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.saitamagrs.flashnow.morse.core.MorseCodec
import com.saitamagrs.flashnow.morse.core.MorseProtocol
import com.saitamagrs.flashnow.morse.core.MorseTiming
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer

/**
 * Optical Morse Code Receiver Engine.
 * Analyzes video stream frames from CameraX ImageAnalysis to detect flashlight pulses and decode text.
 */
class MorseReceiverEngine : ImageAnalysis.Analyzer {

    companion object {
        private const val TAG = "MorseReceiverEngine"
        private const val MIN_CONTRAST_THRESHOLD = 20.0f
        private const val LUMINANCE_HISTORY_SIZE = 30 // ~1 second at 30 fps
    }

    // Exposed StateFlows for UI and diagnostic observing
    private val _rawLuminance = MutableStateFlow(0.0f)
    val rawLuminance: StateFlow<Float> = _rawLuminance.asStateFlow()

    private val _threshold = MutableStateFlow(0.0f)
    val threshold: StateFlow<Float> = _threshold.asStateFlow()

    private val _isLightOn = MutableStateFlow(false)
    val isLightOn: StateFlow<Boolean> = _isLightOn.asStateFlow()

    private val _currentSymbols = MutableStateFlow("")
    val currentSymbols: StateFlow<String> = _currentSymbols.asStateFlow()

    private val _decodedText = MutableStateFlow("")
    val decodedText: StateFlow<String> = _decodedText.asStateFlow()

    private val _preambleDetected = MutableStateFlow(false)
    val preambleDetected: StateFlow<Boolean> = _preambleDetected.asStateFlow()

    private val _debugLog = MutableStateFlow("Receiver ready")
    val debugLog: StateFlow<String> = _debugLog.asStateFlow()

    // Internal Signal Processing State
    private val luminanceHistory = ArrayDeque<Float>()
    private var minLuma = 255.0f
    private var maxLuma = 0.0f

    private var currentStateIsOn = false
    private var lastStateChangeTimestamp = 0L

    private val currentSymbolBuffer = StringBuilder()
    private val decodedMessageBuilder = StringBuilder()

    // Flag for initial frame
    private var isFirstFrame = true

    override fun analyze(image: ImageProxy) {
        try {
            val luma = calculateYPlaneLuminance(image)
            val nowMs = SystemClock.elapsedRealtime()

            _rawLuminance.value = luma

            // Update sliding window min/max
            updateLuminanceStats(luma)

            val range = maxLuma - minLuma
            val dynamicThreshold = minLuma + 0.5f * range
            _threshold.value = dynamicThreshold

            val detectedOn = if (range >= MIN_CONTRAST_THRESHOLD) {
                luma > dynamicThreshold
            } else {
                false // Low contrast, default to OFF
            }

            if (isFirstFrame) {
                currentStateIsOn = detectedOn
                lastStateChangeTimestamp = nowMs
                isFirstFrame = false
                _isLightOn.value = detectedOn
                return
            }

            // Check state transition
            if (detectedOn != currentStateIsOn) {
                val durationMs = nowMs - lastStateChangeTimestamp
                lastStateChangeTimestamp = nowMs

                if (currentStateIsOn) {
                    // ON -> OFF transition: Process pulse duration
                    handlePulse(durationMs)
                } else {
                    // OFF -> ON transition: Process gap duration
                    handleGap(durationMs)
                }

                currentStateIsOn = detectedOn
                _isLightOn.value = detectedOn
            } else {
                // If light remains OFF for a long duration, check for END_GAP or WORD_GAP timeout
                if (!currentStateIsOn && lastStateChangeTimestamp > 0) {
                    val idleGapMs = nowMs - lastStateChangeTimestamp
                    if (idleGapMs >= MorseTiming.END_GAP_MS && currentSymbolBuffer.isNotEmpty()) {
                        finalizeLetter()
                        _debugLog.value = "Frame ended (Timeout)"
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error analyzing frame", e)
        } finally {
            image.close()
        }
    }

    private fun calculateYPlaneLuminance(image: ImageProxy): Float {
        val plane = image.planes[0]
        val buffer: ByteBuffer = plane.buffer
        buffer.rewind()

        val data = ByteArray(buffer.remaining())
        buffer.get(data)

        if (data.isEmpty()) return 0.0f

        // Subsample every 16th byte for fast processing
        var sum = 0L
        var count = 0
        val step = 16
        var i = 0
        while (i < data.size) {
            sum += (data[i].toInt() and 0xFF)
            count++
            i += step
        }

        return if (count > 0) sum.toFloat() / count else 0.0f
    }

    private fun updateLuminanceStats(luma: Float) {
        luminanceHistory.addLast(luma)
        if (luminanceHistory.size > LUMINANCE_HISTORY_SIZE) {
            luminanceHistory.removeFirst()
        }

        minLuma = luminanceHistory.minOrNull() ?: 0.0f
        maxLuma = luminanceHistory.maxOrNull() ?: 255.0f
    }

    private fun handlePulse(durationMs: Long) {
        val pulseType = MorseTiming.classifyPulse(durationMs)
        when (pulseType) {
            MorseTiming.PulseType.DOT -> currentSymbolBuffer.append('.')
            MorseTiming.PulseType.DASH -> currentSymbolBuffer.append('-')
            MorseTiming.PulseType.UNKNOWN -> return
        }
        _currentSymbols.value = currentSymbolBuffer.toString()
        _debugLog.value = "Pulse: $pulseType (${durationMs}ms) -> Symbols: $currentSymbolBuffer"
    }

    private fun handleGap(durationMs: Long) {
        val gapType = MorseTiming.classifyGap(durationMs)
        _debugLog.value = "Gap: $gapType (${durationMs}ms)"

        when (gapType) {
            MorseTiming.GapType.SYMBOL_GAP -> {
                // Continuation within a letter
            }
            MorseTiming.GapType.LETTER_GAP -> {
                finalizeLetter()
            }
            MorseTiming.GapType.WORD_GAP -> {
                finalizeLetter()
                if (decodedMessageBuilder.isNotEmpty() && !decodedMessageBuilder.endsWith(" ")) {
                    decodedMessageBuilder.append(" ")
                    _decodedText.value = decodedMessageBuilder.toString()
                }
            }
            MorseTiming.GapType.END_GAP -> {
                finalizeLetter()
                _debugLog.value = "End Gap frame complete"
            }
        }
    }

    private fun finalizeLetter() {
        val pattern = currentSymbolBuffer.toString()
        if (pattern.isEmpty()) return

        // Check if preamble matched ("...")
        if (pattern == MorseProtocol.PREAMBLE_MORSE_PATTERN && !_preambleDetected.value) {
            _preambleDetected.value = true
            _debugLog.value = "Preamble synced ('...')"
            currentSymbolBuffer.clear()
            _currentSymbols.value = ""
            // Clear message buffer for fresh frame sync
            decodedMessageBuilder.clear()
            _decodedText.value = ""
            return
        }

        val char = MorseCodec.decodeLetter(pattern)
        if (char != null) {
            decodedMessageBuilder.append(char)
            _decodedText.value = decodedMessageBuilder.toString()
            _debugLog.value = "Decoded letter '$char' from '$pattern'"
        } else {
            _debugLog.value = "Unrecognized pattern '$pattern'"
        }

        currentSymbolBuffer.clear()
        _currentSymbols.value = ""
    }

    /**
     * Resets receiver state machine.
     */
    fun reset() {
        luminanceHistory.clear()
        currentSymbolBuffer.clear()
        decodedMessageBuilder.clear()
        _currentSymbols.value = ""
        _decodedText.value = ""
        _preambleDetected.value = false
        _rawLuminance.value = 0.0f
        _threshold.value = 0.0f
        _isLightOn.value = false
        _debugLog.value = "Receiver reset"
        isFirstFrame = true
    }
}
