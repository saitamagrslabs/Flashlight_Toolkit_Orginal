package com.saitamagrs.flashnow.morse.correction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MorseEnglishCorrectorTest {

    private lateinit var corrector: MorseEnglishCorrector

    @Before
    fun setUp() {
        val testDictionary = setOf(
            "HELLO", "WORLD", "HELP", "SOS", "STOP", "YES", "NO", "OK",
            "MORSE", "LIGHT", "FLASH", "PHONE", "SEND", "RECEIVE", "MESSAGE",
            "EMERGENCY", "POLICE", "FIRE", "MEDIC", "WATER", "FOOD", "SAFE",
            "DANGER", "READY", "DONE", "START", "CANCEL"
        )
        corrector = MorseEnglishCorrector(testDictionary)
    }

    @Test
    fun testExactWordRequiresNoCorrection() {
        val result = corrector.correct("HELLO")
        assertFalse(result.hasSuggestion)
        assertNull(result.suggestedText)
        assertEquals(CorrectionConfidence.NONE, result.confidence)
    }

    @Test
    fun testSimpleOpticalTypoCorrected() {
        val result = corrector.correct("HEL1O")
        assertTrue(result.hasSuggestion)
        assertEquals("HELLO", result.suggestedText)
        assertEquals(CorrectionConfidence.HIGH, result.confidence)
    }

    @Test
    fun testOneCharacterMissingErrorCorrected() {
        val result = corrector.correct("HELO")
        assertTrue(result.hasSuggestion)
        assertEquals("HELLO", result.suggestedText)
        assertEquals(CorrectionConfidence.HIGH, result.confidence)
    }

    @Test
    fun testUnknownGibberishWordHasNoCorrection() {
        val result = corrector.correct("QZX")
        assertFalse(result.hasSuggestion)
        assertNull(result.suggestedText)
        assertEquals(CorrectionConfidence.NONE, result.confidence)
    }

    @Test
    fun testShortWordProtection() {
        // Short valid word NO should never be transformed to something else
        val resultNo = corrector.correct("NO")
        assertFalse(resultNo.hasSuggestion)
        assertNull(resultNo.suggestedText)

        // Short valid word OK should never be transformed
        val resultOk = corrector.correct("OK")
        assertFalse(resultOk.hasSuggestion)
        assertNull(resultOk.suggestedText)

        // Short optical confusion like S0S -> SOS
        val resultSos = corrector.correct("S0S")
        assertTrue(resultSos.hasSuggestion)
        assertEquals("SOS", resultSos.suggestedText)
    }

    @Test
    fun testMultiWordTextCorrection() {
        val result = corrector.correct("HEL1O WORLD")
        assertTrue(result.hasSuggestion)
        assertEquals("HELLO WORLD", result.suggestedText)
        assertEquals(CorrectionConfidence.HIGH, result.confidence)
    }

    @Test
    fun testPureNumbersRemainUnchanged() {
        val result = corrector.correct("123")
        assertFalse(result.hasSuggestion)
        assertNull(result.suggestedText)
        assertEquals(CorrectionConfidence.NONE, result.confidence)
    }

    @Test
    fun testNumbersWithPunctuationRemainUnchanged() {
        val result = corrector.correct("911!")
        assertFalse(result.hasSuggestion)
        assertNull(result.suggestedText)
    }

    @Test
    fun testEmptyAndBlankInput() {
        val resultEmpty = corrector.correct("")
        assertFalse(resultEmpty.hasSuggestion)

        val resultBlank = corrector.correct("   ")
        assertFalse(resultBlank.hasSuggestion)
    }
}
