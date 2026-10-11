package com.saitamagrs.flashnow.morse.correction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
        // Deterministic dictionary with "PLANET" (not in HIGH_PRIORITY_WORDS)
        val customCorrector = MorseEnglishCorrector(setOf("PLANET"))
        // Input "QLANET":
        // Length 6, edit distance 1 from "PLANET" ('P' -> 'Q')
        // Base score = 100 - (1 * 25) = 75
        // First char 'Q' != 'P' (0 bonus). Last char 'T' == 'T' (+10 anchor bonus).
        // Morse letter dist: 'Q' (--.-) vs 'P' (.--.) -> dist is 2 (+10 bonus).
        // That gives 75 + 10 + 10 = 95 (HIGH).
        // What about distance 2 on length 6?
        // Let's test "QLANES":
        // Length 6, edit distance 2 from "PLANET" ('P'->'Q', 'T'->'S')
        // maxDist for length 6 is 2. Both candidates are length 6.
        // Base score = 100 - (2 * 25) = 50.
        // First char 'Q' != 'P' (0). Last char 'S' != 'T' (0).
        // Double-letter expansion? False.
        // HIGH_PRIORITY_WORDS contains PLANET? False.
        // Morse letter dist: 'Q' (--.-) vs 'P' (.--.) is 2, 'S' (...) vs 'T' (-) is 2.
        // Overall rawMorse vs candMorse:
        // raw:  "--.- .-.. .- -. . ..."
        // cand: ".--. .-.. .- -. . -"
        // Levenshtein between rawMorse and candMorse:
        // Diff at pos 0 ("--.-" vs ".--." is 2 substitutions) + diff at pos 5 ("..." vs "-" is 2 insertions).
        // Total morseDist = 4. Since morseDist > 2, NO morse letter bonus!
        // Unspaced stream:
        // raw:  "--.---..-...-..." (len 16)
        // cand: ".--..--..-...-"   (len 14)
        // Stream distance > 3 (no stream bonus).
        // Score = 50. Since 50 <= score < 75, confidence is strictly MEDIUM.
        val candidateResult = customCorrector.correctSingleWord("QLANES")

        // 1. Candidate must not be null
        assertNotNull("Expected non-null candidate for QLANES", candidateResult)
        // 2. Candidate word is PLANET
        assertEquals("PLANET", candidateResult?.first)
        // 3. Confidence must be exactly MEDIUM
        assertEquals(CorrectionConfidence.MEDIUM, candidateResult?.second)
        // 4. autoCorrectMessage must conservatively preserve the original word unchanged
        assertEquals("QLANES", customCorrector.autoCorrectMessage("QLANES", isFinal = true))
    }

    @Test
    fun testPhase2A_ExistingHighConfidenceCorrectionsRemainIntact() {
        // Optical digit substitutions: HEL1O -> HELLO, S0S -> SOS
        assertEquals("HELLO", corrector.autoCorrectMessage("HEL1O", isFinal = true))
        assertEquals("SOS", corrector.autoCorrectMessage("S0S", isFinal = true))
        // High confidence Morse typo: ETANGER -> DANGER
        assertEquals("DANGER", corrector.autoCorrectMessage("ETANGER", isFinal = true))
    }

    // ================= Phase 2B Whitespace Preservation Tests =================

    @Test
    fun testPhase2B_MultipleConsecutiveSpacesPreserved() {
        assertEquals("HELLO  WORLD", corrector.autoCorrectMessage("HELLO  WORLD", isFinal = true))
        assertEquals("HELLO   WORLD", corrector.autoCorrectMessage("HEILO   WORID", isFinal = true))
        assertEquals("A    B     C", corrector.autoCorrectMessage("A    B     C", isFinal = true))
    }

    @Test
    fun testPhase2B_LeadingAndTrailingSpacesPreserved() {
        assertEquals("  HELLO WORLD  ", corrector.autoCorrectMessage("  HEILO WORID  ", isFinal = true))
        assertEquals("   HELLO   ", corrector.autoCorrectMessage("   HEILO   ", isFinal = true))
        assertEquals(" HELLO ", corrector.autoCorrectMessage(" HEILO "))
    }

    @Test
    fun testPhase2B_TabBetweenWordsPreserved() {
        assertEquals("HELLO\tWORLD", corrector.autoCorrectMessage("HEILO\tWORID", isFinal = true))
        assertEquals("\tHELLO\t\tWORLD\t", corrector.autoCorrectMessage("\tHEILO\t\tWORID\t", isFinal = true))
    }

    @Test
    fun testPhase2B_NewlineBetweenWordsPreserved() {
        assertEquals("HELLO\nWORLD", corrector.autoCorrectMessage("HEILO\nWORID", isFinal = true))
        assertEquals("LINE1\n\nLINE2\n\n\nLINE3", corrector.autoCorrectMessage("LINE1\n\nLINE2\n\n\nLINE3", isFinal = true))
    }

    @Test
    fun testPhase2B_MixedSeparatorsPreserved() {
        val input = " \t HEILO \n\t WORID \r\n  "
        val expected = " \t HELLO \n\t WORLD \r\n  "
        assertEquals(expected, corrector.autoCorrectMessage(input, isFinal = true))
    }

    @Test
    fun testPhase2B_WhitespaceOnlyInputByteForByteUnchanged() {
        assertEquals("", corrector.autoCorrectMessage(""))
        assertEquals(" ", corrector.autoCorrectMessage(" "))
        assertEquals("   ", corrector.autoCorrectMessage("   "))
        assertEquals("\t\n  \r\n\t", corrector.autoCorrectMessage("\t\n  \r\n\t"))
    }

    @Test
    fun testPhase2B_PunctuationAroundWordsPreservedWithWhitespace() {
        assertEquals("  [HELLO] , \n \"WORLD\" ! ", corrector.autoCorrectMessage("  [HEILO] , \n \"WORID\" ! ", isFinal = true))
    }

    @Test
    fun testPhase2B_HighConfidenceTypoCorrectedWithSurroundingWhitespace() {
        assertEquals(" \n\t HELLO \t\n ", corrector.autoCorrectMessage(" \n\t HEL1O \t\n ", isFinal = true))
        assertEquals("  DANGER  ", corrector.autoCorrectMessage("  ETANGER  ", isFinal = true))
    }

    @Test
    fun testPhase2B_MediumConfidenceCandidateUnchangedWithWhitespacePreserved() {
        val customCorrector = MorseEnglishCorrector(setOf("PLANET"))
        val input = "  \t QLANES \n\n "
        assertEquals(input, customCorrector.autoCorrectMessage(input, isFinal = true))
    }
}
