package com.saitamagrs.flashnow.morse.detector

import android.graphics.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.ByteBuffer

class OpticalSignalDetectorTest {

    private lateinit var config: OpticalDetectorConfig
    private lateinit var detector: OpticalSignalDetector

    @Before
    fun setUp() {
        config = OpticalDetectorConfig(
            calibrationFramesCount = 10,
            onContrastOffset = 25.0f,
            offContrastOffset = 12.0f,
            minAbsoluteOnLuma = 35.0f,
            minConfirmationMs = 40L,
            minConfirmationFrames = 2,
            ambientAdaptAlpha = 0.01f
        )
        detector = OpticalSignalDetector(config)
        detector.start()
    }

    /**
     * Helper to simulate N frames of calibration at a fixed luminance.
     */
    private fun calibrate(ambient: Float = 30.0f, frameIntervalMs: Long = 33L, startTimestampMs: Long = 0L): Long {
        var t = startTimestampMs
        for (i in 0 until config.calibrationFramesCount) {
            detector.processSample(ambient, t)
            t += frameIntervalMs
        }
        return t
    }

    // =========================================================================
    // 1. CALIBRATION & INITIAL STATE
    // =========================================================================

    @Test
    fun `test initial calibration establishes ambient baseline`() {
        assertFalse(detector.isCalibrated)
        assertEquals(0.0f, detector.ambientLuma, 0.001f)

        // Feed 9 frames (less than calibrationFramesCount = 10)
        for (i in 0 until 9) {
            val transition = detector.processSample(30.0f, i * 33L)
            assertNull(transition)
            assertFalse(detector.isCalibrated)
        }

        // 10th frame completes calibration
        val transition = detector.processSample(30.0f, 9 * 33L)
        assertNull(transition)
        assertTrue(detector.isCalibrated)
        assertEquals(30.0f, detector.ambientLuma, 0.001f)
        assertEquals(LightState.OFF, detector.currentState)

        val diag = detector.diagnostics.value
        assertEquals(55.0f, diag.onThreshold, 0.001f)  // 30 + 25 = 55
        assertEquals(42.0f, diag.offThreshold, 0.001f) // 30 + 12 = 42
    }

    @Test
    fun `test minimum absolute ON threshold floor in dark environment`() {
        // In pitch black room with ambient = 5.0f
        // ambient + 25 = 30, but minAbsoluteOnLuma = 35, so threshold floor is 35
        for (i in 0 until 10) {
            detector.processSample(5.0f, i * 33L)
        }
        assertTrue(detector.isCalibrated)
        assertEquals(5.0f, detector.ambientLuma, 0.001f)
        assertEquals(35.0f, detector.diagnostics.value.onThreshold, 0.001f)
    }

    // =========================================================================
    // 2. STABLE SIGNALS & HYSTERESIS
    // =========================================================================

    @Test
    fun `test stable OFF signal maintains OFF state`() {
        var t = calibrate(30.0f)
        for (i in 0 until 30) {
            val transition = detector.processSample(30.0f + (i % 3), t)
            assertNull(transition)
            assertEquals(LightState.OFF, detector.currentState)
            t += 33L
        }
    }

    @Test
    fun `test stable ON signal maintains ON state after confirmation`() {
        var t = calibrate(30.0f)

        // Frame 1 of light ON (candidate starts)
        var trans = detector.processSample(180.0f, t)
        assertNull("Candidate frame 1 should not confirm immediately", trans)
        assertEquals(LightState.OFF, detector.currentState)

        // Frame 2 of light ON (confirmation duration >= 40ms)
        t += 45L
        trans = detector.processSample(180.0f, t)
        assertNotNull("Candidate frame 2 should confirm transition", trans)
        assertEquals(LightState.OFF, trans!!.previousState)
        assertEquals(LightState.ON, trans.newState)
        assertEquals(LightState.ON, detector.currentState)

        // Subsequent steady ON frames should maintain ON without new transitions
        for (i in 0 until 20) {
            t += 33L
            val steadyTrans = detector.processSample(180.0f, t)
            assertNull(steadyTrans)
            assertEquals(LightState.ON, detector.currentState)
        }
    }

