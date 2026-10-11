package com.saitamagrs.flashnow.morse.receiver

import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer

/**
 * Photometric and operational diagnostics emitted on each camera frame.
 */
data class ReceiverDiagnostics(
    val frameWidth: Int = 0,
    val frameHeight: Int = 0,
    val frameIntervalMs: Long = 0L,
    val processDurationMs: Long = 0L,
    val frameMeanLuma: Float = 0f,
    val frameMaxLuma: Int = 0,
    val centerMeanLuma: Float = 0f,
    val centerMaxLuma: Int = 0,
    val roiMeanLuma: Float = 0f,
    val roiMaxLuma: Int = 0,
    val localBgLuma: Float = 0f,
    val candidateLuma: Int = 0,
    val candidateContrast: Float = 0f,
    val signalDelta: Float = 0f,
    val restingContrast: Float = 0f,
    val ambientBaseline: Float = 0f,
    val onThreshold: Float = 0f,
    val offThreshold: Float = 0f,
    val isLightOn: Boolean = false,
    val transitionCount: Int = 0,
    val sourceState: MorseReceiverEngine.SourceState = MorseReceiverEngine.SourceState.NO_SOURCE,
    val receiverSignalState: MorseReceiverEngine.ReceiverSignalState = MorseReceiverEngine.ReceiverSignalState.CALIBRATING
)

/**
 * Optical Morse Code Receiver Engine.
 *
 * Clean architectural rebuild based on MorseLight's brightness-transition model:
 * 1. Fixed central reticle region of interest (30% ROI) where user aims at the sender.
 * 2. Explicit camera settling and calibration phase to establish resting ambient and noise floor.
 * 3. Temporal delta-from-baseline signal detection with asymmetric hysteresis.
 * 4. Streaming character decoder with preamble synchronization and real-time UI flow emission.
 */
class MorseReceiverEngine : ImageAnalysis.Analyzer {

    enum class SessionState {
        WAITING,    // Calibrating or waiting for initial optical signal
        RECEIVING,  // Actively receiving Morse pulses and gaps
        ENDING,     // Ending transmission
        READY       // Transmission complete, decoded text frozen & displayed, armed for next transmission
    }

    enum class SourceState {
        NO_SOURCE,
        SEARCHING,
        LOCKED
    }

    enum class ReceiverSignalState {
        CALIBRATING,    // Settling camera AE and measuring resting baseline
        WAITING,        // Armed and ready, waiting for sender optical flash
        FLASH_DETECTED, // Flashlight pulse actively detected inside reticle
        RECEIVING,      // Morse transmission in progress (pulses/gaps decoding)
        DECODED         // Message received and finalized, armed for next message
    }

    companion object {
        private const val TAG = "MorseReceiverEngine"
        const val SESSION_END_TIMEOUT_MS = 1400L
    }

    var englishCorrector: Any? = null

    // Subsystems
    val brightnessAnalyzer = OpticalBrightnessAnalyzer(0.30f, 0.30f)
    val decoder = MorseStreamDecoder(
        onSymbolsUpdated = { symbols -> _currentSymbols.value = symbols },
        onMessageUpdated = { msg ->
            _decodedText.value = msg
            _rawDecodedText.value = msg
        },
        onPreambleDetected = { synced -> _preambleDetected.value = synced },
        onSessionCompleted = { finalizeSession() }
    )

    val signalDetector = OpticalSignalDetector(
        onPulseCallback = { durationMs ->
            if (_sessionState.value == SessionState.READY || _sessionState.value == SessionState.WAITING) {
                startNewSession()
            }
            decoder.handlePulse(durationMs)
        },
        onGapCallback = { durationMs ->
            decoder.handleGap(durationMs)
        },
        onIdleGapCallback = { idleMs ->
            decoder.handleIdleGap(idleMs)
        },
        onStateChangeCallback = { isLightOn ->
            _isLightOn.value = isLightOn
        }
    )

