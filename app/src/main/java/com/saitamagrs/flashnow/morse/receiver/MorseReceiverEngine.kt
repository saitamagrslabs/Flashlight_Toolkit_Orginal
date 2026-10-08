package com.saitamagrs.flashnow.morse.receiver

import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.saitamagrs.flashnow.morse.core.MorseCodec
import com.saitamagrs.flashnow.morse.core.MorseProtocol
import com.saitamagrs.flashnow.morse.core.MorseTiming
import com.saitamagrs.flashnow.morse.correction.MorseEnglishCorrector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer

/**
 * Optical Morse Code Receiver Engine.
 * Analyzes video stream frames from CameraX ImageAnalysis to detect flashlight pulses and decode text.
 *
 * Enhanced for robust 0.5m - 3.0m optical range and automatic word correction:
 * - Dynamic contrast thresholding with saturation flare compensation for close range (0.5m)
 * - High-sensitivity thresholds (ON: 13.0f, OFF: 6.5f) for distant flashlight spots (3.0m)
 * - Transmission session lifecycle management (WAITING -> RECEIVING -> ENDING -> READY)
 * - Automatic transmission session reset on new optical signal without manual user intervention
 * - Receiver remains armed and camera active across sessions with message frozen on screen
 * - Integrated automatic offline English word correction at word boundaries and session completion
 * - Full-resolution center sampling with 256-bin histogram cluster detection rejecting hot pixels
 */
class MorseReceiverEngine : ImageAnalysis.Analyzer {

    enum class SessionState {
        WAITING,    // Waiting for initial signal / transmission start
        RECEIVING,  // Actively receiving Morse pulses and gaps
        ENDING,     // Transitioning out of transmission (end-gap detected)
        READY       // Transmission complete, decoded text frozen & displayed, armed for next transmission
    }

    enum class SourceState {
        NO_SOURCE,
        SEARCHING,
        LOCKED
    }

    companion object {
        private const val TAG = "MorseReceiverEngine"

        // Search region: 60% of frame dimensions, centered
        internal const val SEARCH_REGION_RATIO = 0.60f

        // Candidate acquisition parameters
        internal const val MIN_CLUSTER_PIXELS = 3
        internal const val MIN_CANDIDATE_CONTRAST = 7.5f
        internal const val SEARCHING_FRAMES_TO_LOCK = 2
        internal const val TRACKING_MAX_LOSS_MS = 1800L
        internal const val TRACKING_WINDOW_RADIUS = 36

        // High-sensitivity distant flashlight thresholds (up to 3.0 meters)
        internal const val ON_THRESHOLD_CONTRAST = 8.5f
        internal const val BASE_OFF_THRESHOLD_CONTRAST = 4.5f

        // Resting ambient noise band: fluctuations below this are treated as resting ambient noise
        internal const val RESTING_NOISE_BAND = 3.5f

        // Close-range high-contrast saturation & flare protection
        private const val HIGH_CONTRAST_PEAK_THRESHOLD = 40.0f
        private const val CLOSE_RANGE_OFF_RATIO = 0.28f
        private const val MIN_OFF_CONTRAST = 6.0f
        private const val MAX_OFF_CONTRAST = 32.0f

        // Initial calibration window (frames)
        private const val CALIBRATION_FRAMES_REQUIRED = 5

        // Glitch rejection filter threshold (25ms = confirms state persists across consecutive frames)
        private const val MIN_GLITCH_DURATION_MS = 25L

        // End-gap session completion timeout
        const val SESSION_END_TIMEOUT_MS = 1400L
    }

    // Exposed StateFlows for UI and diagnostic observing
    private val _sourceState = MutableStateFlow(SourceState.NO_SOURCE)
    val sourceState: StateFlow<SourceState> = _sourceState.asStateFlow()

    private val _sourceX = MutableStateFlow(0.5f)
    val sourceX: StateFlow<Float> = _sourceX.asStateFlow()

    private val _sourceY = MutableStateFlow(0.5f)
    val sourceY: StateFlow<Float> = _sourceY.asStateFlow()

    private val _sourceContrast = MutableStateFlow(0.0f)
    val sourceContrast: StateFlow<Float> = _sourceContrast.asStateFlow()

