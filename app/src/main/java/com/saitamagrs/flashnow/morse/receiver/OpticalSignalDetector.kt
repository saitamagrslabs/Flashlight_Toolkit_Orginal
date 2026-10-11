package com.saitamagrs.flashnow.morse.receiver

/**
 * Optical signal transition detector using an explicit settling/calibration phase,
 * temporal delta-from-resting-baseline measurement, asymmetric hysteresis, monotonic timestamps,
 * and glitch filtering.
 *
 * Distinguishes steady ambient room illumination (even at high brightness or with specular highlights)
 * from active, timed flashlight pulses by tracking changes relative to the calibrated resting state.
 */
class OpticalSignalDetector(
    private val onPulseCallback: (Long) -> Unit,
    private val onGapCallback: (Long) -> Unit,
    private val onIdleGapCallback: (Long) -> Unit,
    private val onStateChangeCallback: (Boolean) -> Unit = {}
) {
    companion object {
        const val CALIBRATION_FRAMES_REQUIRED = 10
        const val MIN_GLITCH_DURATION_MS = 25L
        const val ON_THRESHOLD_DELTA = 8.5f
        const val BASE_OFF_THRESHOLD_DELTA = 4.5f
        const val HIGH_CONTRAST_PEAK_THRESHOLD = 40.0f
        const val CLOSE_RANGE_OFF_RATIO = 0.28f
        const val MIN_OFF_THRESHOLD = 4.5f
        const val MAX_OFF_THRESHOLD = 32.0f
    }

    enum class DetectorState {
        CALIBRATING,
        READY,
        RECEIVING,
        COMPLETED
    }

    var state: DetectorState = DetectorState.CALIBRATING
        private set

    var isLightOn: Boolean = false
        private set

    var ambientBaseline: Float = 40.0f
        internal set

    var restingContrast: Float = 0.0f
        internal set

    var restingSpot: Float = 40.0f
        internal set

    var restingMean: Float = 40.0f
        internal set

    var lastTemporalSignal: Float = 0.0f
        private set

    var lastActiveOffThreshold: Float = BASE_OFF_THRESHOLD_DELTA
        private set

    val onThreshold: Float get() = ON_THRESHOLD_DELTA
    val offThreshold: Float get() = lastActiveOffThreshold

    var calibrationFramesCount: Int = 0
        internal set

    var transitionCount: Int = 0
        private set

    private var lastStateChangeTimestamp: Long = 0L
    private var candidateStateIsOn: Boolean? = null
    private var candidateStateTimestamp: Long = 0L
    private var currentPeakSignal: Float = 0.0f

    val isCalibrated: Boolean get() = calibrationFramesCount >= CALIBRATION_FRAMES_REQUIRED

    fun setCalibrationDone() {
        calibrationFramesCount = CALIBRATION_FRAMES_REQUIRED
        state = DetectorState.READY
    }

    fun reset() {
        state = DetectorState.CALIBRATING
        isLightOn = false
        ambientBaseline = 40.0f
        restingContrast = 0.0f
        restingSpot = 40.0f
        restingMean = 40.0f
        lastTemporalSignal = 0.0f
        lastActiveOffThreshold = BASE_OFF_THRESHOLD_DELTA
        calibrationFramesCount = 0
        transitionCount = 0
        lastStateChangeTimestamp = 0L
        candidateStateIsOn = null
        candidateStateTimestamp = 0L
        currentPeakSignal = 0.0f
    }

    /**
     * Processes a single photometric reading.
     *
     * @param contrast The optical contrast above local background (peakLuma - medianLuma).
     * @param brightSignal The peak spot luminance (0..255).
     * @param nowMs Monotonic timestamp in milliseconds.
     * @param backgroundEstimate The median/local background estimate.
     * @param meanLuma The arithmetic mean luminance across the reticle.
     */
    fun processSample(
        contrast: Float,
        brightSignal: Float,
        nowMs: Long,
        backgroundEstimate: Float = brightSignal,
        meanLuma: Float = backgroundEstimate
    ) {
        // 1. Initial settling / calibration phase to establish resting ambient, spot, and contrast
        if (calibrationFramesCount < CALIBRATION_FRAMES_REQUIRED) {
            calibrationFramesCount++
            val weight = if (calibrationFramesCount == 1) 1.0f else 0.25f
            ambientBaseline = (ambientBaseline * (1f - weight)) + (backgroundEstimate * weight)
            restingContrast = (restingContrast * (1f - weight)) + (contrast * weight)
            restingSpot = (restingSpot * (1f - weight)) + (brightSignal * weight)
            restingMean = (restingMean * (1f - weight)) + (meanLuma * weight)
            lastStateChangeTimestamp = nowMs
            isLightOn = false
            candidateStateIsOn = null
            if (calibrationFramesCount >= CALIBRATION_FRAMES_REQUIRED) {
                state = DetectorState.READY
            }
            return
        }

        // 2. Slow baseline tracking during confirmed dark intervals (light OFF)
        if (!isLightOn && candidateStateIsOn == null) {
            val deltaSpot = (brightSignal - restingSpot).coerceAtLeast(0f)
            val deltaContrast = (contrast - restingContrast).coerceAtLeast(0f)
            val deltaMean = (meanLuma - restingMean).coerceAtLeast(0f)
            val candidateSignal = maxOf(deltaContrast, deltaSpot, deltaMean * 2.0f)

            // Only adapt when signal is below half the ON threshold to prevent chasing an active flash rising edge
            if (candidateSignal < ON_THRESHOLD_DELTA * 0.5f) {
                val alpha = 0.12f
                restingSpot = (restingSpot * (1f - alpha)) + (brightSignal * alpha)
                restingContrast = (restingContrast * (1f - alpha)) + (contrast * alpha)
                restingMean = (restingMean * (1f - alpha)) + (meanLuma * alpha)
                ambientBaseline = (ambientBaseline * (1f - alpha)) + (backgroundEstimate * alpha)
            }
        }

        // 3. Compute temporal signal evidence above calibrated resting baselines
        val deltaSpot = (brightSignal - restingSpot).coerceAtLeast(0f)
        val deltaContrast = (contrast - restingContrast).coerceAtLeast(0f)
        val deltaMean = (meanLuma - restingMean).coerceAtLeast(0f)
        val temporalSignal = maxOf(deltaSpot, deltaContrast, deltaMean * 2.0f)
        lastTemporalSignal = temporalSignal

        // 4. Track peak temporal signal for close-range lens flare compensation
        if (isLightOn) {
            currentPeakSignal = maxOf(currentPeakSignal, temporalSignal)
        }

        // 5. Dynamic OFF threshold calculation
        val activeOffDelta = if (isLightOn && currentPeakSignal > HIGH_CONTRAST_PEAK_THRESHOLD) {
            (currentPeakSignal * CLOSE_RANGE_OFF_RATIO).coerceIn(MIN_OFF_THRESHOLD, MAX_OFF_THRESHOLD)
        } else {
            BASE_OFF_THRESHOLD_DELTA
        }
        lastActiveOffThreshold = activeOffDelta

        // 6. Schmitt trigger / Hysteresis comparison: evaluate temporal signal delta
        val detectedRawOn = if (isLightOn) {
            temporalSignal > activeOffDelta
        } else {
            temporalSignal >= ON_THRESHOLD_DELTA
        }

        // 7. Temporal glitch filtering (persists across frames >= 25ms)
        if (detectedRawOn != isLightOn) {
            if (candidateStateIsOn != detectedRawOn) {
                candidateStateIsOn = detectedRawOn
                candidateStateTimestamp = nowMs
            } else {
                val candidateDuration = nowMs - candidateStateTimestamp
                if (candidateDuration >= MIN_GLITCH_DURATION_MS) {
                    // Confirmed valid state transition!
                    val durationMs = candidateStateTimestamp - lastStateChangeTimestamp
                    lastStateChangeTimestamp = candidateStateTimestamp
                    isLightOn = detectedRawOn
                    candidateStateIsOn = null
                    transitionCount++
                    onStateChangeCallback(isLightOn)

                    if (isLightOn) {
                        state = DetectorState.RECEIVING
                        currentPeakSignal = temporalSignal
                        if (durationMs >= MIN_GLITCH_DURATION_MS) {
                            onGapCallback(durationMs)
                        }
                    } else {
                        currentPeakSignal = 0.0f
                        if (durationMs >= MIN_GLITCH_DURATION_MS) {
                            onPulseCallback(durationMs)
                        }
                    }
                }
            }
        } else {
            candidateStateIsOn = null

            // If light remains OFF, notify idle gap listener for responsive letter/session finalization
            if (!isLightOn && lastStateChangeTimestamp > 0L) {
                val idleGapMs = nowMs - lastStateChangeTimestamp
                onIdleGapCallback(idleGapMs)
            }
        }
    }
}
