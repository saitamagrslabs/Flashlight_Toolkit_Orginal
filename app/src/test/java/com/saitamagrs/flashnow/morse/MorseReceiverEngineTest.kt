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
}
