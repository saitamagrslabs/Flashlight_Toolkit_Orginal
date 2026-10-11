package com.saitamagrs.flashnow.morse.receiver

/**
 * Optical signal transition detector using an explicit settling/calibration phase,
 * asymmetric hysteresis, monotonic timestamps, and glitch filtering.
 */
class OpticalSignalDetector(
    private val onPulseCallback: (Long) -> Unit,
    private val onGapCallback: (Long) -> Unit,
    private val onIdleGapCallback: (Long) -> Unit,
    private val onStateChangeCallback: (Boolean) -> Unit = {}
) {
    companion object {
        const val CALIBRATION_FRAMES_REQUIRED = 5
        const val MIN_GLITCH_DURATION_MS = 25L
        const val ON_THRESHOLD_DELTA = 8.5f
        const val BASE_OFF_THRESHOLD_DELTA = 4.5f
        const val HIGH_CONTRAST_PEAK_THRESHOLD = 40.0f
        const val CLOSE_RANGE_OFF_RATIO = 0.28f
        const val MIN_OFF_CONTRAST = 6.0f
        const val MAX_OFF_CONTRAST = 32.0f
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

    var calibrationFramesCount: Int = 0
        internal set

    var transitionCount: Int = 0
        private set

    private var lastStateChangeTimestamp: Long = 0L
    private var candidateStateIsOn: Boolean? = null
    private var candidateStateTimestamp: Long = 0L
    private var currentPeakContrast: Float = 0.0f

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
        calibrationFramesCount = 0
        transitionCount = 0
        lastStateChangeTimestamp = 0L
        candidateStateIsOn = null
        candidateStateTimestamp = 0L
        currentPeakContrast = 0.0f
    }

    /**
     * Processes a single photometric reading.
     *
     * @param contrast The optical contrast above local background (peakLuma - medianLuma).
     * @param brightSignal The peak spot luminance (0..255).
     * @param nowMs Monotonic timestamp in milliseconds.
     * @param backgroundEstimate The median/local background estimate.
     */
    fun processSample(
        contrast: Float,
        brightSignal: Float,
        nowMs: Long,
        backgroundEstimate: Float = brightSignal
    ) {
        // 1. Initial settling / calibration phase to establish resting ambient and contrast
        if (calibrationFramesCount < CALIBRATION_FRAMES_REQUIRED) {
            calibrationFramesCount++
            ambientBaseline = if (calibrationFramesCount == 1) {
                backgroundEstimate
            } else {
                (ambientBaseline * 0.7f) + (backgroundEstimate * 0.3f)
            }
            restingContrast = if (calibrationFramesCount == 1) {
                contrast
            } else {
                (restingContrast * 0.7f) + (contrast * 0.3f)
            }
            lastStateChangeTimestamp = nowMs
            isLightOn = false
            candidateStateIsOn = null
            if (calibrationFramesCount >= CALIBRATION_FRAMES_REQUIRED) {
                state = DetectorState.READY
            }
            return
        }

        // 2. Slow ambient baseline tracking during confirmed dark intervals (light OFF)
        if (!isLightOn && candidateStateIsOn == null) {
            val restingSignal = if (backgroundEstimate > 0f && backgroundEstimate != brightSignal) {
                backgroundEstimate
            } else {
                brightSignal
            }
            val ambientDelta = restingSignal - ambientBaseline

            // Only adapt when signal is below ON threshold to prevent chasing an active flashlight
            if (contrast < ON_THRESHOLD_DELTA) {
                if (ambientDelta < 0f) {
                    ambientBaseline = (ambientBaseline * 0.88f) + (restingSignal * 0.12f)
                } else {
                    ambientBaseline = (ambientBaseline * 0.90f) + (restingSignal * 0.10f)
                }
                restingContrast = (restingContrast * 0.92f) + (contrast * 0.08f)
            }
        }

        // 3. Track peak contrast for close-range lens flare compensation
        if (isLightOn) {
            currentPeakContrast = maxOf(currentPeakContrast, contrast)
        }

        // 4. Compute dynamic OFF threshold
        val activeOffContrast = if (isLightOn && currentPeakContrast > HIGH_CONTRAST_PEAK_THRESHOLD) {
            (currentPeakContrast * CLOSE_RANGE_OFF_RATIO).coerceIn(MIN_OFF_CONTRAST, MAX_OFF_CONTRAST)
        } else {
            BASE_OFF_THRESHOLD_DELTA
        }

        // 5. Schmitt trigger / Hysteresis detection
        val effectiveOffThreshold = restingContrast + activeOffContrast
        val effectiveOnThreshold = restingContrast + ON_THRESHOLD_DELTA

        val detectedRawOn = if (isLightOn) {
            contrast > activeOffContrast
        } else {
            contrast >= ON_THRESHOLD_DELTA
        }

        // 6. Glitch filtering (persists across frames >= 25ms)
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
                        // OFF -> ON: previous period was a GAP
                        state = DetectorState.RECEIVING
                        currentPeakContrast = contrast
                        if (durationMs >= MIN_GLITCH_DURATION_MS) {
                            onGapCallback(durationMs)
                        }
                    } else {
                        // ON -> OFF: previous period was a PULSE
                        currentPeakContrast = 0.0f
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
