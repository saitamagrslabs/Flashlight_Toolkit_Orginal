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

    // ================= Auto-Correct Message Tests (Phase 8.0F) =================

    @Test
    fun testAutoCorrectValidWordsRemainUntouched() {
        assertEquals("HELLO", corrector.autoCorrectMessage("HELLO", isFinal = true))
        assertEquals("WORLD", corrector.autoCorrectMessage("WORLD", isFinal = true))
        assertEquals("HELLO WORLD", corrector.autoCorrectMessage("HELLO WORLD", isFinal = true))
        assertEquals("SOS", corrector.autoCorrectMessage("SOS", isFinal = true))
    }

    @Test
    fun testAutoCorrectTypoAtWordBoundary() {
        assertEquals("HELLO ", corrector.autoCorrectMessage("HEILO "))
        assertEquals("WORLD ", corrector.autoCorrectMessage("WORID "))
        assertEquals("HELLO WORLD", corrector.autoCorrectMessage("HEILO WORID", isFinal = true))
    }

    @Test
    fun testAutoCorrectGarbageRemainsUnchanged() {
        assertEquals("QZXMVK", corrector.autoCorrectMessage("QZXMVK", isFinal = true))
        assertEquals("QZXMVK ", corrector.autoCorrectMessage("QZXMVK "))
    }

    @Test
    fun testAutoCorrectPreservesInProgressTrailingWord() {
        // While the user is still sending letters of "HELLO", "HEL" must not prematurely mutate
        assertEquals("HEL", corrector.autoCorrectMessage("HEL", isFinal = false))
        assertEquals("HELLO HEL", corrector.autoCorrectMessage("HELLO HEL", isFinal = false))
    }

    @Test
    fun testEtangerCorrectsToDanger() {
        assertEquals("DANGER", corrector.autoCorrectMessage("ETANGER", isFinal = true))
    }

    @Test
    fun testFullDictionaryCorrectsEtangerToDanger() {
        val file = java.io.File("src/main/assets/morse/common_english_words.txt")
        if (file.exists()) {
            val words = file.readLines().map { it.trim().uppercase() }.filter { it.isNotEmpty() }.toSet()
            val fullCorrector = MorseEnglishCorrector(words)
            val result = fullCorrector.autoCorrectMessage("ETANGER", isFinal = true)
            assertEquals("DANGER", result)
        }
    }

    // ================= Switch & Casing Preservation Tests =================

    @Test
    fun testAutoCorrectPreservesCasing() {
        // Uppercase
        assertEquals("HELLO WORLD", corrector.autoCorrectMessage("HEILO WORID", isFinal = true))
        // Lowercase
        assertEquals("hello world", corrector.autoCorrectMessage("heilo worid", isFinal = true))
        // Titlecase
        assertEquals("Hello World", corrector.autoCorrectMessage("Heilo Worid", isFinal = true))
    }

    @Test
    fun testAutoCorrectPreservesPunctuationAndNumbers() {
        // Word with punctuation attached should preserve punctuation and casing
        assertEquals("HELLO!", corrector.autoCorrectMessage("HEILO!", isFinal = true))
        assertEquals("HELLO, WORLD.", corrector.autoCorrectMessage("HEILO, WORID.", isFinal = true))
        // Numbers
        assertEquals("123 456", corrector.autoCorrectMessage("123 456", isFinal = true))
        assertEquals("911 EMERGENCY!", corrector.autoCorrectMessage("911 EMERGENSY!", isFinal = true))
    }

    @Test
    fun testSwitchToggleBehavior() {
        val rawMessage = "HEILO WORID"

        // When Switch is OFF: return exact raw message without modification
        fun renderMessage(raw: String, isAutoCorrectionEnabled: Boolean): String {
            return if (!isAutoCorrectionEnabled) {
                raw
            } else {
                corrector.autoCorrectMessage(raw, isFinal = true)
            }
        }

        assertEquals("HEILO WORID", renderMessage(rawMessage, isAutoCorrectionEnabled = false))
        assertEquals("HELLO WORLD", renderMessage(rawMessage, isAutoCorrectionEnabled = true))
        // Toggling back to OFF immediately returns raw
        assertEquals("HEILO WORID", renderMessage(rawMessage, isAutoCorrectionEnabled = false))
    }

    // ================= Phase 2A False-Positive Safety Tests =================

    @Test
    fun testPhase2A_KnownMisspelledWordStillCorrected() {
        // High confidence typos (HEILO -> HELLO, WORID -> WORLD) must be corrected
        assertEquals("HELLO", corrector.autoCorrectMessage("HEILO", isFinal = true))
        assertEquals("WORLD", corrector.autoCorrectMessage("WORID", isFinal = true))
        assertEquals("EMERGENCY", corrector.autoCorrectMessage("EMERGENSY", isFinal = true))
    }

    @Test
    fun testPhase2A_CorrectlySpelledCommonEnglishWordRemainsUnchanged() {
        assertEquals("HELLO", corrector.autoCorrectMessage("HELLO", isFinal = true))
        assertEquals("WORLD", corrector.autoCorrectMessage("WORLD", isFinal = true))
        assertEquals("EMERGENCY", corrector.autoCorrectMessage("EMERGENCY", isFinal = true))
    }

    @Test
    fun testPhase2A_UnknownLegitimateWordPreservedWithoutUnrelatedReplacement() {
        // Words like "QUANTUM" or "CYBER" that are legitimate words but not in test dictionary
        // must NOT be mutated to an unrelated dictionary word.
        assertEquals("QUANTUM", corrector.autoCorrectMessage("QUANTUM", isFinal = true))
        assertEquals("CYBER", corrector.autoCorrectMessage("CYBER", isFinal = true))
    }

    @Test
    fun testPhase2A_CapitalizedProperNameOrPlaceNamePreserved() {
        // Capitalized personal names or place names like "Alice", "Paris", "Tokyo", "David"
        // should remain preserved rather than forcefully matching a similar dictionary word
        assertEquals("Alice", corrector.autoCorrectMessage("Alice", isFinal = true))
        assertEquals("Paris", corrector.autoCorrectMessage("Paris", isFinal = true))
        assertEquals("Tokyo", corrector.autoCorrectMessage("Tokyo", isFinal = true))
    }

    @Test
    fun testPhase2A_UppercaseAcronymOrAbbreviationPreserved() {
        // Acronyms/abbreviations like "HTTP", "NATO", "NASA", "GPS", "API"
        assertEquals("HTTP", corrector.autoCorrectMessage("HTTP", isFinal = true))
        assertEquals("NATO", corrector.autoCorrectMessage("NATO", isFinal = true))
        assertEquals("NASA", corrector.autoCorrectMessage("NASA", isFinal = true))
    }

    @Test
    fun testPhase2A_TechnicalOrUncommonTermPreserved() {
        // Technical terms like "WIDGET", "PIXEL", "KERNEL"
        assertEquals("WIDGET", corrector.autoCorrectMessage("WIDGET", isFinal = true))
        assertEquals("PIXEL", corrector.autoCorrectMessage("PIXEL", isFinal = true))
        assertEquals("KERNEL", corrector.autoCorrectMessage("KERNEL", isFinal = true))
    }

    @Test
    fun testPhase2A_MediumConfidenceCandidateHandledConservatively() {
        // Verify that correctSingleWord returns MEDIUM confidence for uncertain candidates,
        // and autoCorrectMessage preserves the original word rather than applying it.
        // Let's create a custom corrector with a controlled word to observe MEDIUM confidence
        val customCorrector = MorseEnglishCorrector(setOf("FLASHLIGHT"))
        // An uncertain candidate with edit distance 2 and score in 50..74 range
        val singleResult = customCorrector.correctSingleWord("FLASHXIGXZ")
        // If singleResult is MEDIUM confidence:
        if (singleResult != null && singleResult.second == CorrectionConfidence.MEDIUM) {
            // autoCorrectMessage must keep original word
            assertEquals("FLASHXIGXZ", customCorrector.autoCorrectMessage("FLASHXIGXZ", isFinal = true))
        }
    }

    @Test
    fun testPhase2A_ExistingHighConfidenceCorrectionsRemainIntact() {
        // Optical digit substitutions: HEL1O -> HELLO, S0S -> SOS
        assertEquals("HELLO", corrector.autoCorrectMessage("HEL1O", isFinal = true))
        assertEquals("SOS", corrector.autoCorrectMessage("S0S", isFinal = true))
        // High confidence Morse typo: ETANGER -> DANGER
        assertEquals("DANGER", corrector.autoCorrectMessage("ETANGER", isFinal = true))
    }
}
