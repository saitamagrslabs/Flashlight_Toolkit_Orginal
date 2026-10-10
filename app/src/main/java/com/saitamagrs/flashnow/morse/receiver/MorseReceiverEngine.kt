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
 * Restored to verified Phase 8.0H baseline (commit a784d0e) with Phase 8.3 Optical Detection Fix:
 * - Center-focused Region of Interest (ROI) matching on-screen reticle
 * - Small-cluster downward search (3-6 pixels) to detect genuine flashlight beams at 0.5m - 2.0m+ range
 * - Single-pixel hot noise rejection (isolated 1-2 pixel anomalies ignored)
 * - 40th percentile adaptive ambient baseline
 * - Hysteresis thresholding (Schmitt trigger) with close-range lens flare & saturation protection
 * - 45ms candidate glitch filter rejecting transient camera noise
 * - Direct letter finalization and decoded message accumulation
 * - Minimal, non-intrusive MorseRxDiag logging
 */
class MorseReceiverEngine : ImageAnalysis.Analyzer {

    companion object {
        private const val TAG = "MorseReceiverEngine"
        private const val DIAG_TAG = "MorseRxDiag"

        // Center ROI definition (28% of frame dimensions, centered)
        private const val ROI_RATIO = 0.28f

        // Percentile for robust ambient baseline calculation
        private const val AMBIENT_PERCENTILE = 0.40f // 40th percentile = robust ambient baseline

        // Adaptive contrast thresholds with hysteresis (Schmitt trigger) for 0.5m - 2.0m+ range
        internal const val ON_THRESHOLD_CONTRAST = 15.0f
        internal const val OFF_THRESHOLD_CONTRAST = 8.0f

        // Close-range high-contrast saturation & lens flare protection
        private const val HIGH_CONTRAST_PEAK_THRESHOLD = 40.0f
        private const val CLOSE_RANGE_OFF_RATIO = 0.28f
        private const val MAX_OFF_THRESHOLD_CONTRAST = 28.0f

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
    private var currentPeakContrast = 0.0f
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

            // 2. Compute 40th percentile (ambient) and cluster bright signal
            val localAmbient = getPercentileValue(sampleCount, AMBIENT_PERCENTILE)
            val brightSignal = getBrightSignal(sampleCount)

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
                currentPeakContrast = 0.0f
                candidateStateIsOn = null
                _rawLuminance.value = brightSignal
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
            _rawLuminance.value = brightSignal

            // Dynamic OFF contrast threshold for close-range lens flare / saturation protection
            if (currentStateIsOn) {
                if (signalContrast > currentPeakContrast) {
                    currentPeakContrast = signalContrast
                }
            }

            val activeOffContrast = if (currentStateIsOn && currentPeakContrast > HIGH_CONTRAST_PEAK_THRESHOLD) {
                (currentPeakContrast * CLOSE_RANGE_OFF_RATIO).coerceIn(OFF_THRESHOLD_CONTRAST, MAX_OFF_THRESHOLD_CONTRAST)
            } else {
                OFF_THRESHOLD_CONTRAST
            }

            // 5. Hysteresis detection (Schmitt trigger)
            val detectedRawOn = if (currentStateIsOn) {
                signalContrast > activeOffContrast
            } else {
                signalContrast >= ON_THRESHOLD_CONTRAST
            }

            val activeThreshold = if (currentStateIsOn) {
                ambientBaseline + activeOffContrast
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
                            currentPeakContrast = signalContrast
                            Log.d(DIAG_TAG, "TRANSITION: state=ON, durationMs=$durationMs")
                            if (durationMs >= MIN_GLITCH_DURATION_MS) {
                                handleGap(durationMs)
                            }
                        } else {
                            // ON -> OFF transition: previous period was a PULSE
                            currentPeakContrast = 0.0f
                            Log.d(DIAG_TAG, "TRANSITION: state=OFF, durationMs=$durationMs")
                            if (durationMs >= MIN_GLITCH_DURATION_MS) {
                                handlePulse(durationMs)
                            }
                        }
                    }
                }
            } else {
                // Detected state matches current state, reset any transient candidate
                candidateStateIsOn = null

                // If light remains OFF, check for responsive letter finalization or frame timeout
                if (!currentStateIsOn && lastStateChangeTimestamp > 0) {
                    val idleGapMs = nowMs - lastStateChangeTimestamp
                    handleIdleGap(idleGapMs)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error analyzing frame", e)
        } finally {
            image.close()
        }
    }

    /**
     * Finds the brightest pixel level supported by a small cluster of pixels.
     * Searches top-down from bin 255 to bin 0 until cumulative count reaches minClusterSamples.
     * Filters isolated hot pixels (1-2 pixels) while capturing genuine flashlight spot (3-6 pixels at 0.5m-1.5m).
     */
    internal fun getBrightSignal(sampleCount: Int, customHistogram: IntArray = histogram): Float {
        val minClusterSamples = (sampleCount * 0.0006f).toInt().coerceIn(3, 6)
        var cumulative = 0
        for (i in 255 downTo 0) {
            cumulative += customHistogram[i]
            if (cumulative >= minClusterSamples) {
                return i.toFloat()
            }
        }
        return 0.0f
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

    internal fun handleIdleGap(idleGapMs: Long) {
        // Responsively finalize letter as soon as letter gap threshold is reached
        if (currentSymbolBuffer.isNotEmpty() && idleGapMs >= MorseTiming.LETTER_GAP_MS) {
            finalizeLetter()
        }
        if (idleGapMs >= MorseTiming.END_GAP_MS && currentSymbolBuffer.isEmpty() && !_debugLog.value.endsWith("(Timeout)")) {
            _debugLog.value = "Frame ended (Timeout)"
        }
    }

    internal fun handlePulse(durationMs: Long) {
        if (durationMs < MIN_GLITCH_DURATION_MS) return
        val pulseType = MorseTiming.classifyPulse(durationMs)
        Log.d(DIAG_TAG, "PULSE: durationMs=$durationMs -> $pulseType")
        when (pulseType) {
            MorseTiming.PulseType.DOT -> currentSymbolBuffer.append('.')
            MorseTiming.PulseType.DASH -> currentSymbolBuffer.append('-')
            MorseTiming.PulseType.UNKNOWN -> return
        }
        _currentSymbols.value = currentSymbolBuffer.toString()
        _debugLog.value = "Pulse: $pulseType (${durationMs}ms) -> Symbols: $currentSymbolBuffer"
    }

    internal fun handleGap(durationMs: Long) {
        if (durationMs < MIN_GLITCH_DURATION_MS) return
        val gapType = MorseTiming.classifyGap(durationMs)
        Log.d(DIAG_TAG, "GAP: durationMs=$durationMs -> $gapType")
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

    internal fun finalizeLetter() {
        val pattern = currentSymbolBuffer.toString()
        if (pattern.isEmpty()) return

        // Opportunistically mark protocol preamble sync without discarding legitimate letters
        if (pattern == MorseProtocol.PREAMBLE_MORSE_PATTERN && !_preambleDetected.value) {
            _preambleDetected.value = true
            _debugLog.value = "Preamble synced ('...')"
            Log.d(DIAG_TAG, "PREAMBLE: synced ('...')")
        }

        val char = MorseCodec.decodeLetter(pattern)
        if (char != null) {
            decodedMessageBuilder.append(char)
            _decodedText.value = decodedMessageBuilder.toString()
            _debugLog.value = "Decoded letter '$char' from '$pattern'"
            Log.d(DIAG_TAG, "LETTER_FINALIZE: pattern='$pattern', decoded='$char'")
        } else {
            _debugLog.value = "Unrecognized pattern '$pattern'"
            Log.d(DIAG_TAG, "LETTER_FINALIZE: pattern='$pattern', rejectReason=UNRECOGNIZED_MORSE_PATTERN")
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
        currentPeakContrast = 0.0f
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
        Log.d(DIAG_TAG, "SESSION: RESET, buffersCleared=true")
    }
}