    @Test
    fun `test hysteresis behavior near thresholds`() {
        var t = calibrate(30.0f) // onThresh = 55, offThresh = 42

        // 1. Signal at 50.0f (< onThresh 55.0f) does NOT trigger ON
        detector.processSample(50.0f, t)
        t += 33L
        detector.processSample(50.0f, t)
        assertEquals(LightState.OFF, detector.currentState)

        // 2. Signal exceeds 55.0f -> triggers ON
        t += 33L
        detector.processSample(60.0f, t)
        t += 45L
        detector.processSample(60.0f, t)
        assertEquals(LightState.ON, detector.currentState)

        // 3. Signal drops to 48.0f (between offThresh 42 and onThresh 55) -> REMAINS ON!
        t += 33L
        detector.processSample(48.0f, t)
        t += 45L
        detector.processSample(48.0f, t)
        assertEquals("Hysteresis band must keep state ON", LightState.ON, detector.currentState)

        // 4. Signal drops below offThresh (40.0f <= 42.0f) -> drops to OFF
        t += 33L
        detector.processSample(40.0f, t)
        t += 45L
        val offTrans = detector.processSample(40.0f, t)
        assertNotNull(offTrans)
        assertEquals(LightState.OFF, offTrans!!.newState)
        assertEquals(LightState.OFF, detector.currentState)
    }

    // =========================================================================
    // 3. NOISE REJECTION & TEMPORAL FILTERING
    // =========================================================================

    @Test
    fun `test brief noise spike rejected without state transition`() {
        var t = calibrate(30.0f)

        // Single isolated spike frame (glitch or hot flash)
        val spike = detector.processSample(240.0f, t)
        assertNull(spike)
        assertEquals(LightState.OFF, detector.currentState)

        // Next frame drops back to ambient
        t += 33L
        val normal = detector.processSample(30.0f, t)
        assertNull(normal)
        assertEquals(LightState.OFF, detector.currentState)

        // Spurious candidate state should be reset
        t += 33L
        detector.processSample(30.0f, t)
        assertEquals(LightState.OFF, detector.currentState)
    }

    @Test
    fun `test brief drop during ON state rejected without transition`() {
        var t = calibrate(30.0f)

        // Establish ON state
        t += 33L
        detector.processSample(200.0f, t)
        t += 45L
        detector.processSample(200.0f, t)
        assertEquals(LightState.ON, detector.currentState)

        // Single brief drop frame
        t += 33L
        val dip = detector.processSample(20.0f, t)
        assertNull(dip)
        assertEquals(LightState.ON, detector.currentState)

        // Next frame immediately returns to ON
        t += 33L
        val restored = detector.processSample(200.0f, t)
        assertNull(restored)
        assertEquals(LightState.ON, detector.currentState)
    }

    // =========================================================================
    // 4. REPRESENTATIVE MORSE PULSES (DOT & DASH)
    // =========================================================================

    @Test
    fun `test short pulse representative of 150ms dot`() {
        var t = calibrate(30.0f) // Calibrated up to t=330ms

        // Start 150ms light pulse at t=400ms
        t = 400L
        detector.processSample(200.0f, t) // frame 1
        t += 45L // 445ms
        val onTrans = detector.processSample(200.0f, t) // frame 2 (confirmed ON!)
        assertNotNull(onTrans)
        assertEquals(LightState.ON, onTrans!!.newState)

        // Pulse continues for ~150ms total (from 400ms to 550ms)
        t = 480L
        detector.processSample(200.0f, t)
        t = 520L
        detector.processSample(200.0f, t)

        // Light turns OFF at 550ms
        t = 550L
        detector.processSample(30.0f, t) // frame 1 of OFF
        t = 595L // 45ms later
        val offTrans = detector.processSample(30.0f, t) // confirmed OFF!
        assertNotNull(offTrans)
        assertEquals(LightState.OFF, offTrans!!.newState)

        // Verify measured pulse duration is within dot timing tolerance
        val measuredPulseDuration = offTrans.previousStateDurationMs
        assertTrue(
            "Measured dot duration $measuredPulseDuration ms should be close to 150ms (range 120..190)",
            measuredPulseDuration in 120L..190L
        )
    }

