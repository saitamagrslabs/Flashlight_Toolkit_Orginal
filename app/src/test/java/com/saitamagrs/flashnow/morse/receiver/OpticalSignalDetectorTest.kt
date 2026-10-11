package com.saitamagrs.flashnow.morse.receiver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class OpticalSignalDetectorTest {

    private lateinit var detector: OpticalSignalDetector
    private val pulses = mutableListOf<Long>()
    private val gaps = mutableListOf<Long>()
    private var isLightOnState = false

    @Before
    fun setUp() {
        pulses.clear()
        gaps.clear()
        isLightOnState = false
        detector = OpticalSignalDetector(
            onPulseCallback = { duration -> pulses.add(duration) },
            onGapCallback = { duration -> gaps.add(duration) },
            onIdleGapCallback = {},
            onStateChangeCallback = { on -> isLightOnState = on }
        )
    }

    private fun calibrate(ambient: Float = 40f, contrast: Float = 0f, spot: Float = ambient, mean: Float = ambient) {
        for (i in 0 until OpticalSignalDetector.CALIBRATION_FRAMES_REQUIRED) {
            detector.processSample(
                contrast = contrast,
                brightSignal = spot,
                nowMs = 100L + (i * 33L),
                backgroundEstimate = ambient,
                meanLuma = mean
            )
        }
        assertTrue("Detector must be calibrated", detector.isCalibrated)
        assertEquals(OpticalSignalDetector.DetectorState.READY, detector.state)
    }

    @Test
    fun `test initial calibration sets resting baseline`() {
        calibrate(ambient = 50f, contrast = 2f, spot = 52f, mean = 50f)
        assertEquals(50f, detector.ambientBaseline, 2f)
        assertEquals(2f, detector.restingContrast, 2f)
        assertEquals(52f, detector.restingSpot, 2f)
    }

    @Test
    fun `test steady high ambient and specular reflection does NOT trigger Light ON`() {
        // In an indoor room: background ambient is 120, specular highlight is 255, contrast is 135
        calibrate(ambient = 120f, contrast = 135f, spot = 255f, mean = 125f)
        assertFalse("Must be Light OFF initially", detector.isLightOn)

        // Steady room lighting over multiple frames
        for (i in 0 until 10) {
            detector.processSample(
                contrast = 135f,
                brightSignal = 255f,
                nowMs = 500L + (i * 33L),
                backgroundEstimate = 120f,
                meanLuma = 125f
            )
            assertFalse("Steady room illumination must NEVER trigger Light ON", detector.isLightOn)
            assertEquals("No false transitions", 0, detector.transitionCount)
        }
    }

    @Test
    fun `test flashlight pulse against high ambient background triggers Light ON and OFF`() {
        // Calibrate on bright room: ambient = 100, spot = 120, contrast = 20, mean = 105
        calibrate(ambient = 100f, contrast = 20f, spot = 120f, mean = 105f)

        // Flashlight turns ON at t=500ms: spot jumps to 240 (+120), contrast jumps to 130 (+110)
        detector.processSample(130f, 240f, 500L, 110f, 125f)
        assertFalse("Glitch filter holds state (< 25ms)", detector.isLightOn)

        detector.processSample(130f, 240f, 535L, 110f, 125f)
        assertTrue("Flashlight pulse must trigger Light ON", detector.isLightOn)
        assertEquals(OpticalSignalDetector.DetectorState.RECEIVING, detector.state)

        // Flashlight turns OFF at t=685ms (150ms dot pulse)
        detector.processSample(20f, 120f, 685L, 100f, 105f)
        detector.processSample(20f, 120f, 720L, 100f, 105f)
        assertFalse("Must return to Light OFF", detector.isLightOn)

        assertEquals(1, pulses.size)
        assertEquals(185L, pulses[0]) // 685L - 500L
    }

    @Test
    fun `test contrast elevation triggers Light ON after glitch filter`() {
        calibrate(ambient = 40f, contrast = 0f, spot = 40f, mean = 40f)

        // Frame 1: Elevated contrast 20.0f (> 10.0f threshold) at t=300ms
        detector.processSample(20f, 60f, 300L, 40f, 40f)
        assertFalse("Glitch filter must hold state (< 25ms)", detector.isLightOn)

        // Frame 2: Elevated contrast persists at t=335ms (35ms elapsed >= 25ms)
        detector.processSample(20f, 60f, 335L, 40f, 40f)
        assertTrue("Must transition to Light ON", detector.isLightOn)
        assertEquals(OpticalSignalDetector.DetectorState.RECEIVING, detector.state)
    }

    @Test
    fun `test transient noise spike shorter than 25ms is rejected`() {
        calibrate(ambient = 40f, contrast = 0f, spot = 40f, mean = 40f)

        // Spike at t=300ms
        detector.processSample(30f, 70f, 300L, 40f, 40f)
        assertFalse(detector.isLightOn)

        // Returns to dark at t=315ms (15ms elapsed < 25ms)
        detector.processSample(0f, 40f, 315L, 40f, 40f)
        assertFalse("Noise spike must be rejected", detector.isLightOn)
    }

    @Test
    fun `test pulse duration measurement on transition to OFF`() {
        calibrate(ambient = 40f, contrast = 0f, spot = 40f, mean = 40f)

        // Turn ON at t=300ms..335ms
        detector.processSample(50f, 90f, 300L, 40f, 40f)
        detector.processSample(50f, 90f, 335L, 40f, 40f)
        assertTrue(detector.isLightOn)

        // Turn OFF at t=485ms (pulse duration ~150ms)
        detector.processSample(0f, 40f, 485L, 40f, 40f)
        detector.processSample(0f, 40f, 520L, 40f, 40f) // 35ms elapsed
        assertFalse("Must turn OFF", detector.isLightOn)

        assertEquals(1, pulses.size)
        assertEquals(185L, pulses[0]) // 485L - 300L
    }

    @Test
    fun `test hysteresis prevents oscillation in moderate dip`() {
        calibrate(ambient = 40f, contrast = 0f, spot = 40f, mean = 40f)

        // Turn ON: contrast 20.0f
        detector.processSample(20f, 60f, 300L, 40f, 40f)
        detector.processSample(20f, 60f, 335L, 40f, 40f)
        assertTrue(detector.isLightOn)

        // Moderate dip: contrast 6.0f (above 5.0f OFF threshold)
        detector.processSample(6f, 46f, 370L, 40f, 40f)
        detector.processSample(6f, 46f, 405L, 40f, 40f)
        assertTrue("Hysteresis must hold ON state", detector.isLightOn)

        // Drop below OFF threshold: contrast 2.0f (< 5.0f)
        detector.processSample(2f, 42f, 440L, 40f, 40f)
        detector.processSample(2f, 42f, 475L, 40f, 40f)
        assertFalse("Must turn OFF once below 5.0f", detector.isLightOn)
    }

    @Test
    fun `test slow ambient drift adapts without false triggers`() {
        calibrate(ambient = 40f, contrast = 0f, spot = 40f, mean = 40f)

        // Slowly drift ambient up by 6 units over 30 frames (0.2 units per frame)
        for (i in 1..30) {
            val driftLuma = 40f + (i * 0.2f)
            detector.processSample(0f, driftLuma, 500L + (i * 33L), driftLuma, driftLuma)
            assertFalse("Slow ambient drift must not trigger Light ON", detector.isLightOn)
        }

        assertTrue("Resting baseline must have adapted upward", detector.restingSpot > 43f)
    }
}
