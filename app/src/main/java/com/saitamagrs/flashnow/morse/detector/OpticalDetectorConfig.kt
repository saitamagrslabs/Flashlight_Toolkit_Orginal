package com.saitamagrs.flashnow.morse.detector

/**
 * Centralized configuration for optical signal detection, sampling, and hysteresis.
 *
 * Designed to reliably detect nominal 150ms dots and 450ms dashes from a remote flashlight
 * without being corrupted by sensor noise, hot pixels, or gradual room lighting changes.
 *
 * @property roiWidthFraction Horizontal fraction of the camera crop rect to sample (center-aligned).
 * @property roiHeightFraction Vertical fraction of the camera crop rect to sample (center-aligned).
 * @property pixelSamplingStride Subsampling stride within the ROI (1 = every pixel for maximum density).
 * @property percentileTarget Luminance percentile used as the representative signal statistic (e.g. 0.95 = 95th percentile).
 *                             A high percentile detects a localized flashlight beam spot without being skewed by hot pixels.
 * @property calibrationFramesCount Number of initial frames averaged to establish the ambient baseline.
 * @property onContrastOffset Luminance offset above ambient required to trigger an ON candidate (upper hysteresis threshold).
 * @property offContrastOffset Luminance offset above ambient required to trigger an OFF candidate (lower hysteresis threshold).
 * @property minAbsoluteOnLuma Absolute luminance floor for an ON decision, preventing low-contrast triggers in near pitch-black rooms.
 * @property minConfirmationMs Minimum duration in milliseconds a candidate state must persist before confirming a transition.
 *                             Rejects high-frequency optical spikes and camera sync glitches (< 40ms) without erasing 150ms dots.
 * @property minConfirmationFrames Minimum number of consecutive frames in candidate state required for confirmation.
 * @property maxCandidateIntervalMs Maximum permitted interval in milliseconds between consecutive candidate observations.
 *                                  If the gap between consecutive candidate frames exceeds this threshold, the stale
 *                                  candidate is discarded and the current frame starts a fresh candidate observation.
 *                                  Default (120ms) accommodates normal CameraX operation at 20-30 fps (33ms-50ms frames)
 *                                  including single-frame jitter or one dropped frame, while rejecting multi-frame gaps.
 * @property ambientAdaptAlpha Learning rate for slow baseline drift adaptation during confirmed OFF state (0.0 = disabled).
 */
data class OpticalDetectorConfig(
    val roiWidthFraction: Float = 0.50f,
    val roiHeightFraction: Float = 0.50f,
    val pixelSamplingStride: Int = 1,
    val percentileTarget: Float = 0.95f,
    val calibrationFramesCount: Int = 10,
    val onContrastOffset: Float = 25.0f,
    val offContrastOffset: Float = 12.0f,
    val minAbsoluteOnLuma: Float = 35.0f,
    val minConfirmationMs: Long = 40L,
    val minConfirmationFrames: Int = 2,
    val maxCandidateIntervalMs: Long = 120L,
    val ambientAdaptAlpha: Float = 0.005f,
    val useBoundedTopPixel: Boolean = true,
    val minSpotPixels: Int = 6,
    val maxSpotPixels: Int = 200,
    val spotPixelFraction: Float = 0.0004f
)