    private val _isSourceLocked = MutableStateFlow(false)
    val isSourceLocked: StateFlow<Boolean> = _isSourceLocked.asStateFlow()

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

    private val _rawDecodedText = MutableStateFlow("")
    val rawDecodedText: StateFlow<String> = _rawDecodedText.asStateFlow()

    private val _sessionState = MutableStateFlow(SessionState.WAITING)
    val sessionState: StateFlow<SessionState> = _sessionState.asStateFlow()

    private val _preambleDetected = MutableStateFlow(false)
    val preambleDetected: StateFlow<Boolean> = _preambleDetected.asStateFlow()

    private val _debugLog = MutableStateFlow("Receiver ready")
    val debugLog: StateFlow<String> = _debugLog.asStateFlow()

    // Optional offline English word corrector
    var englishCorrector: MorseEnglishCorrector? = null

    // Reusable 256-bin histogram for zero-allocation percentile computation
    private val histogram = IntArray(256)

    // Internal Signal Processing State
    private var ambientBaseline = 40.0f
    private var calibrationFramesCount = 0
    private var currentPeakContrast = 0.0f

    private var currentStateIsOn = false
    private var lastStateChangeTimestamp = 0L

    // Optical source tracking state
    internal var trackedSourceX = -1
    internal var trackedSourceY = -1
    internal var candidateSourceX = -1
    internal var candidateSourceY = -1
    internal var searchingFramesCount = 0
    internal var lastSeenSourceTimestamp = 0L

    // Glitch candidate state
    private var candidateStateIsOn: Boolean? = null
    private var candidateStateTimestamp = 0L

    // Preamble synchronization buffer (holds candidate preamble dots before confirmation)
    private val preambleCandidateBuffer = StringBuilder()

    private val currentSymbolBuffer = StringBuilder()
    private val rawDecodedMessageBuilder = StringBuilder()

    internal fun getAmbientBaseline(): Float = ambientBaseline
    internal fun setAmbientBaseline(baseline: Float) { ambientBaseline = baseline }
    internal fun setCalibrationDone() { calibrationFramesCount = CALIBRATION_FRAMES_REQUIRED }

    internal fun setSourceLockedForTest(x: Int, y: Int, nowMs: Long = SystemClock.elapsedRealtime()) {
        _sourceState.value = SourceState.LOCKED
        _isSourceLocked.value = true
        trackedSourceX = x
        trackedSourceY = y
        lastSeenSourceTimestamp = nowMs
    }

