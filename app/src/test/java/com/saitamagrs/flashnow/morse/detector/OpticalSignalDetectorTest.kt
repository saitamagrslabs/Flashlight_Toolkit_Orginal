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

    @Test
    fun `test candidate reset on excessive frame gap for OFF to ON`() {
        calibrate(30.0f) // Ambient 30.0f, onThreshold = 55.0f

        // Frame 1 of ON candidate at t = 500ms
        val firstCandidate = detector.processSample(200.0f, 500L)
        assertNull("First candidate frame must not confirm immediately", firstCandidate)
        assertEquals(LightState.OFF, detector.currentState)

        // Excessive frame gap of 4000ms (> maxCandidateIntervalMs = 120ms)
        // Arrives at t = 4500ms with qualifying ON luma
        val staleCandidate = detector.processSample(200.0f, 4500L)
        assertNull("Qualifying sample after excessive frame gap must reset candidate and NOT confirm", staleCandidate)
        assertEquals("State must remain OFF because previous candidate was stale", LightState.OFF, detector.currentState)
    }

    @Test
    fun `test candidate reset on excessive frame gap for ON to OFF`() {
        calibrate(30.0f)

        // Establish confirmed ON state
        detector.processSample(200.0f, 400L)
        detector.processSample(200.0f, 445L)
        assertEquals(LightState.ON, detector.currentState)

        // Frame 1 of OFF candidate at t = 800ms
        val firstOffCandidate = detector.processSample(30.0f, 800L)
        assertNull(firstOffCandidate)
        assertEquals(LightState.ON, detector.currentState)

        // Excessive frame gap of 3000ms (> maxCandidateIntervalMs = 120ms) at t = 3800ms
        val staleOffCandidate = detector.processSample(30.0f, 3800L)
        assertNull("Qualifying sample after excessive gap must reset candidate and NOT confirm", staleOffCandidate)
        assertEquals("State must remain ON because previous candidate was stale", LightState.ON, detector.currentState)
    }

    @Test
    fun `test subsequent closely spaced frames confirm transition after stale candidate reset`() {
        calibrate(30.0f)

        // Candidate 1 at t = 500ms
        detector.processSample(200.0f, 500L)
        assertEquals(LightState.OFF, detector.currentState)

        // Excessive gap at t = 3000ms (resets stale candidate, becomes frame 1 of new candidate)
        val staleSample = detector.processSample(200.0f, 3000L)
        assertNull(staleSample)
        assertEquals(LightState.OFF, detector.currentState)

        // Closely spaced frame at t = 3045ms (interval 45ms <= 120ms, duration 45ms >= 40ms)
        val confirmed = detector.processSample(200.0f, 3045L)
        assertNotNull("Subsequent closely-spaced frame must confirm transition", confirmed)
        assertEquals(LightState.ON, confirmed!!.newState)
        assertEquals(LightState.ON, detector.currentState)
    }

    @Test
    fun `test realistic frame intervals at 20fps and 30fps confirm transitions reliably`() {
        calibrate(30.0f)

        // 1. Test 30 fps (33ms interval): 150ms dot at t=500ms
        var t = 500L
        detector.processSample(200.0f, t) // frame 1 (33ms later is not yet 40ms)
        t += 33L // 533ms (duration 33ms < 40ms)
        var trans = detector.processSample(200.0f, t)
        assertNull("33ms is < 40ms minConfirmationMs", trans)

        t += 33L // 566ms (duration 66ms >= 40ms, frameCount 3 >= 2)
        trans = detector.processSample(200.0f, t)
        assertNotNull("Frame 3 at 30 fps confirms transition", trans)
        assertEquals(LightState.ON, trans!!.newState)

        // Turn OFF at 30 fps
        t = 650L
        detector.processSample(30.0f, t)
        t += 45L // 695ms
        val offTrans = detector.processSample(30.0f, t)
        assertNotNull(offTrans)
        assertEquals(LightState.OFF, offTrans!!.newState)

        // 2. Test 20 fps (50ms interval):
        t = 1000L
        detector.processSample(200.0f, t) // frame 1
        t += 50L // 1050ms (interval 50ms <= 120ms, duration 50ms >= 40ms, frameCount 2 >= 2)
        val onTrans20Fps = detector.processSample(200.0f, t)
        assertNotNull("Frame 2 at 20 fps confirms transition", onTrans20Fps)
        assertEquals(LightState.ON, onTrans20Fps!!.newState)
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
    fun `test synthetic buffer 95th percentile extracts localized 16 percent bright spot`() {
        val width = 100
        val height = 100
        val rowStride = 100
        val pixelStride = 1
        val buffer = ByteBuffer.allocate(width * height)

        // Fill entire image with dark ambient luma (20)
        val byteArray = ByteArray(width * height) { 20.toByte() }

        // ROI is center 50%: rows 25..74, cols 25..74 -> 50 x 50 = 2500 pixels
        // Place a localized bright spot (230) in rows 40..59 (20 rows) x cols 40..59 (20 cols) = 400 pixels
        // 400 / 2500 = 16.0% of the sampled ROI
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

        assertEquals("95th percentile must detect 16% bright spot (400/2500 pixels)", 230.0f, luma95, 0.001f)
    }

    @Test
    fun `test synthetic buffer 95th percentile detects 6 percent bright spot`() {
        val width = 100
        val height = 100
        val buffer = ByteBuffer.allocate(width * height)

        val byteArray = ByteArray(width * height) { 20.toByte() }

        // ROI is center 50%: rows 25..74, cols 25..74 -> 50 x 50 = 2500 pixels
        // Place a localized bright spot (230) in rows 45..54 (10 rows) x cols 43..57 (15 cols) = 150 pixels
        // 150 / 2500 = 6.0% of the sampled ROI.
        // Since 6.0% > (1.0 - 0.95 = 5.0%), the 95th percentile must detect the bright spot!
        for (r in 45 until 55) {
            for (c in 43 until 58) {
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
            rowStride = width,
            pixelStride = 1,
            cropLeft = 0,
            cropTop = 0,
            cropRight = width,
            cropBottom = height,
            config = config.copy(percentileTarget = 0.95f),
            histogram = histogram
        )

        assertEquals("95th percentile must detect 6% bright spot (150/2500 pixels)", 230.0f, luma95, 0.001f)
    }

    @Test
    fun `test synthetic buffer 95th percentile ignores sub-5 percent bright spot`() {
        val width = 100
        val height = 100
        val buffer = ByteBuffer.allocate(width * height)

        val byteArray = ByteArray(width * height) { 20.toByte() }

        // ROI is center 50%: rows 25..74, cols 25..74 -> 50 x 50 = 2500 pixels
        // Place a localized bright spot (230) in rows 45..54 (10 rows) x cols 45..54 (10 cols) = 100 pixels
        // 100 / 2500 = 4.0% of the sampled ROI.
        // Since 4.0% < (1.0 - 0.95 = 5.0%), the 95th percentile mathematically remains at ambient luma (20)
        for (r in 45 until 55) {
            for (c in 45 until 55) {
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
            rowStride = width,
            pixelStride = 1,
            cropLeft = 0,
            cropTop = 0,
            cropRight = width,
            cropBottom = height,
            config = config.copy(percentileTarget = 0.95f, useBoundedTopPixel = false),
            histogram = histogram
        )

        assertEquals("95th percentile must ignore 4% spot below 5% mathematical threshold", 20.0f, luma95, 0.001f)
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

    // =========================================================================
    // 10. DISTANCE SENSITIVITY & BOUNDED TOP-PIXEL STATISTIC (PHASE R5)
    // =========================================================================

    private fun createSyntheticFrame(
        width: Int,
        height: Int,
        ambientLuma: Byte = 20,
        spotPixels: Int = 0,
        spotLuma: Byte = 230.toByte(),
        cropRect: Rect? = null
    ): Pair<ByteBuffer, Int> {
        val buffer = ByteBuffer.allocate(width * height)
        val array = ByteArray(width * height) { ambientLuma }
        val cLeft = cropRect?.left ?: (width * 0.25f).toInt()
        val cTop = cropRect?.top ?: (height * 0.25f).toInt()
        val cWidth = (cropRect?.width() ?: (width * 0.5f).toInt()).coerceAtLeast(1)
        val cHeight = (cropRect?.height() ?: (height * 0.5f).toInt()).coerceAtLeast(1)

        val startX = cLeft + (cWidth / 2)
        val startY = cTop + (cHeight / 2)

        var placed = 0
        var radius = 0
        while (placed < spotPixels && radius < cWidth) {
            for (dy in -radius..radius) {
                for (dx in -radius..radius) {
                    val px = startX + dx
                    val py = startY + dy
                    if (px in cLeft until (cLeft + cWidth) && py in cTop until (cTop + cHeight)) {
                        val idx = py * width + px
                        if (array[idx] != spotLuma) {
                            array[idx] = spotLuma
                            placed++
                            if (placed >= spotPixels) break
                        }
                    }
                }
                if (placed >= spotPixels) break
            }
            radius++
        }
        buffer.put(array)
        buffer.flip()
        return Pair(buffer, placed)
    }

    @Test
    fun `test bounded top-pixel detects 50-pixel spot in 640x480 frame`() {
        val width = 640
        val height = 480
        val (buffer, placed) = createSyntheticFrame(width, height, spotPixels = 50, spotLuma = 230.toByte())
        assertEquals(50, placed)

        val histogram = IntArray(256)
        val r5Config = OpticalDetectorConfig(useBoundedTopPixel = true, spotPixelFraction = 0.0004f, minSpotPixels = 6)
        val analysis = OpticalFrameAnalyzer.extractRoiAnalysisFromBuffer(
            yBuffer = buffer,
            width = width,
            height = height,
            rowStride = width,
            pixelStride = 1,
            cropRect = null,
            config = r5Config,
            histogram = histogram
        )

        assertEquals("Peak luminance should match spot", 230.0f, analysis.maxLuma, 0.001f)
        assertEquals("Bright pixel count should match placed count", 50, analysis.brightPixelCount)
        assertEquals("Representative luma must detect spot instead of ambient 20", 230.0f, analysis.representativeLuma, 0.001f)
    }

    @Test
    fun `test bounded top-pixel detects 100-pixel and 150-pixel spots`() {
        val width = 640
        val height = 480
        val histogram = IntArray(256)
        val r5Config = OpticalDetectorConfig(useBoundedTopPixel = true)

        for (spotSize in listOf(100, 150)) {
            val (buffer, _) = createSyntheticFrame(width, height, spotPixels = spotSize, spotLuma = 225.toByte())
            val analysis = OpticalFrameAnalyzer.extractRoiAnalysisFromBuffer(
                yBuffer = buffer,
                width = width,
                height = height,
                rowStride = width,
                pixelStride = 1,
                cropRect = null,
                config = r5Config,
                histogram = histogram
            )
            assertEquals("Representative luma for $spotSize px spot", 225.0f, analysis.representativeLuma, 0.001f)
            assertEquals("Bright pixels for $spotSize px spot", spotSize, analysis.brightPixelCount)
            assertEquals("Max luma for $spotSize px spot", 225.0f, analysis.maxLuma, 0.001f)
        }
    }

    @Test
    fun `test bounded top-pixel across multiple resolutions`() {
        val histogram = IntArray(256)
        val r5Config = OpticalDetectorConfig(useBoundedTopPixel = true, minSpotPixels = 20, maxSpotPixels = 200, spotPixelFraction = 0.0004f)
        val resolutions = listOf(
            Pair(320, 240),
            Pair(640, 480),
            Pair(1280, 720),
            Pair(1920, 1080)
        )

        for ((w, h) in resolutions) {
            val sampleCount = (w * 0.5f).toInt() * (h * 0.5f).toInt()
            val expectedMinRequired = (sampleCount * 0.0004f).toInt().coerceIn(20, 200)
            val spotSize = (expectedMinRequired + 10).coerceAtLeast(30)

            val (buffer, _) = createSyntheticFrame(w, h, spotPixels = spotSize, spotLuma = 240.toByte())
            val analysis = OpticalFrameAnalyzer.extractRoiAnalysisFromBuffer(
                yBuffer = buffer,
                width = w,
                height = h,
                rowStride = w,
                pixelStride = 1,
                cropRect = null,
                config = r5Config,
                histogram = histogram
            )

            assertEquals("Resolution ${w}x${h} must detect representative spot luma", 240.0f, analysis.representativeLuma, 0.001f)
            assertEquals("Resolution ${w}x${h} peak luma", 240.0f, analysis.maxLuma, 0.001f)
            assertTrue("Bright pixel count must be >= spotSize", analysis.brightPixelCount >= spotSize)
        }
    }

    @Test
    fun `test bounded top-pixel rejects isolated hot pixels between 1 and 5`() {
        val width = 640
        val height = 480
        val histogram = IntArray(256)
        val r5Config = OpticalDetectorConfig(useBoundedTopPixel = true, minSpotPixels = 6)

        for (hotPixelCount in 1..5) {
            val (buffer, _) = createSyntheticFrame(width, height, spotPixels = hotPixelCount, spotLuma = 255.toByte())
            val analysis = OpticalFrameAnalyzer.extractRoiAnalysisFromBuffer(
                yBuffer = buffer,
                width = width,
                height = height,
                rowStride = width,
                pixelStride = 1,
                cropRect = null,
                config = r5Config,
                histogram = histogram
            )

            assertEquals("Max luma captures the hot pixel", 255.0f, analysis.maxLuma, 0.001f)
            assertEquals("Representative luma must reject $hotPixelCount hot pixels and remain at ambient", 20.0f, analysis.representativeLuma, 0.001f)
        }
    }

    @Test
    fun `test dark ambient cluster does not falsely trigger`() {
        val width = 640
        val height = 480
        val histogram = IntArray(256)
        val (buffer, _) = createSyntheticFrame(width, height, ambientLuma = 15, spotPixels = 50, spotLuma = 25.toByte())
        val r5Config = OpticalDetectorConfig(minAbsoluteOnLuma = 35.0f)
        val analysis = OpticalFrameAnalyzer.extractRoiAnalysisFromBuffer(
            yBuffer = buffer,
            width = width,
            height = height,
            rowStride = width,
            pixelStride = 1,
            cropRect = null,
            config = r5Config,
            histogram = histogram
        )

        assertEquals("Bright pixel count must be 0 below minAbsoluteOnLuma", 0, analysis.brightPixelCount)
        assertEquals(25.0f, analysis.representativeLuma, 0.001f)

        // Process through detector
        val det = OpticalSignalDetector(r5Config).apply { start() }
        for (i in 0 until 10) { det.processSample(15.0f, i * 33L) }
        val transition = det.processSample(analysis.representativeLuma, 400L, maxLuma = analysis.maxLuma, brightPixelCount = analysis.brightPixelCount)
        assertNull("Sub-threshold cluster must not trigger transition", transition)
        assertEquals(LightState.OFF, det.currentState)
    }

    @Test
    fun `test ambient step change does not cause false ON transition`() {
        val det = OpticalSignalDetector(config).apply { start() }
        var t = 0L
        for (i in 0 until 10) {
            det.processSample(20.0f, t)
            t += 33L
        }
        assertTrue(det.isCalibrated)
        assertEquals(LightState.OFF, det.currentState)

        // Gentle ambient step from 20 to 24 (within onContrastOffset = 25)
        val transition = det.processSample(24.0f, t)
        assertNull("Gentle ambient rise must not trigger ON", transition)
        assertEquals(LightState.OFF, det.currentState)
    }

    @Test
    fun `test 50-pixel spot triggers confirmed ON transition in detector`() {
        val width = 640
        val height = 480
        val histogram = IntArray(256)
        val r5Config = OpticalDetectorConfig(
            calibrationFramesCount = 10,
            onContrastOffset = 25.0f,
            minAbsoluteOnLuma = 35.0f,
            minConfirmationMs = 40L,
            minConfirmationFrames = 2
        )
        val det = OpticalSignalDetector(r5Config).apply { start() }

        // Calibrate with ambient frames (luma 20)
        var t = 0L
        for (i in 0 until 10) {
            det.processSample(20.0f, t)
            t += 33L
        }
        assertTrue(det.isCalibrated)

        // Generate 50-pixel spot frame (luma 220)
        val (buffer, _) = createSyntheticFrame(width, height, ambientLuma = 20, spotPixels = 50, spotLuma = 220.toByte())
        val analysis = OpticalFrameAnalyzer.extractRoiAnalysisFromBuffer(
            yBuffer = buffer,
            width = width,
            height = height,
            rowStride = width,
            pixelStride = 1,
            cropRect = null,
            config = r5Config,
            histogram = histogram
        )

        // Feed candidate frame 1
        t += 33L
        val t1 = det.processSample(analysis.representativeLuma, t, maxLuma = analysis.maxLuma, brightPixelCount = analysis.brightPixelCount)
        assertNull("Candidate frame 1 should not confirm yet", t1)
        assertEquals(LightState.OFF, det.currentState)

        // Feed candidate frame 2 after minConfirmationMs
        t += 45L
        val t2 = det.processSample(analysis.representativeLuma, t, maxLuma = analysis.maxLuma, brightPixelCount = analysis.brightPixelCount)
        assertNotNull("Candidate frame 2 must confirm ON transition", t2)
        assertEquals(LightState.ON, t2!!.newState)
        assertEquals(LightState.ON, det.currentState)
    }

    @Test
    fun `test close-range full flood is detected identically`() {
        val width = 640
        val height = 480
        val (buffer, _) = createSyntheticFrame(width, height, ambientLuma = 240.toByte(), spotPixels = 0)
        val histogram = IntArray(256)
        val analysis = OpticalFrameAnalyzer.extractRoiAnalysisFromBuffer(
            yBuffer = buffer,
            width = width,
            height = height,
            rowStride = width,
            pixelStride = 1,
            cropRect = null,
            config = OpticalDetectorConfig(),
            histogram = histogram
        )
        assertEquals(240.0f, analysis.representativeLuma, 0.001f)
        assertEquals(240.0f, analysis.maxLuma, 0.001f)
        assertEquals(analysis.sampleCount, analysis.brightPixelCount)
    }

    @Test
    fun `test OpticalDiagnostics includes maxLuma and brightPixelCount`() {
        val det = OpticalSignalDetector(config).apply { start() }
        var t = 0L
        for (i in 0 until 10) {
            det.processSample(20.0f, t, maxLuma = 25.0f, brightPixelCount = 0)
            t += 33L
        }
        det.processSample(180.0f, t, maxLuma = 250.0f, brightPixelCount = 75)

        val diag = det.diagnostics.value
        assertEquals(250.0f, diag.maxLuma, 0.001f)
        assertEquals(75, diag.brightPixelCount)
        assertEquals(180.0f, diag.measuredLuma, 0.001f)
    }

    @Test
    fun `test reset clears maxLuma and brightPixelCount in diagnostics`() {
        val det = OpticalSignalDetector(config).apply { start() }
        det.processSample(200.0f, 100L, maxLuma = 255.0f, brightPixelCount = 100)
        assertEquals(255.0f, det.diagnostics.value.maxLuma, 0.001f)

        det.reset()
        assertEquals(0.0f, det.diagnostics.value.maxLuma, 0.001f)
        assertEquals(0, det.diagnostics.value.brightPixelCount)
    }

    // =========================================================================
    // 11. SMALL-SPOT FALL-THROUGH CORRECTION & REGRESSION SUITE (PHASE R5.2)
    // =========================================================================

    @Test
    fun `test small spots of 6, 8, 10, 15, 20, and 29 pixels against ambient`() {
        val width = 640
        val height = 480
        val histogram = IntArray(256)
        val testConfig = OpticalDetectorConfig(useBoundedTopPixel = true, minSpotPixels = 6)
        val spotSizes = listOf(6, 8, 10, 15, 20, 29)

        for (spotSize in spotSizes) {
            val (buffer, placed) = createSyntheticFrame(width, height, ambientLuma = 20, spotPixels = spotSize, spotLuma = 230.toByte())
            assertEquals(spotSize, placed)
            val analysis = OpticalFrameAnalyzer.extractRoiAnalysisFromBuffer(
                yBuffer = buffer,
                width = width,
                height = height,
                rowStride = width,
                pixelStride = 1,
                cropRect = null,
                config = testConfig,
                histogram = histogram
            )

            assertEquals("Spot of $spotSize px must have peak 230", 230.0f, analysis.maxLuma, 0.001f)
            assertEquals("Spot of $spotSize px bright count", spotSize, analysis.brightPixelCount)
            assertEquals("Spot of $spotSize px must NOT fall through to ambient 20", 230.0f, analysis.representativeLuma, 0.001f)
        }
    }

    @Test
    fun `test small spots across multiple ROI sizes without fall-through`() {
        val histogram = IntArray(256)
        val testConfig = OpticalDetectorConfig(useBoundedTopPixel = true, minSpotPixels = 6)
        val resolutions = listOf(
            Pair(320, 240),
            Pair(640, 480),
            Pair(1280, 720),
            Pair(1920, 1080)
        )

        for ((w, h) in resolutions) {
            for (spotSize in listOf(8, 15)) {
                val (buffer, _) = createSyntheticFrame(w, h, ambientLuma = 20, spotPixels = spotSize, spotLuma = 220.toByte())
                val analysis = OpticalFrameAnalyzer.extractRoiAnalysisFromBuffer(
                    yBuffer = buffer,
                    width = w,
                    height = h,
                    rowStride = w,
                    pixelStride = 1,
                    cropRect = null,
                    config = testConfig,
                    histogram = histogram
                )
                assertEquals("Resolution ${w}x${h} with $spotSize px spot must detect 220", 220.0f, analysis.representativeLuma, 0.001f)
                assertEquals("Resolution ${w}x${h} peak", 220.0f, analysis.maxLuma, 0.001f)
                assertEquals("Resolution ${w}x${h} bright count", spotSize, analysis.brightPixelCount)
            }
        }
    }

    @Test
    fun `test isolated bright pixels 1 to 5 do not trigger ON transition`() {
        val det = OpticalSignalDetector(config.copy(minSpotPixels = 6)).apply { start() }
        var t = 0L
        for (i in 0 until 10) {
            det.processSample(20.0f, t)
            t += 33L
        }
        assertTrue(det.isCalibrated)

        val histogram = IntArray(256)
        val cfg = OpticalDetectorConfig(useBoundedTopPixel = true, minSpotPixels = 6)

        for (hotPixels in 1..5) {
            val (buffer, _) = createSyntheticFrame(640, 480, ambientLuma = 20, spotPixels = hotPixels, spotLuma = 255.toByte())
            val analysis = OpticalFrameAnalyzer.extractRoiAnalysisFromBuffer(
                yBuffer = buffer,
                width = 640,
                height = 480,
                rowStride = 640,
                pixelStride = 1,
                cropRect = null,
                config = cfg,
                histogram = histogram
            )

            // Representative luma must remain at ambient 20
            assertEquals(20.0f, analysis.representativeLuma, 0.001f)
            assertEquals(255.0f, analysis.maxLuma, 0.001f)
            assertEquals(hotPixels, analysis.brightPixelCount)

            // Process through detector - must not transition
            t += 33L
            val transition = det.processSample(
                analysis.representativeLuma,
                t,
                maxLuma = analysis.maxLuma,
                brightPixelCount = analysis.brightPixelCount
            )
            assertNull("Hot pixel noise of $hotPixels px must not trigger ON transition", transition)
            assertEquals(LightState.OFF, det.currentState)
        }
    }

    @Test
    fun `test sub-threshold bright-pixel group below minAbsoluteOnLuma does not trigger ON`() {
        val histogram = IntArray(256)
        val cfg = OpticalDetectorConfig(useBoundedTopPixel = true, minSpotPixels = 6, minAbsoluteOnLuma = 35.0f)
        // Group of 10 pixels with luma 30 (below minAbsoluteOnLuma = 35) in ambient 15
        val (buffer, _) = createSyntheticFrame(640, 480, ambientLuma = 15, spotPixels = 10, spotLuma = 30.toByte())
        val analysis = OpticalFrameAnalyzer.extractRoiAnalysisFromBuffer(
            yBuffer = buffer,
            width = 640,
            height = 480,
            rowStride = 640,
            pixelStride = 1,
            cropRect = null,
            config = cfg,
            histogram = histogram
        )

        assertEquals("Sub-floor pixels must have 0 brightPixelCount", 0, analysis.brightPixelCount)
        assertEquals(30.0f, analysis.maxLuma, 0.001f)
        // Since brightPixelCount < 6, representativeLuma requires requiredTopPixels, landing at ambient 15
        assertEquals(15.0f, analysis.representativeLuma, 0.001f)
    }

    @Test
    fun `test uniform dark ambient and bright ambient scenes`() {
        val histogram = IntArray(256)
        val cfg = OpticalDetectorConfig(useBoundedTopPixel = true, minSpotPixels = 6)

        // Dark scene (luma 5)
        val (darkBuffer, _) = createSyntheticFrame(640, 480, ambientLuma = 5, spotPixels = 0)
        val darkAnalysis = OpticalFrameAnalyzer.extractRoiAnalysisFromBuffer(
            yBuffer = darkBuffer, width = 640, height = 480, rowStride = 640, pixelStride = 1,
            cropRect = null, config = cfg, histogram = histogram
        )
        assertEquals(5.0f, darkAnalysis.representativeLuma, 0.001f)
        assertEquals(5.0f, darkAnalysis.maxLuma, 0.001f)
        assertEquals(0, darkAnalysis.brightPixelCount)

        // Bright scene (luma 80)
        val (brightBuffer, _) = createSyntheticFrame(640, 480, ambientLuma = 80, spotPixels = 0)
        val brightAnalysis = OpticalFrameAnalyzer.extractRoiAnalysisFromBuffer(
            yBuffer = brightBuffer, width = 640, height = 480, rowStride = 640, pixelStride = 1,
            cropRect = null, config = cfg, histogram = histogram
        )
        assertEquals(80.0f, brightAnalysis.representativeLuma, 0.001f)
        assertEquals(80.0f, brightAnalysis.maxLuma, 0.001f)
        assertEquals(brightAnalysis.sampleCount, brightAnalysis.brightPixelCount)
    }

    @Test
    fun `test small 8-pixel spot confirms ON transition and decodes dot pulse`() {
        val cfg = OpticalDetectorConfig(
            calibrationFramesCount = 10,
            onContrastOffset = 25.0f,
            minAbsoluteOnLuma = 35.0f,
            minConfirmationMs = 40L,
            minConfirmationFrames = 2,
            minSpotPixels = 6,
            useBoundedTopPixel = true
        )
        val det = OpticalSignalDetector(cfg).apply { start() }

        var t = 0L
        for (i in 0 until 10) {
            det.processSample(20.0f, t)
            t += 33L
        }
        assertTrue(det.isCalibrated)

        val histogram = IntArray(256)
        val (buffer, _) = createSyntheticFrame(640, 480, ambientLuma = 20, spotPixels = 8, spotLuma = 230.toByte())
        val analysis = OpticalFrameAnalyzer.extractRoiAnalysisFromBuffer(
            yBuffer = buffer, width = 640, height = 480, rowStride = 640, pixelStride = 1,
            cropRect = null, config = cfg, histogram = histogram
        )

        // Frame 1 of spot
        t += 33L
        val t1 = det.processSample(analysis.representativeLuma, t, maxLuma = analysis.maxLuma, brightPixelCount = analysis.brightPixelCount)
        assertNull("Candidate frame 1 must not confirm yet", t1)
        assertEquals(LightState.OFF, det.currentState)

        // Frame 2 of spot after 45ms
        t += 45L
        val t2 = det.processSample(analysis.representativeLuma, t, maxLuma = analysis.maxLuma, brightPixelCount = analysis.brightPixelCount)
        assertNotNull("Candidate frame 2 must confirm ON transition for 8-pixel spot", t2)
        assertEquals(LightState.ON, t2!!.newState)
        assertEquals(LightState.ON, det.currentState)
    }

    @Test
    fun `test detector transition confirmation and recovery after brief noisy frame`() {
        val cfg = OpticalDetectorConfig(
            calibrationFramesCount = 10,
            onContrastOffset = 25.0f,
            minAbsoluteOnLuma = 35.0f,
            minConfirmationMs = 40L,
            minConfirmationFrames = 2,
            minSpotPixels = 6
        )
        val det = OpticalSignalDetector(cfg).apply { start() }

        var t = 0L
        for (i in 0 until 10) {
            det.processSample(20.0f, t)
            t += 33L
        }

        // Brief single-frame noise spike (e.g. 230 luma for 1 frame)
        t += 33L
        val noiseTrans = det.processSample(230.0f, t)
        assertNull("Single noise frame must not confirm transition", noiseTrans)
        assertEquals(LightState.OFF, det.currentState)

        // Next frame returns to ambient 20
        t += 33L
        val ambientTrans = det.processSample(20.0f, t)
        assertNull(ambientTrans)
        assertEquals(LightState.OFF, det.currentState)

        // Now legitimate pulse arrives: 8-pixel spot for 3 frames
        val histogram = IntArray(256)
        val (buffer, _) = createSyntheticFrame(640, 480, ambientLuma = 20, spotPixels = 8, spotLuma = 230.toByte())
        val analysis = OpticalFrameAnalyzer.extractRoiAnalysisFromBuffer(
            yBuffer = buffer, width = 640, height = 480, rowStride = 640, pixelStride = 1,
            cropRect = null, config = cfg, histogram = histogram
        )

        t += 33L
        det.processSample(analysis.representativeLuma, t, maxLuma = analysis.maxLuma, brightPixelCount = analysis.brightPixelCount)
        t += 45L
        val confirmed = det.processSample(analysis.representativeLuma, t, maxLuma = analysis.maxLuma, brightPixelCount = analysis.brightPixelCount)
        assertNotNull("Must recover and confirm legitimate pulse after prior noise", confirmed)
        assertEquals(LightState.ON, confirmed!!.newState)
        assertEquals(LightState.ON, det.currentState)
    }

    @Test
    fun `test gradual ambient change adapts baseline without false triggers`() {
        val cfg = OpticalDetectorConfig(
            calibrationFramesCount = 10,
            ambientAdaptAlpha = 0.05f,
            onContrastOffset = 25.0f
        )
        val det = OpticalSignalDetector(cfg).apply { start() }
        var t = 0L
        for (i in 0 until 10) {
            det.processSample(20.0f, t)
            t += 33L
        }
        assertEquals(20.0f, det.ambientLuma, 0.01f)

        // Gradually increase ambient from 20 to 35 over 30 frames
        for (i in 1..30) {
            t += 33L
            val trans = det.processSample(20.0f + (i * 0.5f), t)
            assertNull("Gradual ambient rise must not trigger ON", trans)
            assertEquals(LightState.OFF, det.currentState)
        }
        assertTrue("Ambient baseline should have adapted upwards", det.ambientLuma > 25.0f)
    }

    // =========================================================================
    // 12. AMBIENT BASELINE SEPARATION & DYNAMIC OPTICAL SIGNAL DETECTION (PHASE R5.4)
    // =========================================================================

    @Test
    fun `test normal indoor background with static bright pixels calibrates to ambient not 255`() {
        val cfg = OpticalDetectorConfig(
            calibrationFramesCount = 10,
            minSpotPixels = 6,
            ambientPercentile = 0.50f
        )
        val det = OpticalSignalDetector(cfg).apply { start() }
        val histogram = IntArray(256)

        var t = 0L
        for (i in 0 until 10) {
            val (buf, _) = createSyntheticFrame(640, 480, ambientLuma = 60, spotPixels = 100, spotLuma = 255.toByte())
            val analysis = OpticalFrameAnalyzer.extractRoiAnalysisFromBuffer(
                yBuffer = buf, width = 640, height = 480, rowStride = 640, pixelStride = 1,
                cropRect = null, config = cfg, histogram = histogram
            )
            assertEquals(60.0f, analysis.ambientEstimateLuma, 0.001f)
            assertEquals(255.0f, analysis.representativeLuma, 0.001f)
            assertEquals(255.0f, analysis.maxLuma, 0.001f)
            assertEquals(100, analysis.spotPixelCount)

            det.processSample(
                luminance = analysis.representativeLuma,
                timestampMs = t,
                maxLuma = analysis.maxLuma,
                brightPixelCount = analysis.spotPixelCount,
                ambientEstimateLuma = analysis.ambientEstimateLuma
            )
            t += 33L
        }

        assertTrue(det.isCalibrated)
        assertEquals("Ambient baseline must be 60, not saturated 255", 60.0f, det.ambientLuma, 0.001f)
        assertEquals("OnThreshold must be 85, not 280", 85.0f, det.diagnostics.value.onThreshold, 0.001f)
        assertEquals(100, det.baselineBrightPixels)
        assertEquals(LightState.OFF, det.currentState)
    }

    @Test
    fun `test stable background with static bright pixels maintains OFF state`() {
        val cfg = OpticalDetectorConfig(
            calibrationFramesCount = 10,
            minSpotPixels = 6,
            ambientPercentile = 0.50f
        )
        val det = OpticalSignalDetector(cfg).apply { start() }
        val histogram = IntArray(256)

        var t = 0L
        for (i in 0 until 10) {
            val (buf, _) = createSyntheticFrame(640, 480, ambientLuma = 60, spotPixels = 100, spotLuma = 255.toByte())
            val analysis = OpticalFrameAnalyzer.extractRoiAnalysisFromBuffer(
                yBuffer = buf, width = 640, height = 480, rowStride = 640, pixelStride = 1,
                cropRect = null, config = cfg, histogram = histogram
            )
            det.processSample(
                luminance = analysis.representativeLuma,
                timestampMs = t,
                maxLuma = analysis.maxLuma,
                brightPixelCount = analysis.spotPixelCount,
                ambientEstimateLuma = analysis.ambientEstimateLuma
            )
            t += 33L
        }
        assertEquals(LightState.OFF, det.currentState)

        for (i in 0 until 50) {
            val (buf, _) = createSyntheticFrame(640, 480, ambientLuma = 60, spotPixels = 100, spotLuma = 255.toByte())
            val analysis = OpticalFrameAnalyzer.extractRoiAnalysisFromBuffer(
                yBuffer = buf, width = 640, height = 480, rowStride = 640, pixelStride = 1,
                cropRect = null, config = cfg, histogram = histogram
            )
            val trans = det.processSample(
                luminance = analysis.representativeLuma,
                timestampMs = t,
                maxLuma = analysis.maxLuma,
                brightPixelCount = analysis.spotPixelCount,
                ambientEstimateLuma = analysis.ambientEstimateLuma
            )
            assertNull("Static bright background must NEVER produce a transition", trans)
            assertEquals("State must remain OFF in presence of static highlights", LightState.OFF, det.currentState)
            t += 33L
        }
    }

    @Test
    fun `test simulated sender pulse with 6, 8, 10, and 15 pixels triggers ON transition`() {
        val cfg = OpticalDetectorConfig(
            calibrationFramesCount = 10,
            minSpotPixels = 6,
            ambientPercentile = 0.50f
        )
        val spotIncrements = listOf(6, 8, 10, 15)

        for (pulsePixels in spotIncrements) {
            val det = OpticalSignalDetector(cfg).apply { start() }
            val histogram = IntArray(256)

            var t = 0L
            for (i in 0 until 10) {
                val (buf, _) = createSyntheticFrame(640, 480, ambientLuma = 60, spotPixels = 100, spotLuma = 255.toByte())
                val analysis = OpticalFrameAnalyzer.extractRoiAnalysisFromBuffer(
                    yBuffer = buf, width = 640, height = 480, rowStride = 640, pixelStride = 1,
                    cropRect = null, config = cfg, histogram = histogram
                )
                det.processSample(
                    luminance = analysis.representativeLuma,
                    timestampMs = t,
                    maxLuma = analysis.maxLuma,
                    brightPixelCount = analysis.spotPixelCount,
                    ambientEstimateLuma = analysis.ambientEstimateLuma
                )
                t += 33L
            }
            assertEquals(LightState.OFF, det.currentState)

            t += 33L
            val (pBuf1, _) = createSyntheticFrame(640, 480, ambientLuma = 60, spotPixels = 100 + pulsePixels, spotLuma = 255.toByte())
            val pAnalysis1 = OpticalFrameAnalyzer.extractRoiAnalysisFromBuffer(
                yBuffer = pBuf1, width = 640, height = 480, rowStride = 640, pixelStride = 1,
                cropRect = null, config = cfg, histogram = histogram
            )
            val trans1 = det.processSample(
                luminance = pAnalysis1.representativeLuma,
                timestampMs = t,
                maxLuma = pAnalysis1.maxLuma,
                brightPixelCount = pAnalysis1.spotPixelCount,
                ambientEstimateLuma = pAnalysis1.ambientEstimateLuma
            )
            assertNull("Candidate frame 1 should not confirm immediately", trans1)
            assertEquals(LightState.OFF, det.currentState)

            t += 45L
            val (pBuf2, _) = createSyntheticFrame(640, 480, ambientLuma = 60, spotPixels = 100 + pulsePixels, spotLuma = 255.toByte())
            val pAnalysis2 = OpticalFrameAnalyzer.extractRoiAnalysisFromBuffer(
                yBuffer = pBuf2, width = 640, height = 480, rowStride = 640, pixelStride = 1,
                cropRect = null, config = cfg, histogram = histogram
            )
            val trans2 = det.processSample(
                luminance = pAnalysis2.representativeLuma,
                timestampMs = t,
                maxLuma = pAnalysis2.maxLuma,
                brightPixelCount = pAnalysis2.spotPixelCount,
                ambientEstimateLuma = pAnalysis2.ambientEstimateLuma
            )
            assertNotNull("Sender pulse of $pulsePixels px must confirm ON transition", trans2)
            assertEquals(LightState.ON, trans2!!.newState)
            assertEquals(LightState.ON, det.currentState)
        }
    }

    @Test
    fun `test repeated ON and OFF pulses produce valid detector transitions in room with static lamp`() {
        val cfg = OpticalDetectorConfig(
            calibrationFramesCount = 10,
            minSpotPixels = 6,
            ambientPercentile = 0.50f
        )
        val det = OpticalSignalDetector(cfg).apply { start() }
        val histogram = IntArray(256)

        var t = 0L
        for (i in 0 until 10) {
            val (buf, _) = createSyntheticFrame(640, 480, ambientLuma = 60, spotPixels = 80, spotLuma = 255.toByte())
            val a = OpticalFrameAnalyzer.extractRoiAnalysisFromBuffer(buf, 640, 480, 640, 1, null, cfg, histogram)
            det.processSample(a.representativeLuma, t, maxLuma = a.maxLuma, brightPixelCount = a.spotPixelCount, ambientEstimateLuma = a.ambientEstimateLuma)
            t += 33L
        }
        assertEquals(LightState.OFF, det.currentState)

        val confirmedTransitions = mutableListOf<OpticalTransition>()

        for (pulse in 1..3) {
            for (f in 0 until 4) {
                t += 38L
                val (buf, _) = createSyntheticFrame(640, 480, ambientLuma = 60, spotPixels = 95, spotLuma = 255.toByte())
                val a = OpticalFrameAnalyzer.extractRoiAnalysisFromBuffer(buf, 640, 480, 640, 1, null, cfg, histogram)
                val trans = det.processSample(a.representativeLuma, t, maxLuma = a.maxLuma, brightPixelCount = a.spotPixelCount, ambientEstimateLuma = a.ambientEstimateLuma)
                if (trans != null) confirmedTransitions.add(trans)
            }
            assertEquals("Must be ON after pulse $pulse", LightState.ON, det.currentState)

            for (f in 0 until 4) {
                t += 38L
                val (buf, _) = createSyntheticFrame(640, 480, ambientLuma = 60, spotPixels = 80, spotLuma = 255.toByte())
                val a = OpticalFrameAnalyzer.extractRoiAnalysisFromBuffer(buf, 640, 480, 640, 1, null, cfg, histogram)
                val trans = det.processSample(a.representativeLuma, t, maxLuma = a.maxLuma, brightPixelCount = a.spotPixelCount, ambientEstimateLuma = a.ambientEstimateLuma)
                if (trans != null) confirmedTransitions.add(trans)
            }
            assertEquals("Must be OFF after gap $pulse", LightState.OFF, det.currentState)
        }

        assertEquals(6, confirmedTransitions.size)
        assertEquals(LightState.ON, confirmedTransitions[0].newState)
        assertEquals(LightState.OFF, confirmedTransitions[1].newState)
        assertEquals(LightState.ON, confirmedTransitions[2].newState)
        assertEquals(LightState.OFF, confirmedTransitions[3].newState)
        assertEquals(LightState.ON, confirmedTransitions[4].newState)
        assertEquals(LightState.OFF, confirmedTransitions[5].newState)
    }

    @Test
    fun `test thresholds never exceed valid 8-bit range`() {
        val cfg = OpticalDetectorConfig(
            calibrationFramesCount = 10,
            maxOnThreshold = 245.0f,
            onContrastOffset = 25.0f,
            offContrastOffset = 12.0f
        )
        val det = OpticalSignalDetector(cfg).apply { start() }

        var t = 0L
        for (i in 0 until 10) {
            det.processSample(luminance = 240.0f, timestampMs = t, ambientEstimateLuma = 240.0f)
            t += 33L
        }
        assertTrue(det.isCalibrated)
        val diag = det.diagnostics.value
        assertEquals("Threshold must not exceed 245.0", 245.0f, diag.onThreshold, 0.001f)
        assertTrue("Threshold must be within 8-bit unsigned range <= 255", diag.onThreshold <= 255.0f)
        assertTrue("OffThreshold must be strictly below OnThreshold", diag.offThreshold < diag.onThreshold)
    }

    @Test
    fun `test global camera exposure change without localized beam does not trigger ON`() {
        val cfg = OpticalDetectorConfig(
            calibrationFramesCount = 10,
            ambientAdaptAlpha = 0.05f
        )
        val det = OpticalSignalDetector(cfg).apply { start() }
        var t = 0L
        for (i in 0 until 10) {
            det.processSample(luminance = 50.0f, timestampMs = t, ambientEstimateLuma = 50.0f)
            t += 33L
        }
        assertEquals(LightState.OFF, det.currentState)

        for (i in 1..10) {
            t += 33L
            val trans = det.processSample(
                luminance = 50.0f + (i * 1.5f),
                timestampMs = t,
                ambientEstimateLuma = 50.0f + (i * 1.5f)
            )
            assertNull("Global rise within hysteresis must not trigger ON", trans)
            assertEquals(LightState.OFF, det.currentState)
        }
    }

    @Test
    fun `test dark room and close range flood regression coverage`() {
        val cfg = OpticalDetectorConfig(
            calibrationFramesCount = 10,
            minSpotPixels = 6,
            minAbsoluteOnLuma = 35.0f
        )
        val det = OpticalSignalDetector(cfg).apply { start() }
        val histogram = IntArray(256)

        var t = 0L
        for (i in 0 until 10) {
            val (buf, _) = createSyntheticFrame(640, 480, ambientLuma = 5, spotPixels = 0)
            val a = OpticalFrameAnalyzer.extractRoiAnalysisFromBuffer(buf, 640, 480, 640, 1, null, cfg, histogram)
            det.processSample(a.representativeLuma, t, maxLuma = a.maxLuma, brightPixelCount = a.brightPixelCount, ambientEstimateLuma = a.ambientEstimateLuma)
            t += 33L
        }
        assertEquals(5.0f, det.ambientLuma, 0.001f)
        assertEquals(35.0f, det.diagnostics.value.onThreshold, 0.001f)

        t += 33L
        val (buf1, _) = createSyntheticFrame(640, 480, ambientLuma = 5, spotPixels = 12, spotLuma = 200.toByte())
        val a1 = OpticalFrameAnalyzer.extractRoiAnalysisFromBuffer(buf1, 640, 480, 640, 1, null, cfg, histogram)
        det.processSample(a1.representativeLuma, t, maxLuma = a1.maxLuma, brightPixelCount = a1.brightPixelCount, ambientEstimateLuma = a1.ambientEstimateLuma)
        t += 45L
        val transOn = det.processSample(a1.representativeLuma, t, maxLuma = a1.maxLuma, brightPixelCount = a1.brightPixelCount, ambientEstimateLuma = a1.ambientEstimateLuma)
        assertNotNull("Remote 2m spot in dark room must trigger ON", transOn)
        assertEquals(LightState.ON, det.currentState)

        t += 33L
        val (offBuf, _) = createSyntheticFrame(640, 480, ambientLuma = 5, spotPixels = 0)
        val aOff = OpticalFrameAnalyzer.extractRoiAnalysisFromBuffer(offBuf, 640, 480, 640, 1, null, cfg, histogram)
        det.processSample(aOff.representativeLuma, t, maxLuma = aOff.maxLuma, brightPixelCount = aOff.brightPixelCount, ambientEstimateLuma = aOff.ambientEstimateLuma)
        t += 45L
        val transOff = det.processSample(aOff.representativeLuma, t, maxLuma = aOff.maxLuma, brightPixelCount = aOff.brightPixelCount, ambientEstimateLuma = aOff.ambientEstimateLuma)
        assertNotNull("Must return to OFF", transOff)
        assertEquals(LightState.OFF, det.currentState)

        t += 33L
        val (floodBuf, _) = createSyntheticFrame(640, 480, ambientLuma = 5, spotPixels = 10000, spotLuma = 255.toByte())
        val aFlood = OpticalFrameAnalyzer.extractRoiAnalysisFromBuffer(floodBuf, 640, 480, 640, 1, null, cfg, histogram)
        det.processSample(aFlood.representativeLuma, t, maxLuma = aFlood.maxLuma, brightPixelCount = aFlood.brightPixelCount, ambientEstimateLuma = aFlood.ambientEstimateLuma)
        t += 45L
        val transFlood = det.processSample(aFlood.representativeLuma, t, maxLuma = aFlood.maxLuma, brightPixelCount = aFlood.brightPixelCount, ambientEstimateLuma = aFlood.ambientEstimateLuma)
        assertNotNull("Close range flood must trigger ON", transFlood)
        assertEquals(LightState.ON, det.currentState)
    }

    @Test
    fun `test detector reset and reinitialization behaviour`() {
        val cfg = OpticalDetectorConfig(calibrationFramesCount = 10, minSpotPixels = 6)
        val det = OpticalSignalDetector(cfg).apply { start() }

        var t = 0L
        for (i in 0 until 10) {
            det.processSample(luminance = 200.0f, timestampMs = t, brightPixelCount = 50, ambientEstimateLuma = 60.0f)
            t += 33L
        }
        assertTrue(det.isCalibrated)
        assertEquals(60.0f, det.ambientLuma, 0.001f)
        assertEquals(50, det.baselineBrightPixels)

        det.reset()
        assertFalse(det.isCalibrated)
        assertEquals(0.0f, det.ambientLuma, 0.001f)
        assertEquals(0, det.baselineBrightPixels)
        assertEquals(LightState.OFF, det.currentState)
        assertEquals(0L, det.totalFramesProcessed)
        assertEquals(0, det.diagnostics.value.brightPixelCount)
        assertEquals(0, det.diagnostics.value.deltaBrightPixels)
    }
}
