package com.saitamagrs.flashnow.morse

import com.saitamagrs.flashnow.morse.core.MorseTiming
import com.saitamagrs.flashnow.morse.receiver.MorseReceiverEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MorseReceiverEngineTest {

    private lateinit var receiver: MorseReceiverEngine

    @Before
    fun setUp() {
        receiver = MorseReceiverEngine()
    }

    // Helper: Simulate transmission of a DOT (150ms ON)
    private fun sendDot() {
        receiver.handlePulse(MorseTiming.DOT_DURATION_MS)
    }

    // Helper: Simulate transmission of a DASH (450ms ON)
    private fun sendDash() {
        receiver.handlePulse(MorseTiming.DASH_DURATION_MS)
    }

    // Helper: Intra-symbol gap within same letter (150ms OFF)
    private fun sendSymbolGap() {
        receiver.handleGap(MorseTiming.SYMBOL_GAP_MS)
    }

    // Helper: Inter-letter gap between letters (450ms OFF)
    private fun sendLetterGap() {
        receiver.handleGap(MorseTiming.LETTER_GAP_MS)
    }

    @Test
    fun `test 1 - first S decodes without preamble discard on fresh receiver`() {
        assertFalse(receiver.preambleDetected.value)
        assertEquals("", receiver.decodedText.value)

        // Send 'S' (...): dot, symbol gap, dot, symbol gap, dot
        sendDot()
        sendSymbolGap()
        sendDot()
        sendSymbolGap()
        sendDot()

        // Responsive letter gap (450ms)
        receiver.handleIdleGap(MorseTiming.LETTER_GAP_MS)

        assertEquals("S", receiver.decodedText.value)
        assertTrue(receiver.preambleDetected.value)
    }

    @Test
    fun `test 2 - SOS decodes completely including first S`() {
        assertFalse(receiver.preambleDetected.value)

        // Letter 1: 'S' (...)
        sendDot()
        sendSymbolGap()
        sendDot()
        sendSymbolGap()
        sendDot()
        sendLetterGap()

        assertEquals("S", receiver.decodedText.value)

        // Letter 2: 'O' (---)
        sendDash()
        sendSymbolGap()
        sendDash()
        sendSymbolGap()
        sendDash()
        sendLetterGap()

        assertEquals("SO", receiver.decodedText.value)

        // Letter 3: 'S' (...)
        sendDot()
        sendSymbolGap()
        sendDot()
        sendSymbolGap()
        sendDot()
        receiver.handleIdleGap(MorseTiming.LETTER_GAP_MS)

        assertEquals("SOS", receiver.decodedText.value)
    }

    @Test
    fun `test 3 - first letter A decodes correctly`() {
        // Send 'A' (.-)
        sendDot()
        sendSymbolGap()
        sendDash()
        receiver.handleIdleGap(MorseTiming.LETTER_GAP_MS)

        assertEquals("A", receiver.decodedText.value)
    }

    @Test
    fun `test 4 - multiple letters decode correctly`() {
        // Send 'H' (....)
        sendDot()
        sendSymbolGap()
        sendDot()
        sendSymbolGap()
        sendDot()
        sendSymbolGap()
        sendDot()
        sendLetterGap()

        // Send 'I' (..)
        sendDot()
        sendSymbolGap()
        sendDot()
        receiver.handleIdleGap(MorseTiming.LETTER_GAP_MS)

        assertEquals("HI", receiver.decodedText.value)
    }

    @Test
    fun `test 5 - intra-letter gaps do not prematurely finalize symbols`() {
        // Dot 1
        sendDot()
        assertEquals(".", receiver.currentSymbols.value)
        assertEquals("", receiver.decodedText.value)

        // Intra-letter symbol gap (150ms)
        sendSymbolGap()
        receiver.handleIdleGap(MorseTiming.SYMBOL_GAP_MS)
        assertEquals(".", receiver.currentSymbols.value)
        assertEquals("", receiver.decodedText.value)

        // Dot 2
        sendDot()
        assertEquals("..", receiver.currentSymbols.value)
        assertEquals("", receiver.decodedText.value)

        // Intra-letter symbol gap (150ms)
        sendSymbolGap()
        receiver.handleIdleGap(MorseTiming.SYMBOL_GAP_MS)
        assertEquals("..", receiver.currentSymbols.value)
        assertEquals("", receiver.decodedText.value)

        // Dot 3
        sendDot()
        assertEquals("...", receiver.currentSymbols.value)
        assertEquals("", receiver.decodedText.value)

        // Only after letter gap is reached does it finalize
        receiver.handleIdleGap(MorseTiming.LETTER_GAP_MS)
        assertEquals("S", receiver.decodedText.value)
        assertEquals("", receiver.currentSymbols.value)
    }

    @Test
    fun `test 6 - final letter finalized after fallback end of message timeout`() {
        // Send 'T' (-)
        sendDash()
        assertEquals("-", receiver.currentSymbols.value)
        assertEquals("", receiver.decodedText.value)

        // Idle duration reaches END_GAP_MS (1500ms)
        receiver.handleIdleGap(MorseTiming.END_GAP_MS)

        assertEquals("T", receiver.decodedText.value)
        assertEquals("", receiver.currentSymbols.value)
    }

    @Test
    fun `test 7 - reset clears state and subsequent S still decodes as S`() {
        // First transmission of 'S'
        sendDot()
        sendSymbolGap()
        sendDot()
        sendSymbolGap()
        sendDot()
        receiver.handleIdleGap(MorseTiming.LETTER_GAP_MS)

        assertEquals("S", receiver.decodedText.value)

        // Reset
        receiver.reset()
        assertEquals("", receiver.decodedText.value)
        assertEquals("", receiver.currentSymbols.value)
        assertFalse(receiver.preambleDetected.value)

        // Second transmission of 'S' after reset
        sendDot()
        sendSymbolGap()
        sendDot()
        sendSymbolGap()
        sendDot()
        receiver.handleIdleGap(MorseTiming.LETTER_GAP_MS)

        assertEquals("S", receiver.decodedText.value)
    }

    @Test
    fun `test 8 - small cluster of bright pixels triggers getBrightSignal and exceeds ON_THRESHOLD_CONTRAST`() {
        val customHist = IntArray(256)
        val sampleCount = 6000
        // 5995 ambient samples at luma 40
        customHist[40] = 5995
        // 5 flashlight beam samples at luma 240 (at 0.5m-1.5m range)
        customHist[240] = 5

        val brightSignal = receiver.getBrightSignal(sampleCount, customHist)
        assertEquals(240.0f, brightSignal, 0.01f)

        val ambientBaseline = 40.0f
        val signalContrast = brightSignal - ambientBaseline
        assertTrue("Signal contrast $signalContrast should exceed ON_THRESHOLD_CONTRAST",
            signalContrast >= MorseReceiverEngine.ON_THRESHOLD_CONTRAST)
    }

    @Test
    fun `test 9 - single hot pixel is rejected by getBrightSignal`() {
        val customHist = IntArray(256)
        val sampleCount = 6000
        // 5998 ambient samples at luma 40
        customHist[40] = 5998
        // 1 or 2 isolated hot pixels at luma 255 (sensor noise)
        customHist[255] = 2

        val brightSignal = receiver.getBrightSignal(sampleCount, customHist)
        // With minClusterSamples >= 3, 2 hot pixels are rejected and search falls back to ambient
        assertEquals(40.0f, brightSignal, 0.01f)
    }

    @Test
    fun `test 10 - ambient scene with no light source remains below ON_THRESHOLD_CONTRAST`() {
        val customHist = IntArray(256)
        val sampleCount = 6000
        // Ambient room variations: 3000 at 38, 3000 at 45
        customHist[38] = 3000
        customHist[45] = 3000

        val brightSignal = receiver.getBrightSignal(sampleCount, customHist)
        assertEquals(45.0f, brightSignal, 0.01f)

        val ambientBaseline = 38.0f // 40th percentile
        val signalContrast = brightSignal - ambientBaseline
        assertTrue("Ambient noise contrast $signalContrast must not trigger ON threshold",
            signalContrast < MorseReceiverEngine.ON_THRESHOLD_CONTRAST)
    }

    @Test
    fun `test 11 - flashlight moving within ROI produces identical histogram signal regardless of spatial position`() {
        val histPositionA = IntArray(256)
        val histPositionB = IntArray(256)
        val sampleCount = 6000

        // Position A (e.g. top-left of ROI)
        histPositionA[40] = 5994
        histPositionA[235] = 6

        // Position B (e.g. bottom-right of ROI)
        histPositionB[40] = 5994
        histPositionB[235] = 6

        val signalA = receiver.getBrightSignal(sampleCount, histPositionA)
        val signalB = receiver.getBrightSignal(sampleCount, histPositionB)

        assertEquals("Histogram-based detection is spatially invariant across ROI", signalA, signalB, 0.001f)
        assertEquals(235.0f, signalA, 0.01f)
    }

    @Test
    fun `test 12 - close-range high contrast scales active off contrast up to MAX_OFF_THRESHOLD_CONTRAST`() {
        val peakContrast = 100.0f
        val closeRangeRatio = 0.28f
        val maxOffContrast = 28.0f
        val defaultOffContrast = MorseReceiverEngine.OFF_THRESHOLD_CONTRAST

        val scaledOffContrast = (peakContrast * closeRangeRatio).coerceIn(defaultOffContrast, maxOffContrast)
        assertEquals(28.0f, scaledOffContrast, 0.01f)
        assertTrue("Scaled OFF contrast must be greater than base OFF contrast",
            scaledOffContrast > defaultOffContrast)
    }
}