    @Test
    fun `test longer pulse representative of 450ms dash`() {
        var t = calibrate(30.0f)

        // Start 450ms light pulse at t=400ms
        t = 400L
        detector.processSample(200.0f, t)
        t += 45L
        val onTrans = detector.processSample(200.0f, t)
        assertNotNull(onTrans)

        // Sustain light ON for ~450ms (from 400ms to 850ms)
        var pulseTime = 480L
        while (pulseTime <= 850L) {
            detector.processSample(200.0f, pulseTime)
            pulseTime += 33L
        }

        // Light turns OFF at 860ms
        t = 860L
        detector.processSample(30.0f, t)
        t += 45L
        val offTrans = detector.processSample(30.0f, t)
        assertNotNull(offTrans)
        assertEquals(LightState.OFF, offTrans!!.newState)

        val measuredDashDuration = offTrans.previousStateDurationMs
        assertTrue(
            "Measured dash duration $measuredDashDuration ms should be close to 450ms (range 420..500)",
            measuredDashDuration in 420L..500L
        )
    }

    // =========================================================================
    // 5. LIFECYCLE, STOP, RESTART & STALE SESSION
    // =========================================================================

    @Test
    fun `test stop prevents sample processing and clears candidates`() {
        calibrate(30.0f)
        detector.stop()
        assertFalse(detector.isRunning)

        val trans = detector.processSample(200.0f, 1000L)
        assertNull(trans)
        assertEquals(LightState.OFF, detector.currentState)
    }

    @Test
    fun `test reset clears all state and requires fresh calibration`() {
        calibrate(30.0f)
        assertTrue(detector.isCalibrated)

        detector.reset()
        assertFalse(detector.isCalibrated)
        assertEquals(0.0f, detector.ambientLuma, 0.001f)
        assertEquals(LightState.OFF, detector.currentState)
        assertEquals(0L, detector.totalFramesProcessed)

        // Subsequent samples start fresh calibration
        for (i in 0 until 9) {
            detector.processSample(40.0f, i * 33L)
        }
        assertFalse(detector.isCalibrated)
        detector.processSample(40.0f, 9 * 33L)
        assertTrue(detector.isCalibrated)
        assertEquals(40.0f, detector.ambientLuma, 0.001f)
    }

    // =========================================================================
    // 6. TIMESTAMP MONOTONICITY & FRAME GAPS
    // =========================================================================

    @Test
    fun `test retrograde timestamp rejected`() {
        calibrate(30.0f)
        detector.processSample(30.0f, 500L)

        // Send backwards timestamp (400ms < 500ms)
        val result = detector.processSample(200.0f, 400L)
        assertNull("Retrograde timestamp must be rejected", result)
    }

    @Test
    fun `test large frame gap handled gracefully without crash`() {
        calibrate(30.0f)
        detector.processSample(30.0f, 500L)

        // 2-second frame gap (camera stutter / background stall)
        val result = detector.processSample(30.0f, 2500L)
        assertNull(result)
        assertEquals(LightState.OFF, detector.currentState)
    }

    // =========================================================================
    // 7. SLOW AMBIENT ADAPTATION
    // =========================================================================

