package com.saitamagrs.flashnow.morse.decoder

import com.saitamagrs.flashnow.morse.core.MorseCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MorseDecoderTest {

    private lateinit var decoder: MorseDecoder

    @Before
    fun setUp() {
        decoder = MorseDecoder()
    }

    // Helper: simulate a standard DOT (150ms)
    private fun dot(durationMs: Long = 150L) {
        decoder.onPulse(durationMs)
    }

    // Helper: simulate a standard DASH (450ms)
    private fun dash(durationMs: Long = 450L) {
        decoder.onPulse(durationMs)
    }

    // Helper: intra-character gap (150ms)
    private fun symbolGap(durationMs: Long = 150L) {
        decoder.onGap(durationMs)
    }

    // Helper: inter-character gap (450ms)
    private fun letterGap(durationMs: Long = 450L) {
        decoder.onGap(durationMs)
    }

    // Helper: inter-word gap (1050ms)
    private fun wordGap(durationMs: Long = 1050L) {
        decoder.onGap(durationMs)
    }

    // Helper: end-of-frame gap (1500ms)
    private fun endGap(durationMs: Long = 1500L) {
        decoder.onGap(durationMs)
    }

    // Helper: send a complete character pattern string (e.g. "..." for 'S')
    private fun sendPattern(pattern: String) {
        val symbols = pattern.toCharArray()
        for ((index, s) in symbols.withIndex()) {
            if (s == '.') dot() else if (s == '-') dash()
            if (index < symbols.size - 1) {
                symbolGap()
            }
        }
    }

    // ==========================================
    // 1. BASIC DECODING TESTS
    // ==========================================

    @Test
    fun `test decode single dot letter E`() {
        dot()
        assertEquals(".", decoder.symbols)
        decoder.flush()
        assertEquals("E", decoder.text)
        assertEquals("", decoder.symbols)
    }

    @Test
    fun `test decode single dash letter T`() {
        dash()
        assertEquals("-", decoder.symbols)
        decoder.flush()
        assertEquals("T", decoder.text)
        assertEquals("", decoder.symbols)
    }

    @Test
    fun `test decode SOS with inter-character gaps`() {
        // 'S' (...)
        sendPattern("...")
        letterGap()
        assertEquals("S", decoder.text)

        // 'O' (---)
        sendPattern("---")
        letterGap()
        assertEquals("SO", decoder.text)

        // 'S' (...)
        sendPattern("...")
        decoder.flush()
        assertEquals("SOS", decoder.text)
    }

    @Test
    fun `test decode HELP`() {
        // H (....)
        sendPattern("....")
        letterGap()

        // E (.)
        sendPattern(".")
        letterGap()

        // L (.-..)
        sendPattern(".-..")
        letterGap()

        // P (.--.)
        sendPattern(".--.")
        decoder.flush()

        assertEquals("HELP", decoder.text)
    }

    @Test
    fun `test decode DANGER`() {
        val word = "DANGER"
        for ((idx, char) in word.toCharArray().withIndex()) {
            val pattern = MorseCodec.encodeChar(char)!!
            sendPattern(pattern)
            if (idx < word.length - 1) {
                letterGap()
            }
        }
        decoder.flush()
        assertEquals("DANGER", decoder.text)
    }

    @Test
    fun `test decode multiple words with word gap`() {
        // "HI"
        sendPattern("....") // H
        letterGap()
        sendPattern("..")   // I
        wordGap()           // Word gap between words

        assertEquals("HI ", decoder.text)

        // "YOU"
        sendPattern("-.--") // Y
        letterGap()
        sendPattern("---")  // O
        letterGap()
        sendPattern("..-")  // U
        endGap()            // End of message

        assertEquals("HI YOU", decoder.text)
    }

    // ==========================================
    // 2. TIMING & BOUNDARY TESTS
    // ==========================================

    @Test
    fun `test pulse classification boundaries`() {
        // Sub-min (< 40ms) rejected as INVALID
        assertEquals(PulseResult.INVALID, decoder.onPulse(0L))
        assertEquals(PulseResult.INVALID, decoder.onPulse(39L))
        assertEquals("", decoder.symbols)

        // Exactly at minPulseMs (40ms) is DOT
        assertEquals(PulseResult.DOT, decoder.onPulse(40L))
        assertEquals(".", decoder.symbols)
        decoder.reset()

        // Shorter dot (100ms)
        assertEquals(PulseResult.DOT, decoder.onPulse(100L))
        decoder.reset()

        // Nominal dot (150ms)
        assertEquals(PulseResult.DOT, decoder.onPulse(150L))
        decoder.reset()

        // Longer dot, just below boundary (274ms)
        assertEquals(PulseResult.DOT, decoder.onPulse(274L))
        assertEquals(".", decoder.symbols)
        decoder.reset()

        // Exactly at boundary (275ms) is DASH
        assertEquals(PulseResult.DASH, decoder.onPulse(275L))
        assertEquals("-", decoder.symbols)
        decoder.reset()

        // Nominal dash (450ms)
        assertEquals(PulseResult.DASH, decoder.onPulse(450L))
        assertEquals("-", decoder.symbols)
        decoder.reset()

        // Longer dash (600ms)
        assertEquals(PulseResult.DASH, decoder.onPulse(600L))
        decoder.reset()

        // Exactly at maxPulseMs (900ms) is DASH
        assertEquals(PulseResult.DASH, decoder.onPulse(900L))
        decoder.reset()

        // Over-max (> 900ms) rejected as INVALID
        assertEquals(PulseResult.INVALID, decoder.onPulse(901L))
        assertEquals(PulseResult.INVALID, decoder.onPulse(2500L))
        assertEquals("", decoder.symbols)
    }

    @Test
    fun `test gap classification boundaries`() {
        sendPattern(".") // Buffer has "."

        // Negative duration is INVALID
        assertEquals(GapResult.INVALID, decoder.onGap(-1L))
        assertEquals(".", decoder.symbols)

        // Sub-threshold (< 280ms) is SYMBOL_GAP
        assertEquals(GapResult.SYMBOL_GAP, decoder.onGap(0L))
        assertEquals(GapResult.SYMBOL_GAP, decoder.onGap(150L))
        assertEquals(GapResult.SYMBOL_GAP, decoder.onGap(279L))
        assertEquals(".", decoder.symbols) // Character not finalized

        // Boundary (280ms) is LETTER_GAP (finalizes character)
        assertEquals(GapResult.LETTER_GAP, decoder.onGap(280L))
        assertEquals("E", decoder.text)
        assertEquals("", decoder.symbols)

        // Test upper letter gap boundary (749ms)
        sendPattern("-")
        assertEquals(GapResult.LETTER_GAP, decoder.onGap(749L))
        assertEquals("ET", decoder.text)

        // Word gap boundary (750ms)
        sendPattern(".")
        assertEquals(GapResult.WORD_GAP, decoder.onGap(750L))
        assertEquals("ETE ", decoder.text)

        // Upper word gap boundary (1299ms)
        sendPattern("-")
        assertEquals(GapResult.WORD_GAP, decoder.onGap(1299L))
        assertEquals("ETE T ", decoder.text)

        // End gap boundary (1300ms)
        sendPattern(".")
        assertEquals(GapResult.END_GAP, decoder.onGap(1300L))
        assertEquals("ETE T E", decoder.text)

        // Very long end gap (10000ms)
        assertEquals(GapResult.END_GAP, decoder.onGap(10000L))
        assertEquals("ETE T E", decoder.text)
    }

    @Test
    fun `test consecutive gaps do not duplicate spaces or characters`() {
        sendPattern("...") // 'S'
        letterGap(450L)    // Decodes 'S'
        assertEquals("S", decoder.text)

        // Consecutive letter gaps with empty buffer
        letterGap(450L)
        letterGap(450L)
        assertEquals("S", decoder.text)

        // Consecutive word gaps
        wordGap(1050L)
        assertEquals("S ", decoder.text)
        wordGap(1050L)
        wordGap(1050L)
        assertEquals("S ", decoder.text) // Still exactly one space

        // End gap after word gap
        endGap(1500L)
        assertEquals("S ", decoder.text)
    }

    // ==========================================
    // 3. STATE, RESET, AND RECOVERY TESTS
    // ==========================================

    @Test
    fun `test reset during partial character`() {
        dot()
        dash()
        assertEquals(".-", decoder.symbols)

        decoder.reset()
        assertEquals("", decoder.symbols)
        assertEquals("", decoder.text)

        // New character after reset works cleanly
        dot()
        decoder.flush()
        assertEquals("E", decoder.text)
    }

    @Test
    fun `test new message after full reset`() {
        sendPattern("...")
        letterGap()
        sendPattern("---")
        decoder.flush()
        assertEquals("SO", decoder.text)

        decoder.reset()
        assertEquals("", decoder.text)
        assertEquals("", decoder.symbols)

        sendPattern(".-")
        decoder.flush()
        assertEquals("A", decoder.text)
    }

    @Test
    fun `test repeated calls to flush and reset are idempotent`() {
        decoder.flush()
        decoder.flush()
        assertEquals("", decoder.text)

        decoder.reset()
        decoder.reset()
        assertEquals("", decoder.text)

        dot()
        decoder.flush()
        decoder.flush()
        assertEquals("E", decoder.text)

        decoder.reset()
        decoder.reset()
        assertEquals("", decoder.text)
    }

    @Test
    fun `test unknown or malformed Morse sequence handling`() {
        var invalidPatternReported = ""
        decoder.listener = object : MorseDecoderListener {
            override fun onInvalidPattern(pattern: String) {
                invalidPatternReported = pattern
            }
        }

        // Send an invalid Morse pattern: ".-.-" (not in standard Morse)
        sendPattern(".-.-")
        val result = decoder.finalizeCharacter()

        assertNull(result)
        assertEquals(".-.-", invalidPatternReported)
        assertEquals("", decoder.text) // By default, dropped without corrupting text
        assertEquals("", decoder.symbols)

        // Next character decodes normally
        sendPattern(".")
        decoder.flush()
        assertEquals("E", decoder.text)
    }

    @Test
    fun `test replacement character mode on invalid pattern`() {
        val customConfig = MorseDecoderConfig(emitReplacementOnInvalid = true, replacementChar = '?')
        val customDecoder = MorseDecoder(config = customConfig)

        // Send invalid pattern
        customDecoder.onPulse(150L) // .
        customDecoder.onGap(150L)
        customDecoder.onPulse(450L) // -
        customDecoder.onGap(150L)
        customDecoder.onPulse(150L) // .
        customDecoder.onGap(150L)
        customDecoder.onPulse(450L) // -
        customDecoder.flush()

        assertEquals("?", customDecoder.text)
    }

    // ==========================================
    // 4. CHARACTER COVERAGE TESTS
    // ==========================================

    @Test
    fun `test complete alphabet coverage A to Z`() {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
        for (char in alphabet) {
            decoder.reset()
            val morse = MorseCodec.encodeChar(char)!!
            sendPattern(morse)
            decoder.flush()
            assertEquals("Mismatch decoding char $char", char.toString(), decoder.text)
        }
    }

    @Test
    fun `test all digits 0 to 9`() {
        val digits = "0123456789"
        for (digit in digits) {
            decoder.reset()
            val morse = MorseCodec.encodeChar(digit)!!
            sendPattern(morse)
            decoder.flush()
            assertEquals("Mismatch decoding digit $digit", digit.toString(), decoder.text)
        }
    }

    @Test
    fun `test punctuation support`() {
        // Period (.-.-.-)
        decoder.reset()
        sendPattern(".-.-.-")
        decoder.flush()
        assertEquals(".", decoder.text)

        // Question mark (..--..)
        decoder.reset()
        sendPattern("..--..")
        decoder.flush()
        assertEquals("?", decoder.text)

        // Slash (-..-.)
        decoder.reset()
        sendPattern("-..-.")
        decoder.flush()
        assertEquals("/", decoder.text)
    }

    // ==========================================
    // 5. LISTENER CALLBACK VERIFICATION
    // ==========================================

    @Test
    fun `test listener callbacks receive correct events`() {
        val appendedSymbols = mutableListOf<Char>()
        val decodedChars = mutableListOf<Char>()
        var wordBoundaryCount = 0
        var resetCalled = false

        decoder.listener = object : MorseDecoderListener {
            override fun onSymbolAppended(symbol: Char, currentBuffer: String) {
                appendedSymbols.add(symbol)
            }

            override fun onCharacterDecoded(char: Char, pattern: String) {
                decodedChars.add(char)
            }

            override fun onWordBoundary() {
                wordBoundaryCount++
            }

            override fun onReset() {
                resetCalled = true
            }
        }

        // Send 'A' (.-)
        dot()
        symbolGap()
        dash()
        letterGap()

        assertEquals(listOf('.', '-'), appendedSymbols)
        assertEquals(listOf('A'), decodedChars)

        // Word gap
        wordGap()
        assertEquals(1, wordBoundaryCount)

        // Reset
        decoder.reset()
        assertTrue(resetCalled)
    }
}
