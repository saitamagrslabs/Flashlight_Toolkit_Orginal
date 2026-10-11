package com.saitamagrs.flashnow.morse.receiver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.ByteBuffer

class OpticalBrightnessAnalyzerTest {

    private lateinit var analyzer: OpticalBrightnessAnalyzer

    @Before
    fun setUp() {
        analyzer = OpticalBrightnessAnalyzer(roiWidthFraction = 0.30f, roiHeightFraction = 0.30f)
    }

    private fun createUniformBuffer(width: Int, height: Int, luma: Int): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(width * height)
        for (i in 0 until width * height) {
            buffer.put(luma.toByte())
        }
        buffer.rewind()
        return buffer
    }

    private fun setPoint(buffer: ByteBuffer, width: Int, x: Int, y: Int, luma: Int) {
        val idx = (y * width) + x
        if (idx in 0 until buffer.limit()) {
            buffer.put(idx, luma.toByte())
        }
    }

    @Test
    fun `test uniform frame produces zero contrast`() {
        val buffer = createUniformBuffer(100, 100, 60)
        val sample = analyzer.analyze(buffer, 100, 100, 100, 1)

        assertTrue(sample.sampleCount > 0)
        assertEquals(60f, sample.medianLuma, 1.0f)
        assertEquals(60f, sample.peakLuma, 1.0f)
        assertEquals(0f, sample.contrast, 0.1f)
    }

    @Test
    fun `test small centered flashlight spot produces sharp optical contrast`() {
        val buffer = createUniformBuffer(100, 100, 40)
        // Add a 3x3 bright flashlight spot at the center (50, 50)
        for (dy in -1..1) {
            for (dx in -1..1) {
                setPoint(buffer, 100, 50 + dx, 50 + dy, 220)
            }
        }

        val sample = analyzer.analyze(buffer, 100, 100, 100, 1)

        assertEquals(40f, sample.medianLuma, 1.0f)
        assertEquals(220f, sample.peakLuma, 2.0f)
        assertTrue("Contrast must be high for centered flashlight spot: ${sample.contrast}", sample.contrast >= 170f)
    }

    @Test
    fun `test off-reticle bright object outside 30 percent center is ignored`() {
        val buffer = createUniformBuffer(100, 100, 40)
        // Add a bright object at corner (10, 10) - outside central 30% ROI [35..65]
        for (dy in -3..3) {
            for (dx in -3..3) {
                setPoint(buffer, 100, 10 + dx, 10 + dy, 255)
            }
        }

        val sample = analyzer.analyze(buffer, 100, 100, 100, 1)

        // Peripheral bright object must be excluded from reticle analysis
        assertEquals(40f, sample.medianLuma, 1.0f)
        assertEquals(40f, sample.peakLuma, 1.0f)
        assertEquals(0f, sample.contrast, 0.1f)
    }
}