    // Observable StateFlows for UI and diagnostics
    private val _sourceState = MutableStateFlow(SourceState.NO_SOURCE)
    val sourceState: StateFlow<SourceState> = _sourceState.asStateFlow()

    private val _receiverSignalState = MutableStateFlow(ReceiverSignalState.CALIBRATING)
    val receiverSignalState: StateFlow<ReceiverSignalState> = _receiverSignalState.asStateFlow()

    private val _sourceX = MutableStateFlow(0.5f)
    val sourceX: StateFlow<Float> = _sourceX.asStateFlow()

    private val _sourceY = MutableStateFlow(0.5f)
    val sourceY: StateFlow<Float> = _sourceY.asStateFlow()

    private val _sourceContrast = MutableStateFlow(0.0f)
    val sourceContrast: StateFlow<Float> = _sourceContrast.asStateFlow()

    private val _signalDelta = MutableStateFlow(0.0f)
    val signalDelta: StateFlow<Float> = _signalDelta.asStateFlow()

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

    private val _diagnostics = MutableStateFlow(ReceiverDiagnostics())
    val diagnostics: StateFlow<ReceiverDiagnostics> = _diagnostics.asStateFlow()

    private var lastFrameTimestampMs = 0L

    fun getAmbientBaseline(): Float = signalDetector.ambientBaseline
    fun setAmbientBaseline(baseline: Float) {
        signalDetector.ambientBaseline = baseline
        signalDetector.restingSpot = baseline
        signalDetector.restingMean = baseline
        signalDetector.restingContrast = 0.0f
    }
    fun setCalibrationDone() { signalDetector.setCalibrationDone() }
    fun isCalibrationDone(): Boolean = signalDetector.isCalibrated

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

    /**
     * Complete frame analysis and optical signal processing pipeline.
     */
    fun processFrame(
        width: Int,
        height: Int,
        buffer: ByteBuffer,
        rowStride: Int,
        pixelStride: Int,
        nowMs: Long
    ) {
        val startNano = System.nanoTime()
        val frameInterval = if (lastFrameTimestampMs > 0L) nowMs - lastFrameTimestampMs else 0L
        lastFrameTimestampMs = nowMs

        val sample = brightnessAnalyzer.analyze(buffer, width, height, rowStride, pixelStride)

        signalDetector.processSample(
            contrast = sample.contrast,
            brightSignal = sample.peakLuma,
            nowMs = nowMs,
            backgroundEstimate = sample.medianLuma,
            meanLuma = sample.meanLuma
        )

        val procDuration = (System.nanoTime() - startNano) / 1_000_000L

        // Update high-level receiver state and source state
        val currentReceiverState = when {
            !signalDetector.isCalibrated -> ReceiverSignalState.CALIBRATING
            _sessionState.value == SessionState.READY -> ReceiverSignalState.DECODED
            signalDetector.isLightOn -> ReceiverSignalState.FLASH_DETECTED
            _sessionState.value == SessionState.RECEIVING -> ReceiverSignalState.RECEIVING
            else -> ReceiverSignalState.WAITING
        }
        _receiverSignalState.value = currentReceiverState

        val currentSourceState = when (currentReceiverState) {
            ReceiverSignalState.CALIBRATING -> {
                _isSourceLocked.value = false
                SourceState.SEARCHING
            }
            ReceiverSignalState.WAITING -> {
                _isSourceLocked.value = false
                SourceState.SEARCHING
            }
            ReceiverSignalState.FLASH_DETECTED,
            ReceiverSignalState.RECEIVING,
            ReceiverSignalState.DECODED -> {
                _isSourceLocked.value = true
                SourceState.LOCKED
            }
        }
        _sourceState.value = currentSourceState
        _sourceContrast.value = sample.contrast
        _signalDelta.value = signalDetector.lastTemporalSignal
        _rawLuminance.value = sample.peakLuma
        _threshold.value = if (signalDetector.isLightOn) signalDetector.offThreshold else signalDetector.onThreshold

        _diagnostics.value = ReceiverDiagnostics(
            frameWidth = width,
            frameHeight = height,
            frameIntervalMs = frameInterval,
            processDurationMs = procDuration,
            frameMeanLuma = sample.meanLuma,
            frameMaxLuma = sample.peakLuma.toInt(),
            centerMeanLuma = sample.meanLuma,
            centerMaxLuma = sample.peakLuma.toInt(),
            roiMeanLuma = sample.medianLuma,
            roiMaxLuma = sample.peakLuma.toInt(),
            localBgLuma = sample.medianLuma,
            candidateLuma = sample.peakLuma.toInt(),
            candidateContrast = sample.contrast,
            signalDelta = signalDetector.lastTemporalSignal,
            restingContrast = signalDetector.restingContrast,
            ambientBaseline = signalDetector.ambientBaseline,
            onThreshold = signalDetector.onThreshold,
            offThreshold = signalDetector.offThreshold,
            isLightOn = signalDetector.isLightOn,
            transitionCount = signalDetector.transitionCount,
            sourceState = currentSourceState,
            receiverSignalState = currentReceiverState
        )
    }

