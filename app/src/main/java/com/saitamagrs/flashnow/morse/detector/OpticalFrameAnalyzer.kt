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
class OpticalFrameAnalyzer(
    val config: OpticalDetectorConfig = OpticalDetectorConfig(),
    val detector: OpticalSignalDetector = OpticalSignalDetector(config),
    val timeProvider: () -> Long = {
        try {
            SystemClock.elapsedRealtime()
        } catch (_: Throwable) {
            System.currentTimeMillis()
        }
    }
) : ImageAnalysis.Analyzer {

    companion object {
        private const val TAG = "OpticalFrameAnalyzer"

        /**
         * Pure ByteBuffer ROI luminance extractor.
         *
         * Usable in both production CameraX analysis and standalone JVM unit testing.
         *
         * @param yBuffer Direct or heap buffer containing 8-bit unsigned luminance values.
         * @param width Full image width in pixels.
         * @param height Full image height in pixels.
         * @param rowStride Bytes per row in the buffer (including padding).
         * @param pixelStride Bytes per pixel (typically 1 for Y plane).
         * @param cropRect Active crop rectangle from camera image.
         * @param config Detector configuration containing ROI fractions and percentile target.
         * @param histogram Pre-allocated 256-bin integer array to avoid per-frame allocations.
         * @return The computed percentile luminance (0.0 .. 255.0).
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
            return extractLuminanceFromBuffer(
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

            if (sampleCount == 0) return 0.0f

            // Compute percentile target from histogram
            val targetCount = (sampleCount * config.percentileTarget).toInt().coerceIn(1, sampleCount)
            var accumulated = 0
            for (bin in 0..255) {
                accumulated += histogram[bin]
                if (accumulated >= targetCount) {
                    return bin.toFloat()
                }
            }

            return 255.0f
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

            val luma = extractLuminanceFromBuffer(
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
                luminance = luma,
                timestampMs = nowMs,
                frameProcessingTimeMs = processingDurationMs
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