    @Test
    fun `test ambient baseline adapts slowly during confirmed OFF only`() {
        calibrate(30.0f)
        assertEquals(30.0f, detector.ambientLuma, 0.001f)

        // Send 50 OFF frames with slightly higher room brightness (36.0f)
        var t = 500L
        for (i in 0 until 50) {
            detector.processSample(36.0f, t)
            t += 33L
        }

        // Ambient should have adapted partially towards 36.0f
        assertTrue(detector.ambientLuma > 30.0f)
        assertTrue(detector.ambientLuma < 36.0f)

        // Transition to ON
        val currentAmbient = detector.ambientLuma
        detector.processSample(200.0f, t)
        t += 45L
        detector.processSample(200.0f, t)
        assertEquals(LightState.ON, detector.currentState)

        // Send 50 ON frames: ambient must NOT adapt during ON!
        for (i in 0 until 50) {
            detector.processSample(200.0f, t)
            t += 33L
        }
        assertEquals("Ambient must not drift during active illumination", currentAmbient, detector.ambientLuma, 0.001f)
    }

    // =========================================================================
    // 8. SYNTHETIC BUFFER ROI LUMINANCE EXTRACTION
    // =========================================================================

    @Test
    fun `test synthetic buffer 95th percentile extracts localized bright flashlight spot`() {
        val width = 100
        val height = 100
        val rowStride = 100
        val pixelStride = 1
        val buffer = ByteBuffer.allocate(width * height)

        // Fill entire image with dark ambient luma (20)
        val byteArray = ByteArray(width * height) { 20.toByte() }

        // Place a localized bright flashlight spot (230) in the center ROI (rows 40..59, cols 40..59)
        for (r in 40 until 60) {
            for (c in 40 until 60) {
                byteArray[r * width + c] = 230.toByte()
            }
        }
        buffer.put(byteArray)
        buffer.flip()

        val histogram = IntArray(256)

        val luma95 = OpticalFrameAnalyzer.extractLuminanceFromBuffer(
            yBuffer = buffer,
            width = width,
            height = height,
            rowStride = rowStride,
            pixelStride = pixelStride,
            cropLeft = 0,
            cropTop = 0,
            cropRight = width,
            cropBottom = height,
            config = config.copy(percentileTarget = 0.95f),
            histogram = histogram
        )

        assertEquals("95th percentile must detect the 8% localized bright spot", 230.0f, luma95, 0.001f)
    }

    @Test
    fun `test synthetic buffer rejects single hot pixel noise`() {
        val width = 100
        val height = 100
        val buffer = ByteBuffer.allocate(width * height)

        // All pixels dark (20) except for 1 single hot pixel (255)
        for (i in 0 until (width * height - 1)) {
            buffer.put(20.toByte())
        }
        buffer.put(255.toByte()) // Single noisy pixel
        buffer.flip()

        val histogram = IntArray(256)

        val luma95 = OpticalFrameAnalyzer.extractLuminanceFromBuffer(
            yBuffer = buffer,
            width = width,
            height = height,
            rowStride = width,
            pixelStride = 1,
            cropLeft = 0,
            cropTop = 0,
            cropRight = width,
            cropBottom = height,
            config = config.copy(percentileTarget = 0.95f),
            histogram = histogram
        )

        assertEquals("95th percentile must ignore single hot pixel noise", 20.0f, luma95, 0.001f)
    }

    // =========================================================================
    // 9. LISTENER CALLBACKS
    // =========================================================================

    @Test
    fun `test listener callbacks invoked accurately`() {
        var stateChangedCount = 0
        var transitionCount = 0
        var lastDiag: OpticalDiagnostics? = null

        detector.listener = object : OpticalSignalListener {
            override fun onStateChanged(state: LightState, timestampMs: Long) {
                stateChangedCount++
            }

            override fun onOpticalTransition(transition: OpticalTransition) {
                transitionCount++
            }

            override fun onDiagnostics(diagnostics: OpticalDiagnostics) {
                lastDiag = diagnostics
            }
        }

        var t = calibrate(30.0f)
        assertNotNull(lastDiag)

        // Turn ON
        t += 33L
        detector.processSample(200.0f, t)
        t += 45L
        detector.processSample(200.0f, t)

        assertEquals(1, stateChangedCount)
        assertEquals(1, transitionCount)
        assertEquals(LightState.ON, lastDiag!!.currentState)
    }
}
