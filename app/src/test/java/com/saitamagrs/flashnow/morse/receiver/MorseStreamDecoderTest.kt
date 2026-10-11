package com.saitamagrs.flashnow.morse.receiver

import com.saitamagrs.flashnow.morse.core.MorseCodec
import com.saitamagrs.flashnow.morse.core.MorseTiming
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MorseStreamDecoderTest {

    private lateinit var decoder: MorseStreamDecoder
    private var isCompleted = false

    @Before
    fun setUp() {
        isCompleted = false
        decoder = MorseStreamDecoder(
            onSessionCompleted = { isCompleted = true }
        )
    }

    private fun sendDot() = decoder.handlePulse(MorseTiming.DOT_DURATION_MS)
    private fun sendDash() = decoder.handlePulse(MorseTiming.DASH_DURATION_MS)
    private fun sendSymbolGap() = decoder.handleGap(MorseTiming.SYMBOL_GAP_MS)
    private fun sendLetterGap() = decoder.handleGap(MorseTiming.LETTER_GAP_MS)
    private fun sendWordGap() = decoder.handleGap(MorseTiming.WORD_GAP_MS)
    private fun sendEndGap() = decoder.handleGap(MorseTiming.END_GAP_MS)

    private fun sendPreamble() {
        sendDot()
        sendSymbolGap()
        sendDot()
        sendSymbolGap()
        sendDot()
        sendLetterGap()
    }

    private fun sendLetter(pattern: String) {
        for (i in pattern.indices) {
            when (pattern[i]) {
                '.' -> sendDot()
                '-' -> sendDash()
            }
            if (i < pattern.length - 1) {
                sendSymbolGap()
            }
        }
        sendLetterGap()
    }

    private fun sendWord(word: String) {
        for (char in word) {
            val pattern = MorseCodec.encodeChar(char) ?: continue
            sendLetter(pattern)
        }
    }

    @Test
    fun `test preamble is synchronized without emitting letter S`() {
        assertFalse(decoder.preambleDetected)
        assertEquals("", decoder.decodedText)

        sendPreamble()

        assertTrue(decoder.preambleDetected)
        assertEquals("", decoder.decodedText)
        assertEquals("", decoder.currentSymbols)
    }

    @Test
    fun `test preamble followed by SOS preserves genuine leading S`() {
        sendPreamble()
        assertTrue(decoder.preambleDetected)

        sendWord("SOS")

        assertEquals("SOS", decoder.decodedText)
    }

    @Test
    fun `test preamble followed by DANGER decodes accurately`() {
        sendPreamble()
        sendWord("DANGER")

        assertEquals("DANGER", decoder.decodedText)
    }

    @Test
    fun `test word gap separates multiple words with spaces`() {
        sendPreamble()
        sendWord("HELLO")
        sendWordGap()
        sendWord("WORLD")

        assertEquals("HELLO WORLD", decoder.decodedText)
    }

    @Test
    fun `test end gap triggers completion`() {
        sendPreamble()
        sendWord("HI")
        assertFalse(isCompleted)

        sendEndGap()

        assertTrue("End gap must trigger session completion", isCompleted)
        assertEquals("HI", decoder.decodedText)
    }

    @Test
    fun `test direct transmission without preamble preserves first character`() {
        // Direct transmission starts with 'D' (-..)
        sendLetter("-..")

        assertTrue("Non-preamble start must promote to message", decoder.preambleDetected)
        assertEquals("D", decoder.decodedText)
    }

    @Test
    fun `test intra-symbol gap does not split letter`() {
        sendPreamble()

        // Send 'A' (.-)
        sendDot()
        sendSymbolGap()
        assertEquals(".", decoder.currentSymbols)
        assertEquals("", decoder.decodedText)

        sendDash()
        assertEquals(".-", decoder.currentSymbols)
        assertEquals("", decoder.decodedText)

        sendLetterGap()
        assertEquals("", decoder.currentSymbols)
        assertEquals("A", decoder.decodedText)
    }
}
