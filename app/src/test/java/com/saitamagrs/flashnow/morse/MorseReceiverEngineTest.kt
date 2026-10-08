package com.saitamagrs.flashnow.morse

import com.saitamagrs.flashnow.morse.core.MorseCodec
import com.saitamagrs.flashnow.morse.core.MorseTiming
import com.saitamagrs.flashnow.morse.correction.MorseEnglishCorrector
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

    // Helper: Protocol synchronization preamble: 3 DOTs ending with LETTER_GAP
    private fun sendPreamble() {
        sendDot()
        sendSymbolGap()
        sendDot()
        sendSymbolGap()
        sendDot()
        sendLetterGap()
    }

    // Helper: Send a letter pattern
    private fun sendLetter(morsePattern: String) {
        for (i in morsePattern.indices) {
            when (morsePattern[i]) {
                '.' -> sendDot()
                '-' -> sendDash()
            }
            if (i < morsePattern.length - 1) {
                sendSymbolGap()
            }
        }
        sendLetterGap()
    }

    // Helper: Send full word as sequence of framed letters
    private fun sendWord(word: String) {
        for (char in word) {
            val pattern = MorseCodec.encodeChar(char) ?: continue
            sendLetter(pattern)
        }
    }

    @Test
    fun `test 1 - preamble is detected but not emitted as S`() {
        assertFalse(receiver.preambleDetected.value)
        assertEquals("", receiver.decodedText.value)

        sendPreamble()

        assertTrue(receiver.preambleDetected.value)
        assertEquals("", receiver.decodedText.value)
        assertEquals("", receiver.rawDecodedText.value)
        assertEquals("", receiver.currentSymbols.value)
    }

    @Test
    fun `test 2 - preamble followed by SOS preserves real leading S`() {
        assertFalse(receiver.preambleDetected.value)

        sendPreamble()
        assertTrue(receiver.preambleDetected.value)
        assertEquals("", receiver.decodedText.value)

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
        sendPreamble()
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
        sendPreamble()
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
        sendPreamble()
        sendDot()
        sendSymbolGap()
        sendDot()
        sendSymbolGap()
        sendDot()
        receiver.handleIdleGap(MorseTiming.LETTER_GAP_MS)

        assertEquals("S", receiver.decodedText.value)
    }

    @Test
    fun `test 8 - word gap appends space between words`() {
        // Send 'H' (....)
        sendDot(); sendSymbolGap(); sendDot(); sendSymbolGap(); sendDot(); sendSymbolGap(); sendDot()
        sendLetterGap()
        assertEquals("H", receiver.decodedText.value)

        // Send 'I' (..)
        sendDot(); sendSymbolGap(); sendDot()

        // Word gap (1050ms)
        receiver.handleGap(MorseTiming.WORD_GAP_MS)
        assertEquals("HI ", receiver.decodedText.value)

        // Send 'U' (..-)
        sendDot(); sendSymbolGap(); sendDot(); sendSymbolGap(); sendDash()
        receiver.handleIdleGap(MorseTiming.LETTER_GAP_MS)

        assertEquals("HI U", receiver.decodedText.value)
    }

    @Test
    fun `test 9 - glitch pulse below minimum duration is rejected`() {
        sendPreamble()
        assertEquals("", receiver.currentSymbols.value)

        // Send 20ms pulse (< 45ms pulse threshold)
        receiver.handlePulse(20L)
        assertEquals("", receiver.currentSymbols.value)

        // Followed by valid dot (150ms)
        sendDot()
        assertEquals(".", receiver.currentSymbols.value)
    }

    @Test
    fun `test 10 - letter B decodes cleanly without premature split`() {
        // Send 'B' (-...)
        sendDash()
        sendSymbolGap()
        sendDot()
        sendSymbolGap()
        sendDot()
        sendSymbolGap()
        sendDot()

        receiver.handleIdleGap(MorseTiming.GAP_SYMBOL_LETTER_THRESHOLD_MS)
        assertEquals("B", receiver.decodedText.value)
        assertEquals("", receiver.currentSymbols.value)
    }

    @Test
    fun `test 11 - letter O decodes cleanly`() {
        // Send 'O' (---)
        sendDash()
        sendSymbolGap()
        sendDash()
        sendSymbolGap()
        sendDash()

        receiver.handleIdleGap(MorseTiming.GAP_SYMBOL_LETTER_THRESHOLD_MS)
        assertEquals("O", receiver.decodedText.value)
        assertEquals("", receiver.currentSymbols.value)
    }

    @Test
    fun `test 12 - pulse duration tolerance windows for dot and dash`() {
        sendPreamble()
        // Short dot (100ms)
        receiver.handlePulse(100L)
        assertEquals(".", receiver.currentSymbols.value)

        // Long dot (220ms)
        receiver.handlePulse(220L)
        assertEquals("..", receiver.currentSymbols.value)

        // Short dash (360ms)
        receiver.handlePulse(360L)
        assertEquals("..-", receiver.currentSymbols.value)

        // Long dash (540ms)
        receiver.handlePulse(540L)
        assertEquals("..--", receiver.currentSymbols.value)

        // Third dash (450ms) to form '2' (..---)
        receiver.handlePulse(450L)
        assertEquals("..---", receiver.currentSymbols.value)

        receiver.handleIdleGap(MorseTiming.GAP_SYMBOL_LETTER_THRESHOLD_MS)
        assertEquals("2", receiver.decodedText.value)
    }

    @Test
    fun `test 13 - symbol gap variations up to 240ms do not prematurely split letter`() {
        sendPreamble()
        // Dot (150ms)
        sendDot()
        assertEquals(".", receiver.currentSymbols.value)

        // Jittered intra-letter gap of 220ms
        receiver.handleGap(220L)
        receiver.handleIdleGap(220L)
        assertEquals(".", receiver.currentSymbols.value)
        assertEquals("", receiver.decodedText.value)

        // Dash (450ms) -> 'A' (.-)
        sendDash()
        assertEquals(".-", receiver.currentSymbols.value)

        // Letter finalized after letter gap (350ms)
        receiver.handleIdleGap(350L)
        assertEquals("A", receiver.decodedText.value)
        assertEquals("", receiver.currentSymbols.value)
    }

    @Test
    fun `test 14 - SOS with optical jitter and varied timings decodes reliably`() {
        sendPreamble()
        // 'S' with varied dots (120ms, 160ms, 140ms) and varied gaps (130ms, 170ms)
        receiver.handlePulse(120L)
        receiver.handleGap(130L)
        receiver.handlePulse(160L)
        receiver.handleGap(170L)
        receiver.handlePulse(140L)
        receiver.handleGap(400L) // Letter gap (nominal 450ms)

        assertEquals("S", receiver.decodedText.value)

        // 'O' with varied dashes (400ms, 480ms, 430ms)
        receiver.handlePulse(400L)
        receiver.handleGap(140L)
        receiver.handlePulse(480L)
        receiver.handleGap(160L)
        receiver.handlePulse(430L)
        receiver.handleGap(420L) // Letter gap

        assertEquals("SO", receiver.decodedText.value)

        // 'S' with varied dots
        receiver.handlePulse(130L)
        receiver.handleGap(150L)
        receiver.handlePulse(150L)
        receiver.handleGap(140L)
        receiver.handlePulse(170L)
        receiver.handleIdleGap(MorseTiming.GAP_SYMBOL_LETTER_THRESHOLD_MS)

        assertEquals("SOS", receiver.decodedText.value)
    }

    @Test
    fun `test session 1 - receiver initializes in WAITING state`() {
        assertEquals(MorseReceiverEngine.SessionState.WAITING, receiver.sessionState.value)
        assertEquals("", receiver.decodedText.value)
        assertEquals("", receiver.currentSymbols.value)
    }

    @Test
    fun `test session 2 - first pulse transitions WAITING to RECEIVING`() {
        assertEquals(MorseReceiverEngine.SessionState.WAITING, receiver.sessionState.value)
        sendDash()
        assertEquals(MorseReceiverEngine.SessionState.RECEIVING, receiver.sessionState.value)
        assertEquals("-", receiver.currentSymbols.value)
    }

    @Test
    fun `test session 3 - word gap adds space without ending session`() {
        sendDot() // 'E'
        sendLetterGap()
        assertEquals("E", receiver.decodedText.value)
        assertEquals(MorseReceiverEngine.SessionState.RECEIVING, receiver.sessionState.value)

        receiver.handleGap(MorseTiming.WORD_GAP_MS)
        assertEquals("E ", receiver.decodedText.value)
        assertEquals(MorseReceiverEngine.SessionState.RECEIVING, receiver.sessionState.value)
    }

    @Test
    fun `test session 4 - idle gap greater than or equal to 1400ms transitions RECEIVING to READY`() {
        sendDot()
        assertEquals(MorseReceiverEngine.SessionState.RECEIVING, receiver.sessionState.value)
        receiver.handleIdleGap(MorseReceiverEngine.SESSION_END_TIMEOUT_MS)
        assertEquals(MorseReceiverEngine.SessionState.READY, receiver.sessionState.value)
    }

    @Test
    fun `test session 5 - in READY state decoded message is preserved`() {
        sendDot()
        sendSymbolGap()
        sendDash() // 'A'
        receiver.handleIdleGap(MorseReceiverEngine.SESSION_END_TIMEOUT_MS)
        assertEquals(MorseReceiverEngine.SessionState.READY, receiver.sessionState.value)
        assertEquals("A", receiver.decodedText.value)

        // Receiver stays armed; subsequent idle ticks preserve the displayed text
        receiver.handleIdleGap(2000L)
        assertEquals(MorseReceiverEngine.SessionState.READY, receiver.sessionState.value)
        assertEquals("A", receiver.decodedText.value)
    }

    @Test
    fun `test session 6 - new pulse in READY state resets previous message and starts new RECEIVING session`() {
        // Transmission 1: 'A' (.-)
        sendDot()
        sendSymbolGap()
        sendDash()
        receiver.handleIdleGap(MorseReceiverEngine.SESSION_END_TIMEOUT_MS)
        assertEquals(MorseReceiverEngine.SessionState.READY, receiver.sessionState.value)
        assertEquals("A", receiver.decodedText.value)

        // Transmission 2 starts with incoming pulse: automatically resets and transitions to RECEIVING
        sendDash() // start of 'B' (-...)
        assertEquals(MorseReceiverEngine.SessionState.RECEIVING, receiver.sessionState.value)
        assertEquals("", receiver.decodedText.value)
        assertEquals("-", receiver.currentSymbols.value)

        sendSymbolGap()
        sendDot()
        sendSymbolGap()
        sendDot()
        sendSymbolGap()
        sendDot()
        receiver.handleIdleGap(MorseReceiverEngine.SESSION_END_TIMEOUT_MS)
        assertEquals(MorseReceiverEngine.SessionState.READY, receiver.sessionState.value)
        assertEquals("B", receiver.decodedText.value)
    }

    @Test
    fun `test session 7 - ETANGER automatically corrects to DANGER upon session completion`() {
        val testCorrector = com.saitamagrs.flashnow.morse.correction.MorseEnglishCorrector(
            setOf("DANGER", "HELLO", "WORLD", "SOS")
        )
        receiver.englishCorrector = testCorrector

        // Send 'E' (.)
        sendDot()
        sendLetterGap()
        assertEquals("E", receiver.decodedText.value)

        // Send 'T' (-)
        sendDash()
        sendLetterGap()
        assertEquals("ET", receiver.decodedText.value)

        // Send 'A' (.-)
        sendDot()
        sendSymbolGap()
        sendDash()
        sendLetterGap()
        assertEquals("ETA", receiver.decodedText.value)

        // Send 'N' (-.)
        sendDash()
        sendSymbolGap()
        sendDot()
        sendLetterGap()
        assertEquals("ETAN", receiver.decodedText.value)

        // Send 'G' (--.)
        sendDash()
        sendSymbolGap()
        sendDash()
        sendSymbolGap()
        sendDot()
        sendLetterGap()
        assertEquals("ETANG", receiver.decodedText.value)

        // Send 'E' (.)
        sendDot()
        sendLetterGap()
        assertEquals("ETANGE", receiver.decodedText.value)

        // Send 'R' (.-.)
        sendDot()
        sendSymbolGap()
        sendDash()
        sendSymbolGap()
        sendDot()

        // Idle reaches SESSION_END_TIMEOUT_MS -> session finalizes
        receiver.handleIdleGap(MorseReceiverEngine.SESSION_END_TIMEOUT_MS)

        assertEquals(MorseReceiverEngine.SessionState.READY, receiver.sessionState.value)
        assertEquals("ETANGER", receiver.rawDecodedText.value)
        assertEquals("DANGER", receiver.decodedText.value)
    }

    // --- PHASE 8.0H: PREAMBLE EXCLUSION & READY STATE TESTS ---

    @Test
    fun `test 8_0H 1 - preamble is detected but not emitted as S`() {
        assertFalse(receiver.preambleDetected.value)
        assertEquals("", receiver.decodedText.value)
        assertEquals("", receiver.rawDecodedText.value)

        sendPreamble()

        assertTrue(receiver.preambleDetected.value)
        assertEquals("", receiver.decodedText.value)
        assertEquals("", receiver.rawDecodedText.value)
        assertEquals("", receiver.currentSymbols.value)
    }

    @Test
    fun `test 8_0H 2 - preamble followed by DANGER produces DANGER`() {
        sendPreamble()
        sendWord("DANGER")
        receiver.handleIdleGap(MorseReceiverEngine.SESSION_END_TIMEOUT_MS)

        assertEquals(MorseReceiverEngine.SessionState.READY, receiver.sessionState.value)
        assertEquals("DANGER", receiver.rawDecodedText.value)
        assertEquals("DANGER", receiver.decodedText.value)
    }

    @Test
    fun `test 8_0H 3 - preamble followed by HELP produces HELP`() {
        sendPreamble()
        sendWord("HELP")
        receiver.handleIdleGap(MorseReceiverEngine.SESSION_END_TIMEOUT_MS)

        assertEquals(MorseReceiverEngine.SessionState.READY, receiver.sessionState.value)
        assertEquals("HELP", receiver.rawDecodedText.value)
        assertEquals("HELP", receiver.decodedText.value)
    }

    @Test
    fun `test 8_0H 4 - preamble followed by SOS preserves real leading S`() {
        sendPreamble()
        sendWord("SOS")
        receiver.handleIdleGap(MorseReceiverEngine.SESSION_END_TIMEOUT_MS)

        assertEquals(MorseReceiverEngine.SessionState.READY, receiver.sessionState.value)
        assertEquals("SOS", receiver.rawDecodedText.value)
        assertEquals("SOS", receiver.decodedText.value)
    }

    @Test
    fun `test 8_0H 5 - preamble followed by SAFE preserves real leading S`() {
        sendPreamble()
        sendWord("SAFE")
        receiver.handleIdleGap(MorseReceiverEngine.SESSION_END_TIMEOUT_MS)

        assertEquals(MorseReceiverEngine.SessionState.READY, receiver.sessionState.value)
        assertEquals("SAFE", receiver.rawDecodedText.value)
        assertEquals("SAFE", receiver.decodedText.value)
    }

    @Test
    fun `test 8_0H 6 - preamble followed by SHELL preserves real leading S`() {
        sendPreamble()
        sendWord("SHELL")
        receiver.handleIdleGap(MorseReceiverEngine.SESSION_END_TIMEOUT_MS)

        assertEquals(MorseReceiverEngine.SessionState.READY, receiver.sessionState.value)
        assertEquals("SHELL", receiver.rawDecodedText.value)
        assertEquals("SHELL", receiver.decodedText.value)
    }

    @Test
    fun `test 8_0H 7 - first real S after preamble is not discarded`() {
        sendPreamble()
        assertTrue(receiver.preambleDetected.value)

        // Send 'S' (...)
        sendDot()
        sendSymbolGap()
        sendDot()
        sendSymbolGap()
        sendDot()
        sendLetterGap()

        assertEquals("S", receiver.decodedText.value)
        assertEquals("S", receiver.rawDecodedText.value)
    }

    @Test
    fun `test 8_0H 8 - automatic correction receives DANGER, not SDANGER`() {
        val testCorrector = MorseEnglishCorrector(setOf("DANGER", "HELLO", "WORLD"))
        receiver.englishCorrector = testCorrector

        sendPreamble()
        sendWord("DANGER")
        receiver.handleIdleGap(MorseReceiverEngine.SESSION_END_TIMEOUT_MS)

        assertEquals("DANGER", receiver.rawDecodedText.value)
        assertEquals("DANGER", receiver.decodedText.value)
    }

    @Test
    fun `test 8_0H 9 - automatic correction receives HELP, not SHELP`() {
        // SHELP used to be miscorrected to SHELL because edit distance was 1 to SHELL
        val testCorrector = MorseEnglishCorrector(setOf("HELP", "SHELL", "HELLO"))
        receiver.englishCorrector = testCorrector

        sendPreamble()
        sendWord("HELP")
        receiver.handleIdleGap(MorseReceiverEngine.SESSION_END_TIMEOUT_MS)

        assertEquals("HELP", receiver.rawDecodedText.value)
        assertEquals("HELP", receiver.decodedText.value)
    }

    @Test
    fun `test 8_0H 10 - completed session remains READY`() {
        sendPreamble()
        sendWord("HI")
        receiver.handleIdleGap(MorseReceiverEngine.SESSION_END_TIMEOUT_MS)

        assertEquals(MorseReceiverEngine.SessionState.READY, receiver.sessionState.value)
        assertEquals("HI", receiver.decodedText.value)

        // Further idle time maintains READY state and preserved message
        receiver.handleIdleGap(1000L)
        assertEquals(MorseReceiverEngine.SessionState.READY, receiver.sessionState.value)
        assertEquals("HI", receiver.decodedText.value)
    }

    @Test
    fun `test 8_0H 11 - next preamble starts a clean new session`() {
        sendPreamble()
        sendWord("OK")
        receiver.handleIdleGap(MorseReceiverEngine.SESSION_END_TIMEOUT_MS)

        assertEquals(MorseReceiverEngine.SessionState.READY, receiver.sessionState.value)
        assertEquals("OK", receiver.decodedText.value)

        // New transmission arrives with its own preamble
        sendPreamble()
        assertEquals(MorseReceiverEngine.SessionState.RECEIVING, receiver.sessionState.value)
        assertEquals("", receiver.decodedText.value)
        assertEquals("", receiver.rawDecodedText.value)

        sendWord("GO")
        receiver.handleIdleGap(MorseReceiverEngine.SESSION_END_TIMEOUT_MS)

        assertEquals(MorseReceiverEngine.SessionState.READY, receiver.sessionState.value)
        assertEquals("GO", receiver.decodedText.value)
    }

    @Test
    fun `test 8_0H 12 - camera and receiver remains armed while WAITING`() {
        assertEquals(MorseReceiverEngine.SessionState.WAITING, receiver.sessionState.value)
        assertEquals("", receiver.decodedText.value)
        assertFalse(receiver.preambleDetected.value)

        // Multiple idle gaps while waiting do not change WAITING state
        receiver.handleIdleGap(500L)
        receiver.handleIdleGap(1500L)
        assertEquals(MorseReceiverEngine.SessionState.WAITING, receiver.sessionState.value)

        // Light signal triggers transition to RECEIVING
        sendDot()
        assertEquals(MorseReceiverEngine.SessionState.RECEIVING, receiver.sessionState.value)
    }

    // --- PHASE 8.0I: OPTICAL DETECTION RANGE & ADAPTIVE BASELINE TESTS ---

    @Test
    fun `test 8_0I 1 - weak but valid distant signal is detected`() {
        receiver.setAmbientBaseline(40.0f)
        receiver.setCalibrationDone()

        // 3-meter distant signal: baseline 40.0f, brightSignal 50.0f -> contrast = 10.0f (> 8.5f ON threshold)
        receiver.processOpticalSample(50.0f, 100L)
        assertFalse(receiver.isLightOn.value) // Candidate pending (< 25ms)

        receiver.processOpticalSample(50.0f, 135L) // 35ms persisted (>= 25ms glitch filter)
        assertTrue("Weak distant signal (contrast 10.0f) must trigger Light ON", receiver.isLightOn.value)
    }

    @Test
    fun `test 8_0I 2 - medium signal is detected`() {
        receiver.setAmbientBaseline(40.0f)
        receiver.setCalibrationDone()

        // Medium distance signal: contrast = 30.0f
        receiver.processOpticalSample(70.0f, 100L)
        receiver.processOpticalSample(70.0f, 135L)
        assertTrue("Medium signal must trigger Light ON", receiver.isLightOn.value)
    }

    @Test
    fun `test 8_0I 3 - strong close signal is detected`() {
        receiver.setAmbientBaseline(40.0f)
        receiver.setCalibrationDone()

        // Close range saturated signal: contrast = 210.0f
        receiver.processOpticalSample(250.0f, 100L)
        receiver.processOpticalSample(250.0f, 135L)
        assertTrue("Strong close signal must trigger Light ON", receiver.isLightOn.value)
    }

    @Test
    fun `test 8_0I 4 - ambient room lighting alone remains OFF`() {
        receiver.setAmbientBaseline(40.0f)
        receiver.setCalibrationDone()

        // Ambient ripples: contrast <= 2.5f (well below 8.5f ON threshold)
        receiver.processOpticalSample(41.0f, 100L)
        receiver.processOpticalSample(42.5f, 135L)
        receiver.processOpticalSample(41.5f, 170L)
        receiver.processOpticalSample(40.5f, 205L)
        assertFalse("Ambient room noise alone must remain Light OFF", receiver.isLightOn.value)
    }

    @Test
    fun `test 8_0I 5 - small isolated noise spike does not become ON`() {
        receiver.setAmbientBaseline(40.0f)
        receiver.setCalibrationDone()

        // 10ms transient spike (below 25ms glitch threshold)
        receiver.processOpticalSample(60.0f, 100L)
        assertFalse(receiver.isLightOn.value)

        // Drops back to ambient after 10ms
        receiver.processOpticalSample(40.0f, 110L)
        assertFalse("Single-frame noise spike must be rejected by glitch filter", receiver.isLightOn.value)
    }

    @Test
    fun `test 8_0I 6 - baseline does not chase an active flashlight`() {
        receiver.setAmbientBaseline(40.0f)
        receiver.setCalibrationDone()

        val initialBaseline = receiver.getAmbientBaseline()
        // Flashlight active at 52.0f (contrast = 12.0f) across 10 frames (~300ms)
        var t = 100L
        for (i in 0 until 10) {
            receiver.processOpticalSample(52.0f, t)
            t += 33L
        }

        // Ambient baseline must NOT have climbed up to 52.0f (must remain frozen at 40.0f)
        assertEquals("Baseline must freeze during active light", initialBaseline, receiver.getAmbientBaseline(), 0.01f)
    }

    @Test
    fun `test 8_0I 7 - strong flashlight can transition OFF correctly`() {
        receiver.setAmbientBaseline(40.0f)
        receiver.setCalibrationDone()

        // Turn ON with strong close signal (250.0f)
        receiver.processOpticalSample(250.0f, 100L)
        receiver.processOpticalSample(250.0f, 135L)
        assertTrue(receiver.isLightOn.value)

        // Close-range shutoff: lens flare leaves residual light at 60.0f (contrast 20.0f)
        // With peak contrast 210.0f, dynamic OFF threshold is 32.0f (> 20.0f).
        receiver.processOpticalSample(60.0f, 200L)
        receiver.processOpticalSample(60.0f, 235L)
        assertFalse("Close-range lens flare must trigger fast OFF transition", receiver.isLightOn.value)
    }

    @Test
    fun `test 8_0I 8 - hysteresis prevents ON OFF oscillation`() {
        receiver.setAmbientBaseline(40.0f)
        receiver.setCalibrationDone()

        // Turn ON: 50.0f (contrast 10.0f > 8.5f)
        receiver.processOpticalSample(50.0f, 100L)
        receiver.processOpticalSample(50.0f, 135L)
        assertTrue(receiver.isLightOn.value)

        // Moderate dip: 46.0f (contrast 6.0f, below 8.5f but above 4.5f OFF threshold)
        receiver.processOpticalSample(46.0f, 170L)
        receiver.processOpticalSample(46.0f, 205L)
        assertTrue("Hysteresis must hold ON state when above 4.5f", receiver.isLightOn.value)

        // Drop below OFF threshold: 43.0f (contrast 3.0f < 4.5f)
        receiver.processOpticalSample(43.0f, 240L)
        receiver.processOpticalSample(43.0f, 275L)
        assertFalse("Must turn OFF once contrast drops below 4.5f", receiver.isLightOn.value)
    }

    @Test
    fun `test 8_0I 9 - candidate persistence still works`() {
        receiver.setAmbientBaseline(40.0f)
        receiver.setCalibrationDone()

        // Frame at t=100ms
        receiver.processOpticalSample(55.0f, 100L)
        assertFalse(receiver.isLightOn.value)

        // Frame at t=115ms (15ms elapsed < 25ms threshold)
        receiver.processOpticalSample(55.0f, 115L)
        assertFalse(receiver.isLightOn.value)

        // Frame at t=130ms (30ms elapsed >= 25ms threshold)
        receiver.processOpticalSample(55.0f, 130L)
        assertTrue("Candidate must transition to ON once persisted >= 25ms", receiver.isLightOn.value)
    }

    // --- PHASE 8.1A: ADAPTIVE OPTICAL SOURCE ACQUISITION TESTS ---

    private fun createTestFrame(width: Int, height: Int, backgroundLuma: Int = 40): java.nio.ByteBuffer {
        val buffer = java.nio.ByteBuffer.allocateDirect(width * height)
        for (i in 0 until width * height) {
            buffer.put(backgroundLuma.toByte())
        }
        buffer.rewind()
        return buffer
    }

    private fun setPixels(buffer: java.nio.ByteBuffer, width: Int, points: List<Pair<Int, Int>>, luma: Int) {
        for ((x, y) in points) {
            val idx = (y * width) + x
            if (idx in 0 until buffer.limit()) {
                buffer.put(idx, luma.toByte())
            }
        }
    }

    @Test
    fun `test 8_1A 1 - isolated single bright pixel does not immediately lock`() {
        val frame = createTestFrame(100, 100, 40)
        setPixels(frame, 100, listOf(50 to 50), 255)

        receiver.processFrame(100, 100, frame, 100, 1, 100L)

        assertEquals(MorseReceiverEngine.SourceState.NO_SOURCE, receiver.sourceState.value)
        assertFalse(receiver.isSourceLocked.value)
    }

    @Test
    fun `test 8_1A 2 - small concentrated 3 to 8 pixel source can lock`() {
        val frame1 = createTestFrame(100, 100, 40)
        val cluster = listOf(50 to 50, 50 to 51, 51 to 50, 51 to 51, 50 to 52)
        setPixels(frame1, 100, cluster, 160)

        // Frame 1 -> SEARCHING
        receiver.processFrame(100, 100, frame1, 100, 1, 100L)
        assertEquals(MorseReceiverEngine.SourceState.SEARCHING, receiver.sourceState.value)

        // Frame 2 at t=133L -> LOCKED
        val frame2 = createTestFrame(100, 100, 40)
        setPixels(frame2, 100, cluster, 160)
        receiver.processFrame(100, 100, frame2, 100, 1, 133L)

        assertEquals(MorseReceiverEngine.SourceState.LOCKED, receiver.sourceState.value)
        assertTrue(receiver.isSourceLocked.value)
    }

    @Test
    fun `test 8_1A 3 - medium bright source can lock`() {
        val cluster = mutableListOf<Pair<Int, Int>>()
        for (dy in -1..1) {
            for (dx in -1..2) {
                cluster.add(50 + dx to 50 + dy)
            }
        }

        val frame1 = createTestFrame(100, 100, 40)
        setPixels(frame1, 100, cluster, 120)
        receiver.processFrame(100, 100, frame1, 100, 1, 100L)
        assertEquals(MorseReceiverEngine.SourceState.SEARCHING, receiver.sourceState.value)

        val frame2 = createTestFrame(100, 100, 40)
        setPixels(frame2, 100, cluster, 120)
        receiver.processFrame(100, 100, frame2, 100, 1, 133L)
        assertEquals(MorseReceiverEngine.SourceState.LOCKED, receiver.sourceState.value)
        assertTrue(receiver.isSourceLocked.value)
    }

    @Test
    fun `test 8_1A 4 - large saturated source can lock`() {
        val cluster = mutableListOf<Pair<Int, Int>>()
        for (dy in -3..3) {
            for (dx in -3..3) {
                cluster.add(50 + dx to 50 + dy)
            }
        }

        val frame1 = createTestFrame(100, 100, 40)
        setPixels(frame1, 100, cluster, 255)
        receiver.processFrame(100, 100, frame1, 100, 1, 100L)
        assertEquals(MorseReceiverEngine.SourceState.SEARCHING, receiver.sourceState.value)

        val frame2 = createTestFrame(100, 100, 40)
        setPixels(frame2, 100, cluster, 255)
        receiver.processFrame(100, 100, frame2, 100, 1, 133L)
        assertEquals(MorseReceiverEngine.SourceState.LOCKED, receiver.sourceState.value)
        assertTrue(receiver.isSourceLocked.value)
    }

    @Test
    fun `test 8_1A 5 - candidate slightly off-center can lock`() {
        // Off-center spot at (35, 40) within 60% search window [20..80, 20..80]
        val cluster = listOf(35 to 40, 36 to 40, 35 to 41, 36 to 41, 35 to 42)

        val frame1 = createTestFrame(100, 100, 40)
        setPixels(frame1, 100, cluster, 150)
        receiver.processFrame(100, 100, frame1, 100, 1, 100L)
        assertEquals(MorseReceiverEngine.SourceState.SEARCHING, receiver.sourceState.value)

        val frame2 = createTestFrame(100, 100, 40)
        setPixels(frame2, 100, cluster, 150)
        receiver.processFrame(100, 100, frame2, 100, 1, 133L)
        assertEquals(MorseReceiverEngine.SourceState.LOCKED, receiver.sourceState.value)
        assertTrue(receiver.isSourceLocked.value)
    }

    @Test
    fun `test 8_1A 6 - source movement within tracking tolerance remains locked`() {
        val cluster1 = listOf(50 to 50, 50 to 51, 51 to 50, 51 to 51, 50 to 52)
        val frame1 = createTestFrame(100, 100, 40)
        setPixels(frame1, 100, cluster1, 160)
        receiver.processFrame(100, 100, frame1, 100, 1, 100L)

        val frame2 = createTestFrame(100, 100, 40)
        setPixels(frame2, 100, cluster1, 160)
        receiver.processFrame(100, 100, frame2, 100, 1, 133L)
        assertEquals(MorseReceiverEngine.SourceState.LOCKED, receiver.sourceState.value)

        // Hand jitter: moves by 6 pixels to (55, 53)
        val cluster2 = listOf(55 to 53, 55 to 54, 56 to 53, 56 to 54, 55 to 55)
        val frame3 = createTestFrame(100, 100, 40)
        setPixels(frame3, 100, cluster2, 160)
        receiver.processFrame(100, 100, frame3, 100, 1, 166L)

        assertEquals("Must remain locked when hand moves slightly", MorseReceiverEngine.SourceState.LOCKED, receiver.sourceState.value)
        assertTrue(receiver.isSourceLocked.value)
    }

    @Test
    fun `test 8_1A 7 - brief one-frame source loss does not immediately unlock`() {
        val cluster = listOf(50 to 50, 50 to 51, 51 to 50, 51 to 51, 50 to 52)
        val frame1 = createTestFrame(100, 100, 40)
        setPixels(frame1, 100, cluster, 160)
        receiver.processFrame(100, 100, frame1, 100, 1, 100L)

        val frame2 = createTestFrame(100, 100, 40)
        setPixels(frame2, 100, cluster, 160)
        receiver.processFrame(100, 100, frame2, 100, 1, 133L)
        assertEquals(MorseReceiverEngine.SourceState.LOCKED, receiver.sourceState.value)

        // Temporary light shutoff (Morse gap of 1 frame)
        val emptyFrame = createTestFrame(100, 100, 40)
        receiver.processFrame(100, 100, emptyFrame, 100, 1, 166L)

        assertEquals("Brief source loss during Morse gap must retain lock", MorseReceiverEngine.SourceState.LOCKED, receiver.sourceState.value)
        assertTrue(receiver.isSourceLocked.value)
    }

    @Test
    fun `test 8_1A 8 - sustained source loss eventually unlocks`() {
        val cluster = listOf(50 to 50, 50 to 51, 51 to 50, 51 to 51, 50 to 52)
        val frame1 = createTestFrame(100, 100, 40)
        setPixels(frame1, 100, cluster, 160)
        receiver.processFrame(100, 100, frame1, 100, 1, 100L)

        val frame2 = createTestFrame(100, 100, 40)
        setPixels(frame2, 100, cluster, 160)
        receiver.processFrame(100, 100, frame2, 100, 1, 133L)
        assertEquals(MorseReceiverEngine.SourceState.LOCKED, receiver.sourceState.value)

        // Sustained absence > TRACKING_MAX_LOSS_MS (1800ms)
        val emptyFrame = createTestFrame(100, 100, 40)
        receiver.processFrame(100, 100, emptyFrame, 100, 1, 2100L)

        assertFalse("Sustained absence must drop lock", receiver.isSourceLocked.value)
        org.junit.Assert.assertNotEquals(MorseReceiverEngine.SourceState.LOCKED, receiver.sourceState.value)
    }

    @Test
    fun `test 8_1A 9 - weak source with sufficient local contrast can lock`() {
        // Distant source: background 35, spot 52 -> local contrast 17 (> 7.5)
        val cluster = listOf(50 to 50, 50 to 51, 51 to 50, 51 to 51, 50 to 52)
        val frame1 = createTestFrame(100, 100, 35)
        setPixels(frame1, 100, cluster, 52)
        receiver.processFrame(100, 100, frame1, 100, 1, 100L)

        val frame2 = createTestFrame(100, 100, 35)
        setPixels(frame2, 100, cluster, 52)
        receiver.processFrame(100, 100, frame2, 100, 1, 133L)

        assertEquals(MorseReceiverEngine.SourceState.LOCKED, receiver.sourceState.value)
        assertTrue(receiver.isSourceLocked.value)
    }

    @Test
    fun `test 8_1A 10 - broad ambient brightness without concentrated source does not lock`() {
        // Flat white wall where all pixels are 150 (contrast = 0)
        val frame = createTestFrame(100, 100, 150)
        receiver.processFrame(100, 100, frame, 100, 1, 100L)

        assertEquals(MorseReceiverEngine.SourceState.NO_SOURCE, receiver.sourceState.value)
        assertFalse(receiver.isSourceLocked.value)
    }

    @Test
    fun `test 8_1A 11 - ON OFF transitions remain hysteresis-protected`() {
        receiver.setAmbientBaseline(40.0f)
        receiver.setCalibrationDone()

        // Turn ON: 50.0f (contrast 10.0f > 8.5f)
        receiver.processOpticalSample(50.0f, 100L)
        receiver.processOpticalSample(50.0f, 135L)
        assertTrue("Must turn ON", receiver.isLightOn.value)

        // Dip to 46.0f (contrast 6.0f > 4.5f OFF threshold)
        receiver.processOpticalSample(46.0f, 170L)
        receiver.processOpticalSample(46.0f, 205L)
        assertTrue("Hysteresis must hold ON state", receiver.isLightOn.value)

        // Drop to 43.0f (contrast 3.0f < 4.5f)
        receiver.processOpticalSample(43.0f, 240L)
        receiver.processOpticalSample(43.0f, 275L)
        assertFalse("Must turn OFF once contrast drops below 4.5f", receiver.isLightOn.value)
    }

    @Test
    fun `test 8_1A 12 - source acquisition does not modify Morse protocol behavior`() {
        sendPreamble()
        sendWord("SOS")
        receiver.handleIdleGap(MorseReceiverEngine.SESSION_END_TIMEOUT_MS)

        assertEquals(MorseReceiverEngine.SessionState.READY, receiver.sessionState.value)
        assertEquals("SOS", receiver.rawDecodedText.value)
        assertEquals("SOS", receiver.decodedText.value)
    }
}

