package com.saitamagrs.flashnow.morse.detector

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.max

/**
 * Listener interface for optical detection events and state changes.
 */
interface OpticalSignalListener {
    /**
     * Called whenever a light-state transition (OFF -> ON or ON -> OFF) is confirmed.
     */
    fun onOpticalTransition(transition: OpticalTransition) {}

    /**
     * Called whenever the confirmed optical state changes.
     */
    fun onStateChanged(state: LightState, timestampMs: Long) {}

    /**
     * Periodic diagnostic update with current photometric metrics.
     */
    fun onDiagnostics(diagnostics: OpticalDiagnostics) {}
}

/**
 * Camera-independent, deterministic optical signal detector.
 *
 * Ingests scalar luminance measurements paired with monotonic timestamps, establishes an ambient
 * light baseline via an initial calibration phase, applies noise-resistant hysteresis thresholds,
 * and confirms light-state transitions with temporal debouncing.
 *
 * Has zero dependencies on Android Views, Lifecycle, or CameraX, making it 100% testable on the JVM.
 */
class OpticalSignalDetector(
    val config: OpticalDetectorConfig = OpticalDetectorConfig(),
    var listener: OpticalSignalListener? = null
) {

    private val lock = Any()

    @Volatile
    var isRunning: Boolean = false
        private set

    @Volatile
    var isCalibrated: Boolean = false
        private set

    var ambientLuma: Float = 0.0f
        private set

    var baselineBrightPixels: Int = 0
        private set

    var currentState: LightState = LightState.OFF
        private set

    var totalFramesProcessed: Long = 0L
        private set

    private var calibrationSum: Double = 0.0
    private var calibrationBrightPixelsSum: Long = 0L
    private var calibrationSampleCount: Int = 0
    private var baselineBrightPixelsFloat: Float = 0.0f

    // Candidate temporal confirmation state
    private var candidateState: LightState? = null
    private var candidateFirstTimestampMs: Long = 0L
    private var candidateLastTimestampMs: Long = 0L
    private var candidateFrameCount: Int = 0

    // Timing tracking
    private var lastSampleTimestampMs: Long = -1L
    private var lastConfirmedTransitionTimestampMs: Long = 0L
    private var lastTransition: OpticalTransition? = null

    // Observable StateFlows
    private val _opticalState = MutableStateFlow(LightState.OFF)
    val opticalState: StateFlow<LightState> = _opticalState.asStateFlow()

    private val _isCalibratedFlow = MutableStateFlow(false)
    val isCalibratedFlow: StateFlow<Boolean> = _isCalibratedFlow.asStateFlow()

    private val _diagnostics = MutableStateFlow(
        OpticalDiagnostics(
            timestampMs = 0L,
            measuredLuma = 0.0f,
            ambientLuma = 0.0f,
            onThreshold = config.minAbsoluteOnLuma,
            offThreshold = 0.0f,
            currentState = LightState.OFF,
            isCalibrated = false,
            maxLuma = 0.0f,
            brightPixelCount = 0,
            deltaBrightPixels = 0,
            ambientEstimateLuma = 0.0f
        )
    )
    val diagnostics: StateFlow<OpticalDiagnostics> = _diagnostics.asStateFlow()

    /**
     * Starts the optical detector. Calibration will begin on incoming samples.
     * Sample processing is synchronous and gated by [isRunning].
     */
    fun start() = synchronized(lock) {
        isRunning = true
    }

    /**
     * Stops the detector and ignores incoming frames until restarted.
     * Clears candidate state so stopping prevents stale callbacks from continuing later.
     */
    fun stop() = synchronized(lock) {
        isRunning = false
        candidateState = null
        candidateFirstTimestampMs = 0L
        candidateLastTimestampMs = 0L
        candidateFrameCount = 0
    }

    /**
     * Resets all internal calibration, state, and buffers.
     */
    fun reset() = synchronized(lock) {
        isCalibrated = false
        calibrationSum = 0.0
        calibrationBrightPixelsSum = 0L
        calibrationSampleCount = 0
        ambientLuma = 0.0f
        baselineBrightPixels = 0
        baselineBrightPixelsFloat = 0.0f
        currentState = LightState.OFF
        candidateState = null
        candidateFirstTimestampMs = 0L
        candidateLastTimestampMs = 0L
        candidateFrameCount = 0
        lastSampleTimestampMs = -1L
        lastConfirmedTransitionTimestampMs = 0L
        lastTransition = null
        totalFramesProcessed = 0L

        _opticalState.value = LightState.OFF
        _isCalibratedFlow.value = false
        _diagnostics.value = OpticalDiagnostics(
            timestampMs = 0L,
            measuredLuma = 0.0f,
            ambientLuma = 0.0f,
            onThreshold = config.minAbsoluteOnLuma,
            offThreshold = 0.0f,
            currentState = LightState.OFF,
            isCalibrated = false,
            maxLuma = 0.0f,
            brightPixelCount = 0,
            deltaBrightPixels = 0,
            ambientEstimateLuma = 0.0f
        )
    }

    /**
     * Ingests a single optical luminance sample with its monotonic timestamp.
     *
     * @param luminance Measured luminance statistic (0.0 .. 255.0).
     * @param timestampMs Monotonic timestamp in milliseconds.
     * @param frameProcessingTimeMs Optional duration spent computing the luminance statistic.
     * @return An [OpticalTransition] if a state change was confirmed on this frame, or null.
     */
    fun processSample(
        luminance: Float,
        timestampMs: Long,
        frameProcessingTimeMs: Long = 0L,
        maxLuma: Float = luminance,
        brightPixelCount: Int = 0,
        ambientEstimateLuma: Float = luminance
    ): OpticalTransition? = synchronized(lock) {
        if (!isRunning) return null

        // Monotonic timestamp validation: reject retrograde timestamps
        if (lastSampleTimestampMs >= 0 && timestampMs < lastSampleTimestampMs) {
            return null
        }
        lastSampleTimestampMs = timestampMs
        totalFramesProcessed++

        // 1. Initial Calibration Phase
        if (!isCalibrated) {
            calibrationSum += ambientEstimateLuma
            calibrationBrightPixelsSum += brightPixelCount
            calibrationSampleCount++

            if (calibrationSampleCount >= config.calibrationFramesCount) {
                ambientLuma = (calibrationSum / calibrationSampleCount).toFloat()
                baselineBrightPixels = (calibrationBrightPixelsSum / calibrationSampleCount).toInt()
                baselineBrightPixelsFloat = baselineBrightPixels.toFloat()
                isCalibrated = true
                _isCalibratedFlow.value = true
                lastConfirmedTransitionTimestampMs = timestampMs
            }

            val onThresh = computeOnThreshold(ambientLuma)
            val offThresh = computeOffThreshold(ambientLuma)
            val deltaBrightPixels = (brightPixelCount - baselineBrightPixels).coerceAtLeast(0)
            emitDiagnostics(
                timestampMs = timestampMs,
                measuredLuma = luminance,
                ambientLuma = ambientLuma,
                onThreshold = onThresh,
                offThreshold = offThresh,
                frameProcessingTimeMs = frameProcessingTimeMs,
                maxLuma = maxLuma,
                brightPixelCount = brightPixelCount,
                deltaBrightPixels = deltaBrightPixels,
                ambientEstimateLuma = ambientEstimateLuma
            )
            return null
        }

        // 2. Threshold Calculation with Hysteresis
        val onThreshold = computeOnThreshold(ambientLuma)
        val offThreshold = computeOffThreshold(ambientLuma)

        // 3. Raw Instantaneous Light State Decision
        val deltaBrightPixels = brightPixelCount - baselineBrightPixels
        val hasStaticBrightBackground = baselineBrightPixels >= config.minSpotPixels

        val rawTargetState = when (currentState) {
            LightState.OFF -> {
                val lumaExceedsOn = luminance >= onThreshold
                val spotQualified = if (hasStaticBrightBackground) {
                    deltaBrightPixels >= config.minSpotPixels
                } else {
                    true
                }
                if (lumaExceedsOn && spotQualified) LightState.ON else LightState.OFF
            }
            LightState.ON -> {
                if (hasStaticBrightBackground) {
                    if (deltaBrightPixels < config.minSpotPixels || luminance <= offThreshold) {
                        LightState.OFF
                    } else {
                        LightState.ON
                    }
                } else {
                    if (luminance <= offThreshold) LightState.OFF else LightState.ON
                }
            }
        }

        var confirmedTransition: OpticalTransition? = null

        // 4. Temporal Confirmation & Debouncing
        if (rawTargetState != currentState) {
            // Sample differs from confirmed state: track as candidate
            if (candidateState == rawTargetState) {
                val intervalSinceLast = timestampMs - candidateLastTimestampMs
                if (intervalSinceLast > config.maxCandidateIntervalMs) {
                    // Stale candidate: gap between observations exceeded maxCandidateIntervalMs.
                    // Discard stale observation and treat current sample as first observation of fresh candidate.
                    candidateFirstTimestampMs = timestampMs
                    candidateLastTimestampMs = timestampMs
                    candidateFrameCount = 1
                } else {
                    candidateLastTimestampMs = timestampMs
                    candidateFrameCount++
                    val candidateDuration = timestampMs - candidateFirstTimestampMs

                    if (candidateDuration >= config.minConfirmationMs &&
                        candidateFrameCount >= config.minConfirmationFrames
                    ) {
                        // Transition is confirmed!
                        val prevDuration = max(0L, timestampMs - lastConfirmedTransitionTimestampMs)
                        val transition = OpticalTransition(
                            previousState = currentState,
                            newState = rawTargetState,
                            transitionTimestampMs = timestampMs,
                            previousStateDurationMs = prevDuration
                        )

                        currentState = rawTargetState
                        lastConfirmedTransitionTimestampMs = timestampMs
                        lastTransition = transition
                        confirmedTransition = transition

                        candidateState = null
                        candidateFirstTimestampMs = 0L
                        candidateLastTimestampMs = 0L
                        candidateFrameCount = 0

                        _opticalState.value = currentState
                        listener?.onStateChanged(currentState, timestampMs)
                        listener?.onOpticalTransition(transition)
                    }
                }
            } else {
                // New candidate begins
                candidateState = rawTargetState
                candidateFirstTimestampMs = timestampMs
                candidateLastTimestampMs = timestampMs
                candidateFrameCount = 1
            }
        } else {
            // Signal matches current confirmed state: clear any transient noise spike
            candidateState = null
            candidateFirstTimestampMs = 0L
            candidateLastTimestampMs = 0L
            candidateFrameCount = 0

            // Slow ambient baseline adaptation (ONLY during confirmed OFF state)
            if (currentState == LightState.OFF && config.ambientAdaptAlpha > 0.0f) {
                ambientLuma += (ambientEstimateLuma - ambientLuma) * config.ambientAdaptAlpha
                if (hasStaticBrightBackground) {
                    baselineBrightPixelsFloat += (brightPixelCount - baselineBrightPixelsFloat) * config.ambientAdaptAlpha
                    baselineBrightPixels = baselineBrightPixelsFloat.toInt().coerceAtLeast(0)
                }
            }
        }

        // 5. Publish Diagnostics
        emitDiagnostics(
            timestampMs = timestampMs,
            measuredLuma = luminance,
            ambientLuma = ambientLuma,
            onThreshold = onThreshold,
            offThreshold = offThreshold,
            frameProcessingTimeMs = frameProcessingTimeMs,
            maxLuma = maxLuma,
            brightPixelCount = brightPixelCount,
            deltaBrightPixels = deltaBrightPixels,
            ambientEstimateLuma = ambientEstimateLuma
        )

        return confirmedTransition
    }

    private fun computeOnThreshold(ambient: Float): Float {
        val calculated = max(config.minAbsoluteOnLuma, ambient + config.onContrastOffset)
        return calculated.coerceAtMost(config.maxOnThreshold)
    }

    private fun computeOffThreshold(ambient: Float): Float {
        val calculated = ambient + config.offContrastOffset
        val onThresh = computeOnThreshold(ambient)
        return calculated.coerceAtMost(max(0.0f, onThresh - 1.0f))
    }

    private fun emitDiagnostics(
        timestampMs: Long,
        measuredLuma: Float,
        ambientLuma: Float,
        onThreshold: Float,
        offThreshold: Float,
        frameProcessingTimeMs: Long,
        maxLuma: Float = measuredLuma,
        brightPixelCount: Int = 0,
        deltaBrightPixels: Int = 0,
        ambientEstimateLuma: Float = ambientLuma
    ) {
        val diag = OpticalDiagnostics(
            timestampMs = timestampMs,
            measuredLuma = measuredLuma,
            ambientLuma = ambientLuma,
            onThreshold = onThreshold,
            offThreshold = offThreshold,
            currentState = currentState,
            isCalibrated = isCalibrated,
            frameProcessingTimeMs = frameProcessingTimeMs,
            totalFramesProcessed = totalFramesProcessed,
            lastTransition = lastTransition,
            maxLuma = maxLuma,
            brightPixelCount = brightPixelCount,
            deltaBrightPixels = deltaBrightPixels,
            ambientEstimateLuma = ambientEstimateLuma
        )
        _diagnostics.value = diag
        listener?.onDiagnostics(diag)
    }
}