    /**
     * Direct optical sample processing for programmatic / test inputs.
     */
    fun processOpticalSample(
        brightSignal: Float,
        nowMs: Long,
        backgroundEstimate: Float = signalDetector.ambientBaseline,
        contrast: Float = (brightSignal - backgroundEstimate).coerceAtLeast(0f),
        meanLuma: Float = backgroundEstimate
    ) {
        signalDetector.processSample(
            contrast = contrast,
            brightSignal = brightSignal,
            nowMs = nowMs,
            backgroundEstimate = backgroundEstimate,
            meanLuma = meanLuma
        )

        _rawLuminance.value = brightSignal
        _threshold.value = signalDetector.onThreshold
        _isLightOn.value = signalDetector.isLightOn
        _sourceContrast.value = contrast
        _signalDelta.value = signalDetector.lastTemporalSignal
    }

    fun startNewSession() {
        decoder.reset()
        _currentSymbols.value = ""
        _decodedText.value = ""
        _rawDecodedText.value = ""
        _preambleDetected.value = false
        _sessionState.value = SessionState.RECEIVING
        _debugLog.value = "New session started (RECEIVING)"
    }

    fun finalizeSession() {
        if (_sessionState.value == SessionState.READY || _sessionState.value == SessionState.WAITING) return
        _sessionState.value = SessionState.ENDING
        decoder.finalizeLetter()
        _sessionState.value = SessionState.READY
        _debugLog.value = "Transmission complete (READY)"
    }

    fun handlePulse(durationMs: Long) {
        if (_sessionState.value == SessionState.READY || _sessionState.value == SessionState.WAITING) {
            startNewSession()
        }
        decoder.handlePulse(durationMs)
    }

    fun handleGap(durationMs: Long) {
        decoder.handleGap(durationMs)
    }

    fun handleIdleGap(idleGapMs: Long) {
        decoder.handleIdleGap(idleGapMs)
    }

    fun finalizeLetter() {
        decoder.finalizeLetter()
    }

    fun reset() {
        signalDetector.reset()
        decoder.reset()
        _currentSymbols.value = ""
        _decodedText.value = ""
        _rawDecodedText.value = ""
        _preambleDetected.value = false
        _rawLuminance.value = 0.0f
        _threshold.value = 0.0f
        _isLightOn.value = false
        _sessionState.value = SessionState.WAITING
        _receiverSignalState.value = ReceiverSignalState.CALIBRATING
        _sourceState.value = SourceState.NO_SOURCE
        _isSourceLocked.value = false
        _sourceX.value = 0.5f
        _sourceY.value = 0.5f
        _sourceContrast.value = 0.0f
        _signalDelta.value = 0.0f
        _diagnostics.value = ReceiverDiagnostics()
        lastFrameTimestampMs = 0L
        _debugLog.value = "Receiver reset"
    }
}
