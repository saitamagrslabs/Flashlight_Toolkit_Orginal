package com.saitamagrs.flashnow.morse.detector

/**
 * Diagnostic snapshot of the optical detector state for a single frame or time instant.
 *
 * @property timestampMs Monotonic timestamp of this sample in milliseconds.
 * @property measuredLuma The representative luminance statistic measured from the ROI (0.0 .. 255.0).
 * @property ambientLuma The current calibrated ambient/background luminance estimate.
 * @property onThreshold The active upper hysteresis threshold required to trigger an ON transition.
 * @property offThreshold The active lower hysteresis threshold required to drop back to OFF.
 * @property currentState The current confirmed optical state (ON or OFF).
 * @property isCalibrated True if initial calibration has finished and detector is actively tracking signals.
 * @property frameProcessingTimeMs Duration in milliseconds spent analyzing the frame image.
 * @property totalFramesProcessed Total number of frames processed in the current session.
 * @property lastTransition The most recent confirmed optical transition, or null if none has occurred.
 */
data class OpticalDiagnostics(
    val timestampMs: Long,
    val measuredLuma: Float,
    val ambientLuma: Float,
    val onThreshold: Float,
    val offThreshold: Float,
    val currentState: LightState,
    val isCalibrated: Boolean,
    val frameProcessingTimeMs: Long = 0L,
    val totalFramesProcessed: Long = 0L,
    val lastTransition: OpticalTransition? = null
)
