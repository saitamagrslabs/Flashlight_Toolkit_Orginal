package com.saitamagrs.flashnow.morse.detector

import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import java.nio.ByteBuffer

/**
 * CameraX [ImageAnalysis.Analyzer] bridging incoming camera frames to the [OpticalSignalDetector].
 *
 * Responsibilities:
 * - Safely inspects the Y-plane (luminance) of YUV_420_888 camera frames.
 * - Extracts a configurable center Region of Interest (ROI) with row/pixel stride handling.
 * - Computes a robust percentile statistic via an allocation-free 256-bin histogram.
 * - Passes the measured statistic and monotonic timestamp to [detector].
 * - Always closes the [ImageProxy] in a strict finally block to prevent CameraX buffer starvation.
 */
/**
 * Photometric analysis results for a sampled Region of Interest (ROI).
 *
 * @property representativeLuma The representative luminance statistic (0.0 .. 255.0) passed to the detector.
 * @property maxLuma Peak pixel luminance within the sampled ROI (0.0 .. 255.0).
 * @property brightPixelCount Number of pixels in the ROI exceeding the minimum brightness criterion.
 * @property sampleCount Total number of pixels sampled in the ROI.
 */
data class RoiAnalysisResult(
    val representativeLuma: Float,
    val maxLuma: Float,
    val brightPixelCount: Int,
    val sampleCount: Int,
    val ambientEstimateLuma: Float = representativeLuma,
    val spotPixelCount: Int = brightPixelCount
) {
    val signalLuma: Float get() = representativeLuma
}

