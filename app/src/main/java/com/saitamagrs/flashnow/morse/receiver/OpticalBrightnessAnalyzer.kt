package com.saitamagrs.flashnow.morse.receiver

import java.nio.ByteBuffer
import kotlin.math.roundToInt

/**
 * Photometric sample extracted from the center Region of Interest (ROI).
 *
 * @property peakLuma The representative bright signal (top 99.5th percentile) representing the flashlight spot.
 * @property medianLuma The 50th percentile luminance representing the ambient background in the reticle.
 * @property contrast Optical contrast above the local background (peakLuma - medianLuma).
 * @property sampleCount Number of pixels sampled in the center ROI.
 */
data class BrightnessSample(
    val peakLuma: Float,
    val medianLuma: Float,
    val contrast: Float,
    val sampleCount: Int
)

/**
 * High-performance, zero-allocation central ROI brightness extractor for optical Morse detection.
 *
 * Designed with MorseLight's reticle-targeting model:
 * - Inspects a fixed central 30% bounding box where the user aims the camera at the sender.
 * - Extracts both the focused flashlight spot (peak percentile) and the ambient background (median).
 * - Avoids complex candidate tracking windows and cluster algorithms.
 * - Completely zero heap allocations during frame processing.
 */
class OpticalBrightnessAnalyzer(
    val roiWidthFraction: Float = 0.30f,
    val roiHeightFraction: Float = 0.30f
) {
    // Reusable 256-bin histogram for zero-allocation percentile computation
    private val histogram = IntArray(256)

    fun analyze(
        buffer: ByteBuffer,
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int
    ): BrightnessSample {
        histogram.fill(0)
        val limit = buffer.limit()

        val roiW = (width * roiWidthFraction).toInt().coerceAtLeast(10)
        val roiH = (height * roiHeightFraction).toInt().coerceAtLeast(10)
        val startX = (width - roiW) / 2
        val startY = (height - roiH) / 2
        val endX = startX + roiW
        val endY = startY + roiH

        var sampleCount = 0
        var y = startY
        while (y < endY) {
            var x = startX
            while (x < endX) {
                val index = (y * rowStride) + (x * pixelStride)
                if (index in 0 until limit) {
                    val luma = buffer.get(index).toInt() and 0xFF
                    histogram[luma]++
                    sampleCount++
                }
                x += 2 // Step by 2 for sub-millisecond execution
            }
            y += 2
        }

        if (sampleCount == 0) {
            return BrightnessSample(0f, 0f, 0f, 0)
        }

        // 1. Median luminance (50th percentile) represents the resting ambient background of the target area
        var medianLuma = 0
        var medianAccumulator = 0
        val medianTarget = sampleCount / 2
        for (i in 0..255) {
            medianAccumulator += histogram[i]
            if (medianAccumulator >= medianTarget) {
                medianLuma = i
                break
            }
        }

        // 2. Peak luminance (top 99.5th percentile, minimum 1 pixel for micro-tests, up to 10 pixels for video)
        var peakLuma = medianLuma
        var peakAccumulator = 0
        val topTarget = (sampleCount * 0.005f).roundToInt().coerceIn(1, 10)
        for (i in 255 downTo 0) {
            peakAccumulator += histogram[i]
            if (peakAccumulator >= topTarget) {
                peakLuma = i
                break
            }
        }

        val contrast = (peakLuma - medianLuma).toFloat().coerceAtLeast(0f)

        return BrightnessSample(
            peakLuma = peakLuma.toFloat(),
            medianLuma = medianLuma.toFloat(),
            contrast = contrast,
            sampleCount = sampleCount
        )
    }
}