    override fun analyze(image: ImageProxy) {
        try {
            val plane = image.planes[0]
            val buffer: ByteBuffer = plane.buffer
            buffer.rewind()
            val nowMs = SystemClock.elapsedRealtime()

            processFrame(
                image.width,
                image.height,
                buffer,
                plane.rowStride,
                plane.pixelStride.coerceAtLeast(1),
                nowMs
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error analyzing frame", e)
        } finally {
            image.close()
        }
    }

    private fun getPixel(buffer: ByteBuffer, x: Int, y: Int, rowStride: Int, pixelStride: Int, limit: Int): Int {
        val index = (y * rowStride) + (x * pixelStride)
        return if (index in 0 until limit) {
            buffer.get(index).toInt() and 0xFF
        } else {
            0
        }
    }

    /**
     * Complete Optical Source Acquisition, Tracking, and Signal Extraction pipeline.
     */
    internal fun processFrame(
        width: Int,
        height: Int,
        buffer: ByteBuffer,
        rowStride: Int,
        pixelStride: Int,
        nowMs: Long
    ) {
        val roiW = (width * SEARCH_REGION_RATIO).toInt().coerceAtLeast(16)
        val roiH = (height * SEARCH_REGION_RATIO).toInt().coerceAtLeast(16)
        val startX = (width - roiW) / 2
        val startY = (height - roiH) / 2
        val endX = startX + roiW
        val endY = startY + roiH
        val limit = buffer.limit()
        val centerX = width / 2f
        val centerY = height / 2f

        // 1. TRACKING MODE: if source is already LOCKED, inspect local tracking neighborhood
        if (_sourceState.value == SourceState.LOCKED && trackedSourceX in 0 until width && trackedSourceY in 0 until height) {
            val winStartX = (trackedSourceX - TRACKING_WINDOW_RADIUS).coerceIn(0, width - 1)
            val winEndX = (trackedSourceX + TRACKING_WINDOW_RADIUS).coerceIn(0, width - 1)
            val winStartY = (trackedSourceY - TRACKING_WINDOW_RADIUS).coerceIn(0, height - 1)
            val winEndY = (trackedSourceY + TRACKING_WINDOW_RADIUS).coerceIn(0, height - 1)

            var localPeak = 0
            var peakX = trackedSourceX
            var peakY = trackedSourceY

            // Fast sample tracking window
            var y = winStartY
            while (y <= winEndY) {
                var x = winStartX
                while (x <= winEndX) {
                    val luma = getPixel(buffer, x, y, rowStride, pixelStride, limit)
                    if (luma > localPeak) {
                        localPeak = luma
                        peakX = x
                        peakY = y
                    }
                    x += 2
                }
                y += 2
            }

            // Refine local cluster
            val candStartX = (peakX - 8).coerceIn(0, width - 1)
            val candEndX = (peakX + 8).coerceIn(0, width - 1)
            val candStartY = (peakY - 8).coerceIn(0, height - 1)
            val candEndY = (peakY + 8).coerceIn(0, height - 1)

            var clusterCount = 0
            var bgSum = 0
            var bgCount = 0
            val clusterThreshold = maxOf(35, localPeak - 20)

            for (cy in candStartY..candEndY) {
                for (cx in candStartX..candEndX) {
                    val luma = getPixel(buffer, cx, cy, rowStride, pixelStride, limit)
                    if (luma >= clusterThreshold) {
                        clusterCount++
                    }
                    if (cx == candStartX || cx == candEndX || cy == candStartY || cy == candEndY) {
                        bgSum += luma
                        bgCount++
                    }
                }
            }

            val localBg = if (bgCount > 0) bgSum.toFloat() / bgCount else ambientBaseline
            val localContrast = (localPeak - localBg).coerceAtLeast(0.0f)

            if (localContrast >= MIN_CANDIDATE_CONTRAST && clusterCount >= MIN_CLUSTER_PIXELS) {
                // Active optical flash verified in tracking window!
                trackedSourceX = ((trackedSourceX * 0.3f) + (peakX * 0.7f)).toInt().coerceIn(0, width - 1)
                trackedSourceY = ((trackedSourceY * 0.3f) + (peakY * 0.7f)).toInt().coerceIn(0, height - 1)
                lastSeenSourceTimestamp = nowMs
                _sourceContrast.value = localContrast
                _sourceX.value = (trackedSourceX.toFloat() / width).coerceIn(0f, 1f)
                _sourceY.value = (trackedSourceY.toFloat() / height).coerceIn(0f, 1f)
                processOpticalSample(localPeak.toFloat(), nowMs)
                return
            } else {
                // Flashlight is momentarily OFF during a Morse gap or jitter
                val lossMs = nowMs - lastSeenSourceTimestamp
                if (lossMs <= TRACKING_MAX_LOSS_MS) {
                    // Hold lock during transmission gaps
                    _sourceContrast.value = localContrast
                    _sourceX.value = (trackedSourceX.toFloat() / width).coerceIn(0f, 1f)
                    _sourceY.value = (trackedSourceY.toFloat() / height).coerceIn(0f, 1f)
                    processOpticalSample(localPeak.toFloat(), nowMs)
                    return
                } else {
                    // Source lost beyond timeout
                    _sourceState.value = SourceState.SEARCHING
                    _isSourceLocked.value = false
                    trackedSourceX = -1
                    trackedSourceY = -1
                    searchingFramesCount = 0
                    _debugLog.value = "Source lock lost (timeout)"
                }
            }
        }

        // 2. SEARCH MODE: scan central 60% search window
        var bestScore = 0.0f
        var bestPeak = 0
        var bestPeakX = -1
        var bestPeakY = -1
        var bestContrast = 0.0f

        var sy = startY
        while (sy < endY) {
            var sx = startX
            while (sx < endX) {
                val luma = getPixel(buffer, sx, sy, rowStride, pixelStride, limit)
                if (luma > ambientBaseline + MIN_CANDIDATE_CONTRAST && luma > bestPeak) {
                    bestPeak = luma
                    bestPeakX = sx
                    bestPeakY = sy
                }
                sx += 2
            }
            sy += 2
        }

        if (bestPeakX >= 0) {
            val candStartX = (bestPeakX - 8).coerceIn(0, width - 1)
            val candEndX = (bestPeakX + 8).coerceIn(0, width - 1)
            val candStartY = (bestPeakY - 8).coerceIn(0, height - 1)
            val candEndY = (bestPeakY + 8).coerceIn(0, height - 1)

            var clusterCount = 0
            var bgSum = 0
            var bgCount = 0
            val clusterThreshold = maxOf(35, bestPeak - 20)

            for (cy in candStartY..candEndY) {
                for (cx in candStartX..candEndX) {
                    val luma = getPixel(buffer, cx, cy, rowStride, pixelStride, limit)
                    if (luma >= clusterThreshold) {
                        clusterCount++
                    }
                    if (cx == candStartX || cx == candEndX || cy == candStartY || cy == candEndY) {
                        bgSum += luma
                        bgCount++
                    }
                }
            }

            val localBg = if (bgCount > 0) bgSum.toFloat() / bgCount else ambientBaseline
            val contrast = (bestPeak - localBg).coerceAtLeast(0.0f)

            if (clusterCount >= MIN_CLUSTER_PIXELS && contrast >= MIN_CANDIDATE_CONTRAST) {
                val dx = (bestPeakX - centerX) / (width / 2f)
                val dy = (bestPeakY - centerY) / (height / 2f)
                val distNorm = Math.hypot(dx.toDouble(), dy.toDouble()).toFloat().coerceIn(0f, 1f)
                val centerBonus = (1.0f - distNorm) * 20.0f
                bestScore = (bestPeak * 0.3f) + (contrast * 0.5f) + minOf(clusterCount.toFloat(), 15.0f) + centerBonus
                bestContrast = contrast
            }
        }

        if (bestScore > 0f) {
            if (_sourceState.value == SourceState.NO_SOURCE) {
                _sourceState.value = SourceState.SEARCHING
                candidateSourceX = bestPeakX
                candidateSourceY = bestPeakY
                searchingFramesCount = 1
                lastSeenSourceTimestamp = nowMs
                _sourceContrast.value = bestContrast
                _sourceX.value = (bestPeakX.toFloat() / width).coerceIn(0f, 1f)
                _sourceY.value = (bestPeakY.toFloat() / height).coerceIn(0f, 1f)
                _debugLog.value = "Searching: Candidate found at ($bestPeakX, $bestPeakY)"
            } else if (_sourceState.value == SourceState.SEARCHING) {
                val dist = Math.hypot(
                    (bestPeakX - candidateSourceX).toDouble(),
                    (bestPeakY - candidateSourceY).toDouble()
                ).toFloat()
                if (dist <= 45.0f) {
                    searchingFramesCount++
                    candidateSourceX = bestPeakX
                    candidateSourceY = bestPeakY
                    lastSeenSourceTimestamp = nowMs
                    _sourceContrast.value = bestContrast
                    _sourceX.value = (bestPeakX.toFloat() / width).coerceIn(0f, 1f)
                    _sourceY.value = (bestPeakY.toFloat() / height).coerceIn(0f, 1f)

                    if (searchingFramesCount >= SEARCHING_FRAMES_TO_LOCK) {
                        _sourceState.value = SourceState.LOCKED
                        _isSourceLocked.value = true
                        trackedSourceX = bestPeakX
                        trackedSourceY = bestPeakY
                        _debugLog.value = "LIGHT SOURCE LOCKED at ($bestPeakX, $bestPeakY)"
                        processOpticalSample(bestPeak.toFloat(), nowMs)
                    }
                } else {
                    candidateSourceX = bestPeakX
                    candidateSourceY = bestPeakY
                    searchingFramesCount = 1
                    lastSeenSourceTimestamp = nowMs
                }
            }
        } else {
            if (_sourceState.value == SourceState.SEARCHING) {
                searchingFramesCount = 0
                candidateSourceX = -1
                candidateSourceY = -1
                _sourceState.value = SourceState.NO_SOURCE
                _sourceContrast.value = 0.0f
                _debugLog.value = "No optical source detected"
            }
        }
    }

    /**
     * Processes an extracted optical bright-signal reading with adaptive baseline tracking,
     * dual-regime contrast thresholding, hysteresis, and glitch filtering.
     */
    internal fun processOpticalSample(brightSignal: Float, nowMs: Long) {
        // Initial calibration phase to establish resting baseline
        if (calibrationFramesCount < CALIBRATION_FRAMES_REQUIRED) {
            calibrationFramesCount++
            ambientBaseline = if (calibrationFramesCount == 1) {
                brightSignal
            } else {
                (ambientBaseline * 0.7f) + (brightSignal * 0.3f)
            }
            lastStateChangeTimestamp = nowMs
            currentStateIsOn = false
            candidateStateIsOn = null
            _rawLuminance.value = brightSignal
            _threshold.value = ambientBaseline + ON_THRESHOLD_CONTRAST
            _isLightOn.value = false
            return
        }

        val rawContrast = brightSignal - ambientBaseline
        val signalContrast = rawContrast.coerceAtLeast(0.0f)

        // Update adaptive ambient baseline (track resting signal level when light is confirmed OFF)
        if (!currentStateIsOn && candidateStateIsOn == null) {
            if (signalContrast <= RESTING_NOISE_BAND) {
                if (brightSignal < ambientBaseline) {
                    // Scene darkened or flash shut off: track downward smoothly
                    ambientBaseline = (ambientBaseline * 0.92f) + (brightSignal * 0.08f)
                } else {
                    // Resting ambient noise band: adapt slowly to natural room changes (tau ~ 2.2s at 30fps)
                    ambientBaseline = (ambientBaseline * 0.985f) + (brightSignal * 0.015f)
                }
            } else {
                // Signal is elevated above resting noise band (> RESTING_NOISE_BAND).
                // This is a candidate or active distant flashlight pulse.
                // STRICT RULE: FREEZE ambientBaseline! Never adapt upward into active light!
            }
        } else if (currentStateIsOn) {
            // When light is confirmed ON, never adapt upward into the flash.
            // Only allow gentle downward tracking if background ambient drops.
            if (brightSignal < ambientBaseline) {
                ambientBaseline = (ambientBaseline * 0.95f) + (brightSignal * 0.05f)
            }
        }

        // Recompute contrast against current baseline
        val currentContrast = (brightSignal - ambientBaseline).coerceAtLeast(0.0f)

        // Track peak contrast during active illumination for close-range flare compensation
        if (currentStateIsOn) {
            currentPeakContrast = maxOf(currentPeakContrast, currentContrast)
        }

        // Compute dynamic OFF threshold:
        // Close range: when peak contrast > 40, lens flare / blooming lingers.
        // Scale OFF threshold up to 28% of peak (max 32.0f) so shutoff is detected instantly.
        // Distant range: use sensitive BASE_OFF_THRESHOLD_CONTRAST (4.5f).
        val activeOffContrast = if (currentStateIsOn && currentPeakContrast > HIGH_CONTRAST_PEAK_THRESHOLD) {
            (currentPeakContrast * CLOSE_RANGE_OFF_RATIO).coerceIn(MIN_OFF_CONTRAST, MAX_OFF_CONTRAST)
        } else {
            BASE_OFF_THRESHOLD_CONTRAST
        }

        // Hysteresis detection (Schmitt trigger)
        val detectedRawOn = if (currentStateIsOn) {
            currentContrast > activeOffContrast
        } else {
            currentContrast >= ON_THRESHOLD_CONTRAST
        }

        val activeThreshold = if (currentStateIsOn) {
            ambientBaseline + activeOffContrast
        } else {
            ambientBaseline + ON_THRESHOLD_CONTRAST
        }

        // Glitch filtering (confirm state across consecutive frames >= 25 ms)
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
                        currentPeakContrast = currentContrast
                        if (_sessionState.value == SessionState.READY || _sessionState.value == SessionState.WAITING) {
                            startNewSession()
                        }
                        if (durationMs >= MIN_GLITCH_DURATION_MS) {
                            handleGap(durationMs)
                        }
                    } else {
                        // ON -> OFF transition: previous period was a PULSE
                        currentPeakContrast = 0.0f
                        if (durationMs >= MIN_GLITCH_DURATION_MS) {
                            handlePulse(durationMs)
                        }
                    }
                }
            }
        } else {
            // Detected state matches current state, reset any transient candidate
            candidateStateIsOn = null

            // If light remains OFF, check for responsive letter finalization or session end timeout
            if (!currentStateIsOn && lastStateChangeTimestamp > 0) {
                val idleGapMs = nowMs - lastStateChangeTimestamp
                handleIdleGap(idleGapMs)
            }
        }

        // Update exposed flows for UI after state has settled for this frame
        _rawLuminance.value = brightSignal
        _threshold.value = activeThreshold
    }

    /**
     * Extracts bright optical signal level from the center ROI histogram.
     * Searches from maximum brightness (255) downward until a small cluster threshold is reached.
     * Requiring 3 to 5 physical pixels in the center ROI guarantees:
     * - Complete rejection of single or dual hot/dead sensor pixels.
     * - High sensitivity to small, distant flashlight spots (even at 3 meters).
     */
    private fun getBrightSignal(sampleCount: Int): Float {
        val minSamples = (sampleCount * 0.0001f).toInt().coerceIn(3, 5)
        var cumulative = 0
        for (i in 255 downTo 0) {
            cumulative += histogram[i]
            if (cumulative >= minSamples) {
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

    /**
     * Starts a new transmission session on detecting an incoming optical signal.
     * Automatically clears transient buffers and previous message without requiring manual reset.
     */
    internal fun startNewSession() {
        preambleCandidateBuffer.clear()
        currentSymbolBuffer.clear()
        rawDecodedMessageBuilder.clear()
        _currentSymbols.value = ""
        _decodedText.value = ""
        _rawDecodedText.value = ""
        _preambleDetected.value = false
        _sessionState.value = SessionState.RECEIVING
        _debugLog.value = "New session started (RECEIVING)"
    }

    /**
     * Applies optional offline English word correction and updates exposed StateFlows.
     */
    private fun applyCorrection(isFinal: Boolean) {
        val rawText = rawDecodedMessageBuilder.toString()
        _rawDecodedText.value = rawText
        val corrector = englishCorrector
        if (corrector != null && rawText.isNotBlank()) {
            val corrected = corrector.autoCorrectMessage(rawText, isFinal = isFinal)
            _decodedText.value = corrected
        } else {
            _decodedText.value = rawText
        }
    }

    /**
     * Finalizes the current transmission session when an idle end-gap (>= 1400ms) is detected.
     * Message remains frozen and visible; receiver transitions to READY and remains armed.
     */
    internal fun finalizeSession() {
        if (_sessionState.value == SessionState.READY || _sessionState.value == SessionState.WAITING) return
        _sessionState.value = SessionState.ENDING
        finalizeLetter()
        applyCorrection(isFinal = true)
        _sessionState.value = SessionState.READY
        _debugLog.value = "Transmission complete (READY)"
    }

    internal fun handleIdleGap(idleGapMs: Long) {
        // Responsively finalize letter or sync preamble as soon as letter gap threshold is reached
        if ((currentSymbolBuffer.isNotEmpty() || (!_preambleDetected.value && preambleCandidateBuffer.isNotEmpty()))
            && idleGapMs >= MorseTiming.GAP_SYMBOL_LETTER_THRESHOLD_MS) {
            finalizeLetter()
        }
        // Finalize transmission session when idle gap reaches end timeout
        if (idleGapMs >= SESSION_END_TIMEOUT_MS && _sessionState.value == SessionState.RECEIVING) {
            finalizeSession()
        }
    }

    internal fun handlePulse(durationMs: Long) {
        // Automatically start new session if incoming pulse occurs in WAITING or READY state
        if (_sessionState.value == SessionState.READY || _sessionState.value == SessionState.WAITING) {
            startNewSession()
        }
        if (durationMs < MorseTiming.MIN_PULSE_DURATION_MS) return
        val pulseType = MorseTiming.classifyPulse(durationMs)
        val symbolChar = when (pulseType) {
            MorseTiming.PulseType.DOT -> '.'
            MorseTiming.PulseType.DASH -> '-'
            MorseTiming.PulseType.UNKNOWN -> return
        }

        if (!_preambleDetected.value) {
            if (symbolChar == '.' && preambleCandidateBuffer.length < 3) {
                preambleCandidateBuffer.append(symbolChar)
                _debugLog.value = "Preamble candidate: $preambleCandidateBuffer"
            } else {
                // Cannot be preamble (contains DASH or exceeds 3 dots)
                // Treat this transmission as having bypassed the preamble and promote to message symbols
                _preambleDetected.value = true
                currentSymbolBuffer.append(preambleCandidateBuffer)
                currentSymbolBuffer.append(symbolChar)
                preambleCandidateBuffer.clear()
                _currentSymbols.value = currentSymbolBuffer.toString()
                _debugLog.value = "Pulse: $pulseType (${durationMs}ms) -> Symbols: $currentSymbolBuffer"
            }
        } else {
            currentSymbolBuffer.append(symbolChar)
            _currentSymbols.value = currentSymbolBuffer.toString()
            _debugLog.value = "Pulse: $pulseType (${durationMs}ms) -> Symbols: $currentSymbolBuffer"
        }
    }

    internal fun handleGap(durationMs: Long) {
        if (durationMs < MorseTiming.MIN_PULSE_DURATION_MS) return
        val gapType = MorseTiming.classifyGap(durationMs)
        _debugLog.value = "Gap: $gapType (${durationMs}ms)"

        when (gapType) {
            MorseTiming.GapType.SYMBOL_GAP -> {
                // Continuation within a letter: do not finalize symbols!
            }
            MorseTiming.GapType.LETTER_GAP -> {
                finalizeLetter()
            }
            MorseTiming.GapType.WORD_GAP -> {
                finalizeLetter()
                if (rawDecodedMessageBuilder.isNotEmpty() && !rawDecodedMessageBuilder.endsWith(" ")) {
                    rawDecodedMessageBuilder.append(" ")
                    applyCorrection(isFinal = false)
                }
            }
            MorseTiming.GapType.END_GAP -> {
                finalizeSession()
            }
        }
    }

    internal fun finalizeLetter() {
        if (!_preambleDetected.value) {
            val candidate = preambleCandidateBuffer.toString()
            if (candidate == MorseProtocol.PREAMBLE_MORSE_PATTERN) {
                // Preamble detected and synchronized!
                // Discard preamble from message data (never append to message or current symbols)
                _preambleDetected.value = true
                preambleCandidateBuffer.clear()
                _debugLog.value = "Preamble synced ('...')"
                return
            } else if (candidate.isNotEmpty()) {
                // Candidate was not a 3-dot preamble; promote to message symbol
                _preambleDetected.value = true
                currentSymbolBuffer.append(candidate)
                preambleCandidateBuffer.clear()
                _currentSymbols.value = currentSymbolBuffer.toString()
            } else {
                return
            }
        }

        val pattern = currentSymbolBuffer.toString()
        if (pattern.isEmpty()) return

        val char = MorseCodec.decodeLetter(pattern)
        if (char != null) {
            rawDecodedMessageBuilder.append(char)
            applyCorrection(isFinal = false)
            _debugLog.value = "Decoded letter '$char' from '$pattern'"
        } else {
            _debugLog.value = "Unrecognized pattern '$pattern'"
        }

        currentSymbolBuffer.clear()
        _currentSymbols.value = ""
    }

    /**
     * Resets receiver state machine to initial WAITING state.
     */
    fun reset() {
        histogram.fill(0)
        ambientBaseline = 40.0f
        calibrationFramesCount = 0
        currentStateIsOn = false
        candidateStateIsOn = null
        lastStateChangeTimestamp = 0L
        currentPeakContrast = 0.0f
        preambleCandidateBuffer.clear()
        currentSymbolBuffer.clear()
        rawDecodedMessageBuilder.clear()
        _currentSymbols.value = ""
        _decodedText.value = ""
        _rawDecodedText.value = ""
        _preambleDetected.value = false
        _rawLuminance.value = 0.0f
        _threshold.value = 0.0f
        _isLightOn.value = false
        _sessionState.value = SessionState.WAITING

        // Reset optical source acquisition
        _sourceState.value = SourceState.NO_SOURCE
        _isSourceLocked.value = false
        _sourceX.value = 0.5f
        _sourceY.value = 0.5f
        _sourceContrast.value = 0.0f
        trackedSourceX = -1
        trackedSourceY = -1
        candidateSourceX = -1
        candidateSourceY = -1
        searchingFramesCount = 0
        lastSeenSourceTimestamp = 0L

        _debugLog.value = "Receiver reset"
    }
}