class OpticalFrameAnalyzer(
    val config: OpticalDetectorConfig = OpticalDetectorConfig(),
    val detector: OpticalSignalDetector = OpticalSignalDetector(config),
    val timeProvider: () -> Long = {
        try {
            SystemClock.elapsedRealtime()
        } catch (_: Throwable) {
            System.nanoTime() / 1_000_000L
        }
    }
) : ImageAnalysis.Analyzer {

    companion object {
        private const val TAG = "OpticalFrameAnalyzer"

        /**
         * Pure ByteBuffer ROI luminance extractor.
         *
         * Usable in both production CameraX analysis and standalone JVM unit testing.
         */
        fun extractLuminanceFromBuffer(
            yBuffer: ByteBuffer,
            width: Int,
            height: Int,
            rowStride: Int,
            pixelStride: Int,
            cropRect: Rect?,
            config: OpticalDetectorConfig,
            histogram: IntArray
        ): Float {
            return extractRoiAnalysisFromBuffer(
                yBuffer, width, height, rowStride, pixelStride, cropRect, config, histogram
            ).representativeLuma
        }

        fun extractLuminanceFromBuffer(
            yBuffer: ByteBuffer,
            width: Int,
            height: Int,
            rowStride: Int,
            pixelStride: Int,
            cropLeft: Int,
            cropTop: Int,
            cropRight: Int,
            cropBottom: Int,
            config: OpticalDetectorConfig,
            histogram: IntArray
        ): Float {
            return extractRoiAnalysisFromBuffer(
                yBuffer, width, height, rowStride, pixelStride,
                cropLeft, cropTop, cropRight, cropBottom, config, histogram
            ).representativeLuma
        }

        fun extractRoiAnalysisFromBuffer(
            yBuffer: ByteBuffer,
            width: Int,
            height: Int,
            rowStride: Int,
            pixelStride: Int,
            cropRect: Rect?,
            config: OpticalDetectorConfig,
            histogram: IntArray
        ): RoiAnalysisResult {
            val cLeft = try { cropRect?.left ?: 0 } catch (_: Throwable) { 0 }
            val cTop = try { cropRect?.top ?: 0 } catch (_: Throwable) { 0 }
            val cRight = try {
                val r = cropRect?.right ?: width
                if (r > cLeft) r else width
            } catch (_: Throwable) {
                width
            }
            val cBottom = try {
                val b = cropRect?.bottom ?: height
                if (b > cTop) b else height
            } catch (_: Throwable) {
                height
            }
            return extractRoiAnalysisFromBuffer(
                yBuffer = yBuffer,
                width = width,
                height = height,
                rowStride = rowStride,
                pixelStride = pixelStride,
                cropLeft = cLeft,
                cropTop = cTop,
                cropRight = cRight,
                cropBottom = cBottom,
                config = config,
                histogram = histogram
            )
        }

        fun extractRoiAnalysisFromBuffer(
            yBuffer: ByteBuffer,
            width: Int,
            height: Int,
            rowStride: Int,
            pixelStride: Int,
            cropLeft: Int,
            cropTop: Int,
            cropRight: Int,
            cropBottom: Int,
            config: OpticalDetectorConfig,
            histogram: IntArray
        ): RoiAnalysisResult {
            // Zero out pre-allocated histogram
            histogram.fill(0)

            val safeLeft = cropLeft.coerceIn(0, width - 1)
            val safeTop = cropTop.coerceIn(0, height - 1)
            val safeRight = cropRight.coerceIn(safeLeft + 1, width)
            val safeBottom = cropBottom.coerceIn(safeTop + 1, height)

            val cropWidth = safeRight - safeLeft
            val cropHeight = safeBottom - safeTop

            // Calculate center ROI boundaries
            val roiWidth = (cropWidth * config.roiWidthFraction).toInt().coerceAtLeast(1)
            val roiHeight = (cropHeight * config.roiHeightFraction).toInt().coerceAtLeast(1)

            val roiLeft = safeLeft + (cropWidth - roiWidth) / 2
            val roiTop = safeTop + (cropHeight - roiHeight) / 2
            val roiRight = (roiLeft + roiWidth).coerceAtMost(width)
            val roiBottom = (roiTop + roiHeight).coerceAtMost(height)

            val stride = config.pixelSamplingStride.coerceAtLeast(1)
            var sampleCount = 0

            val bufferLimit = yBuffer.limit()

            for (row in roiTop until roiBottom step stride) {
                val rowOffset = row * rowStride
                for (col in roiLeft until roiRight step stride) {
                    val index = rowOffset + col * pixelStride
                    if (index < bufferLimit) {
                        val pixelValue = yBuffer.get(index).toInt() and 0xFF
                        histogram[pixelValue]++
                        sampleCount++
                    }
                }
            }

            if (sampleCount == 0) return RoiAnalysisResult(0.0f, 0.0f, 0, 0, 0.0f, 0)

            // 1. Compute robust bulk ambient baseline estimate (default: 50th percentile / median of ROI)
            val ambientTargetCount = (sampleCount * config.ambientPercentile).toInt().coerceIn(1, sampleCount)
            var accumulatedAmbient = 0
            var ambientEstimateLuma = 0.0f
            for (bin in 0..255) {
                accumulatedAmbient += histogram[bin]
                if (accumulatedAmbient >= ambientTargetCount) {
                    ambientEstimateLuma = bin.toFloat()
                    break
                }
            }

            // 2. Base absolute floor for brightPixelCount (pixels >= minAbsoluteOnLuma)
            val brightFloor = config.minAbsoluteOnLuma.toInt().coerceIn(0, 255)

            // 3. Dynamic spot floor: pixels that stand out above the ambient background
            val spotFloor = maxOf(
                config.minAbsoluteOnLuma.toInt(),
                (ambientEstimateLuma + config.offContrastOffset).toInt()
            ).coerceIn(0, 255)

            // Fast histogram scan (in-register, zero heap allocation) to compute maxLuma, brightPixelCount, and spotPixelCount
            var maxLuma = 0.0f
            var foundMax = false
            var brightPixelCount = 0
            var spotPixelCount = 0

            for (bin in 255 downTo 0) {
                val count = histogram[bin]
                if (count > 0) {
                    if (!foundMax) {
                        maxLuma = bin.toFloat()
                        foundMax = true
                    }
                    if (bin >= brightFloor) {
                        brightPixelCount += count
                    }
                    if (bin >= spotFloor) {
                        spotPixelCount += count
                    }
                }
            }

            var representativeLuma = 0.0f

            if (config.useBoundedTopPixel) {
                val requiredTopPixels = (sampleCount * config.spotPixelFraction)
                    .toInt()
                    .coerceIn(config.minSpotPixels, config.maxSpotPixels)
                    .coerceAtMost(sampleCount)

                // When ambient exceeds minAbsoluteOnLuma, qualifying pixels must exceed spotFloor
                // to prevent background pixels from satisfying the bounded top pixel target.
                val qualifyingCount = if (ambientEstimateLuma >= config.minAbsoluteOnLuma) {
                    spotPixelCount
                } else {
                    brightPixelCount
                }

                val targetPixels = if (qualifyingCount >= config.minSpotPixels) {
                    qualifyingCount.coerceAtMost(requiredTopPixels)
                } else {
                    requiredTopPixels
                }

                var accumulatedTop = 0
                for (bin in 255 downTo 0) {
                    val count = histogram[bin]
                    if (count > 0) {
                        accumulatedTop += count
                        if (accumulatedTop >= targetPixels) {
                            representativeLuma = bin.toFloat()
                            break
                        }
                    }
                }
            } else {
                // Legacy fixed percentile calculation
                val targetCount = (sampleCount * config.percentileTarget).toInt().coerceIn(1, sampleCount)
                var accumulated = 0
                for (bin in 0..255) {
                    accumulated += histogram[bin]
                    if (accumulated >= targetCount) {
                        representativeLuma = bin.toFloat()
                        break
                    }
                }
            }

            return RoiAnalysisResult(
                representativeLuma = representativeLuma,
                maxLuma = maxLuma,
                brightPixelCount = brightPixelCount,
                sampleCount = sampleCount,
                ambientEstimateLuma = ambientEstimateLuma,
                spotPixelCount = spotPixelCount
            )
        }
    }

    // Pre-allocated histogram reused across frames (no per-frame heap churn)
    private val histogram = IntArray(256)

    override fun analyze(image: ImageProxy) {
        val startNs = System.nanoTime()
        try {
            if (!detector.isRunning) {
                return
            }

            val planes = image.planes
            if (planes.isEmpty()) {
                return
            }

            val yPlane = planes[0]
            val buffer = yPlane.buffer ?: return
            val rowStride = yPlane.rowStride
            val pixelStride = yPlane.pixelStride

            val analysis = extractRoiAnalysisFromBuffer(
                yBuffer = buffer,
                width = image.width,
                height = image.height,
                rowStride = rowStride,
                pixelStride = pixelStride,
                cropRect = image.cropRect,
                config = config,
                histogram = histogram
            )

            val nowMs = timeProvider()
            val processingDurationMs = (System.nanoTime() - startNs) / 1_000_000L

            detector.processSample(
                luminance = analysis.representativeLuma,
                timestampMs = nowMs,
                frameProcessingTimeMs = processingDurationMs,
                maxLuma = analysis.maxLuma,
                brightPixelCount = analysis.spotPixelCount,
                ambientEstimateLuma = analysis.ambientEstimateLuma
            )
        } catch (t: Throwable) {
            try {
                Log.e(TAG, "Frame analysis exception", t)
            } catch (_: Throwable) {
                // Ignore logging failures in test environments
            }
        } finally {
            // Strictly guaranteed frame closure
            try {
                image.close()
            } catch (_: Throwable) {
                // Ignore redundant close
            }
        }
    }
}
