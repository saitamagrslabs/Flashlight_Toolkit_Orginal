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

    private fun calibrate(ambient: Float = 40f, contrast: Float = 0f) {
        for (i in 0 until OpticalSignalDetector.CALIBRATION_FRAMES_REQUIRED) {
            detector.processSample(contrast, ambient, 100L + (i * 33L), ambient)
        }
        assertTrue("Detector must be calibrated", detector.isCalibrated)
        assertEquals(OpticalSignalDetector.DetectorState.READY, detector.state)
    }

    @Test
    fun `test initial calibration sets resting baseline`() {
        calibrate(ambient = 50f, contrast = 2f)
        assertEquals(50f, detector.ambientBaseline, 2f)
        assertEquals(2f, detector.restingContrast, 2f)
    }

    @Test
    fun `test contrast elevation triggers Light ON after glitch filter`() {
        calibrate(ambient = 40f, contrast = 0f)

        // Frame 1: Elevated contrast 20.0f (> 8.5f threshold) at t=300ms
        detector.processSample(20f, 60f, 300L, 40f)
        assertFalse("Glitch filter must hold state (< 25ms)", detector.isLightOn)

        // Frame 2: Elevated contrast persists at t=335ms (35ms elapsed >= 25ms)
        detector.processSample(20f, 60f, 335L, 40f)
        assertTrue("Must transition to Light ON", detector.isLightOn)
        assertEquals(OpticalSignalDetector.DetectorState.RECEIVING, detector.state)
    }

    @Test
    fun `test transient noise spike shorter than 25ms is rejected`() {
        calibrate(ambient = 40f, contrast = 0f)

        // Spike at t=300ms
        detector.processSample(30f, 70f, 300L, 40f)
        assertFalse(detector.isLightOn)

        // Returns to dark at t=315ms (15ms elapsed < 25ms)
        detector.processSample(0f, 40f, 315L, 40f)
        assertFalse("Noise spike must be rejected", detector.isLightOn)
    }

    @Test
    fun `test pulse duration measurement on transition to OFF`() {
        calibrate(ambient = 40f, contrast = 0f)

        // Turn ON at t=300ms..335ms
        detector.processSample(50f, 90f, 300L, 40f)
        detector.processSample(50f, 90f, 335L, 40f)
        assertTrue(detector.isLightOn)

        // Turn OFF at t=485ms (pulse duration ~150ms)
        detector.processSample(0f, 40f, 485L, 40f)
        detector.processSample(0f, 40f, 520L, 40f) // 35ms elapsed
        assertFalse("Must turn OFF", detector.isLightOn)

        assertEquals(1, pulses.size)
        assertEquals(185L, pulses[0]) // 485L - 300L
    }

    @Test
    fun `test hysteresis prevents oscillation in moderate dip`() {
        calibrate(ambient = 40f, contrast = 0f)

        // Turn ON: contrast 20.0f
        detector.processSample(20f, 60f, 300L, 40f)
        detector.processSample(20f, 60f, 335L, 40f)
        assertTrue(detector.isLightOn)

        // Moderate dip: contrast 6.0f (above 4.5f OFF threshold)
        detector.processSample(6f, 46f, 370L, 40f)
        detector.processSample(6f, 46f, 405L, 40f)
        assertTrue("Hysteresis must hold ON state", detector.isLightOn)

        // Drop below OFF threshold: contrast 2.0f (< 4.5f)
        detector.processSample(2f, 42f, 440L, 40f)
        detector.processSample(2f, 42f, 475L, 40f)
        assertFalse("Must turn OFF once below 4.5f", detector.isLightOn)
    }
}
