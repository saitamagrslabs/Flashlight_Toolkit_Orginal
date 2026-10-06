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
 *
 * Enhanced for distance reliability:
 * - Center-focused Region of Interest (ROI) matching on-screen reticle
 * - 97th percentile bright-signal detection (immune to single hot pixels)
 * - 40th percentile adaptive ambient baseline
 * - Hysteresis thresholding (Schmitt trigger) preventing edge jitter
 * - 45ms candidate glitch filter rejecting transient camera noise
 */
class MorseReceiverEngine : ImageAnalysis.Analyzer {

    companion object {
        private const val TAG = "MorseReceiverEngine"

        // Center ROI definition (28% of frame dimensions, centered)
        private const val ROI_RATIO = 0.28f

        // Percentiles for robust ambient baseline and bright signal calculation
        private const val AMBIENT_PERCENTILE = 0.40f // 40th percentile = robust ambient baseline
        private const val BRIGHT_PERCENTILE = 0.97f  // 97th percentile = cluster of bright flashlight pixels

        // Adaptive contrast thresholds with hysteresis (Schmitt trigger)
        private const val ON_THRESHOLD_CONTRAST = 25.0f
        private const val OFF_THRESHOLD_CONTRAST = 14.0f

        // Initial calibration window (frames)
        private const val CALIBRATION_FRAMES_REQUIRED = 10

        // Glitch rejection filter threshold
        private const val MIN_GLITCH_DURATION_MS = 45L
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

    // Reusable 256-bin histogram for zero-allocation percentile computation
    private val histogram = IntArray(256)

    // Internal Signal Processing State
    private var ambientBaseline = 40.0f
    private var calibrationFramesCount = 0

    private var currentStateIsOn = false
    private var lastStateChangeTimestamp = 0L

    // Glitch candidate state
    private var candidateStateIsOn: Boolean? = null
    private var candidateStateTimestamp = 0L

    private val currentSymbolBuffer = StringBuilder()
    private val decodedMessageBuilder = StringBuilder()

    override fun analyze(image: ImageProxy) {
        try {
            val plane = image.planes[0]
            val buffer: ByteBuffer = plane.buffer
            buffer.rewind()

            val imgWidth = image.width
            val imgHeight = image.height

            // Calculate center ROI (28% of frame dimensions)
            val roiWidth = (imgWidth * ROI_RATIO).toInt().coerceAtLeast(16)
            val roiHeight = (imgHeight * ROI_RATIO).toInt().coerceAtLeast(16)

            val startX = (imgWidth - roiWidth) / 2
            val startY = (imgHeight - roiHeight) / 2
            val endX = startX + roiWidth
            val endY = startY + roiHeight

            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride.coerceAtLeast(1)

            // 1. Build 256-bin histogram from center ROI (stride = 2 for high speed)
            histogram.fill(0)
            var sampleCount = 0
            val limit = buffer.limit()

            val step = 2
            var y = startY
            while (y < endY) {
                val rowStart = y * rowStride
                var x = startX
                while (x < endX) {
                    val index = rowStart + (x * pixelStride)
                    if (index < limit) {
                        val luma = buffer.get(index).toInt() and 0xFF
                        histogram[luma]++
                        sampleCount++
                    }
                    x += step
                }
                y += step
            }

            if (sampleCount == 0) return

            // 2. Compute 40th percentile (ambient) and 97th percentile (bright signal)
            val localAmbient = getPercentileValue(sampleCount, AMBIENT_PERCENTILE)
            val brightSignal = getPercentileValue(sampleCount, BRIGHT_PERCENTILE)

            val nowMs = SystemClock.elapsedRealtime()

            // 3. Initial calibration phase to establish stable ambient baseline
            if (calibrationFramesCount < CALIBRATION_FRAMES_REQUIRED) {
                calibrationFramesCount++
                ambientBaseline = if (calibrationFramesCount == 1) {
                    localAmbient.toFloat()
                } else {
                    (ambientBaseline * 0.7f) + (localAmbient.toFloat() * 0.3f)
                }
                lastStateChangeTimestamp = nowMs
                currentStateIsOn = false
                candidateStateIsOn = null
                _rawLuminance.value = brightSignal.toFloat()
                _threshold.value = ambientBaseline + ON_THRESHOLD_CONTRAST
                _isLightOn.value = false
                return
            }

            // 4. Update adaptive ambient baseline (gradual smoothing when light is OFF)
            if (!currentStateIsOn) {
                ambientBaseline = (ambientBaseline * 0.94f) + (localAmbient.toFloat() * 0.06f)
            } else {
                // When light is ON, only adapt downward if ambient drops, never upward
                if (localAmbient < ambientBaseline) {
                    ambientBaseline = (ambientBaseline * 0.95f) + (localAmbient.toFloat() * 0.05f)
                }
            }

            val signalContrast = (brightSignal - ambientBaseline).coerceAtLeast(0.0f)
            _rawLuminance.value = brightSignal.toFloat()

            // 5. Hysteresis detection (Schmitt trigger)
            val detectedRawOn = if (currentStateIsOn) {
                signalContrast > OFF_THRESHOLD_CONTRAST
            } else {
                signalContrast >= ON_THRESHOLD_CONTRAST
            }

            val activeThreshold = if (currentStateIsOn) {
                ambientBaseline + OFF_THRESHOLD_CONTRAST
            } else {
                ambientBaseline + ON_THRESHOLD_CONTRAST
            }
            _threshold.value = activeThreshold

            // 6. Glitch filtering (ignore transitions < 45 ms)
            if (detectedRawOn != currentStateIsOn) {
                if (candidateStateIsOn != detectedRawOn) {
                    // New candidate state started
                    candidateStateIsOn = detectedRawOn
                    candidateStateTimestamp = nowMs
                } else {
                    // Candidate persisted across frames
                    val candidateDuration = nowMs - candidateStateTimestamp
                    if (candidateDuration >= MIN_GLITCH_DURATION_MS) {
                        // Confirmed valid state change!
                        val durationMs = candidateStateTimestamp - lastStateChangeTimestamp
                        lastStateChangeTimestamp = candidateStateTimestamp
                        currentStateIsOn = detectedRawOn
                        candidateStateIsOn = null
                        _isLightOn.value = currentStateIsOn

                        if (currentStateIsOn) {
                            // OFF -> ON transition: previous period was a GAP
                            if (durationMs >= MIN_GLITCH_DURATION_MS) {
                                handleGap(durationMs)
                            }
                        } else {
                            // ON -> OFF transition: previous period was a PULSE
                            if (durationMs >= MIN_GLITCH_DURATION_MS) {
                                handlePulse(durationMs)
                            }
                        }
                    }
                }
            } else {
                // Detected state matches current state, reset any transient candidate
                candidateStateIsOn = null

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

    /**
     * Computes the pixel intensity (0..255) at the given cumulative percentile.
     */
    private fun getPercentileValue(sampleCount: Int, percentile: Float): Int {
        val targetCount = (sampleCount * percentile).toInt().coerceIn(1, sampleCount)
        var cumulative = 0
        for (i in 0..255) {
            cumulative += histogram[i]
            if (cumulative >= targetCount) {
                return i
            }
        }
        return 255
    }

    private fun handlePulse(durationMs: Long) {
        if (durationMs < MIN_GLITCH_DURATION_MS) return
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
        if (durationMs < MIN_GLITCH_DURATION_MS) return
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
        histogram.fill(0)
        ambientBaseline = 40.0f
        calibrationFramesCount = 0
        currentStateIsOn = false
        candidateStateIsOn = null
        lastStateChangeTimestamp = 0L
        currentSymbolBuffer.clear()
        decodedMessageBuilder.clear()
        _currentSymbols.value = ""
        _decodedText.value = ""
        _preambleDetected.value = false
        _rawLuminance.value = 0.0f
        _threshold.value = 0.0f
        _isLightOn.value = false
        _debugLog.value = "Receiver reset"
    }
}
