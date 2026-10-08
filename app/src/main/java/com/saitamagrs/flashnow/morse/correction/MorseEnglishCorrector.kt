package com.saitamagrs.flashnow.morse.correction

import android.content.Context
import com.saitamagrs.flashnow.morse.core.MorseCodec
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.math.abs
import kotlin.math.min

/**
 * Confidence level of English word correction.
 */
enum class CorrectionConfidence {
    NONE,
    MEDIUM,
    HIGH
}

/**
 * Result of Morse English correction.
 */
data class CorrectionResult(
    val rawText: String,
    val suggestedText: String?,
    val confidence: CorrectionConfidence
) {
    val hasSuggestion: Boolean
        get() = suggestedText != null && confidence != CorrectionConfidence.NONE && suggestedText != rawText
}

/**
 * Offline, Morse-aware English word corrector.
 * Identifies likely English words when optical or Morse reception suffers minor transmission/glare errors.
 *
 * Design:
 * - Runs completely offline without external dependencies or cloud APIs.
 * - Exact dictionary matches require no suggestion.
 * - Protects short words (<= 3 chars) and numerical / punctuation sequences.
 * - Levenshtein distance combined with Morse-symbol and optical-glyph similarity scoring.
 */
class MorseEnglishCorrector(
    private val dictionary: Set<String>
) {

    companion object {
        const val ASSET_PATH = "morse/common_english_words.txt"

        // Priority terms commonly found in Morse and emergency transmissions
        val HIGH_PRIORITY_WORDS = setOf(
            "SOS", "HELP", "STOP", "YES", "NO", "OK", "MORSE", "LIGHT", "FLASH",
            "PHONE", "SEND", "RECEIVE", "MESSAGE", "BATTERY", "TORCH", "EMERGENCY",
            "HELLO", "TEST", "CHECK", "CODE", "RADIO", "POLICE", "FIRE", "MEDIC",
            "SAFE", "DANGER", "READY", "DONE", "START", "CANCEL", "WATER", "FOOD",
            "MAYDAY", "NORTH", "SOUTH", "EAST", "WEST", "WORLD"
        )

        // Common optical and Morse digit-glyph confusion pairs
        private val OPTICAL_DIGIT_MAP = mapOf(
            '0' to listOf('O'),
            '1' to listOf('I', 'L'),
            '5' to listOf('S'),
            '8' to listOf('B')
        )

        /**
         * Loads the dictionary once from application assets.
         */
        fun fromAssets(context: Context): MorseEnglishCorrector {
            val words = HashSet<String>(5000)
            try {
                context.assets.open(ASSET_PATH).use { inputStream ->
                    BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8)).use { reader ->
                        var line = reader.readLine()
                        while (line != null) {
                            val trimmed = line.trim().uppercase()
                            if (trimmed.isNotEmpty()) {
                                words.add(trimmed)
                            }
                            line = reader.readLine()
                        }
                    }
                }
            } catch (e: Exception) {
                // Fallback to high-priority vocabulary in case of I/O failure
                words.addAll(HIGH_PRIORITY_WORDS)
            }
            return MorseEnglishCorrector(words)
        }
    }

    // Pre-indexed words grouped by length for fast bounded lookup
    private val wordsByLength: Map<Int, List<String>> = dictionary.groupBy { it.length }

    /**
     * Evaluates a multi-word or single-word raw text and returns a correction result.
     */
    fun correct(rawText: String): CorrectionResult {
        val trimmed = rawText.trim()
        if (trimmed.isEmpty()) {
            return CorrectionResult(rawText, null, CorrectionConfidence.NONE)
        }

        // Split text by whitespace while preserving punctuation
        val tokens = trimmed.split("\\s+".toRegex())
        val correctedTokens = mutableListOf<String>()
        var overallConfidence = CorrectionConfidence.NONE
        var anyWordChanged = false

        for (token in tokens) {
            val (cleanedWord, prefix, suffix) = separatePunctuation(token)

            if (cleanedWord.isEmpty() || isPureNumericOrPunctuation(cleanedWord)) {
                // Pure numbers like "123" or pure punctuation stay untouched
                correctedTokens.add(token)
                continue
            }

            val upperWord = cleanedWord.uppercase()
            val wordResult = correctSingleWord(upperWord)

            if (wordResult != null && wordResult.first != upperWord) {
                correctedTokens.add("$prefix${wordResult.first}$suffix")
                anyWordChanged = true
                if (wordResult.second == CorrectionConfidence.HIGH) {
                    overallConfidence = CorrectionConfidence.HIGH
                } else if (overallConfidence != CorrectionConfidence.HIGH) {
                    overallConfidence = CorrectionConfidence.MEDIUM
                }
            } else {
                correctedTokens.add(token)
            }
        }

        return if (anyWordChanged) {
            val suggested = correctedTokens.joinToString(" ")
            CorrectionResult(rawText, suggested, overallConfidence)
        } else {
            CorrectionResult(rawText, null, CorrectionConfidence.NONE)
        }
    }

    /**
     * Auto-corrects a decoded message at the word level for direct user display.
     *
     * Rules:
     * - Completed words are evaluated against the dictionary.
     * - Valid dictionary words (e.g. HELLO, SOS, WORLD) are strictly preserved untouched.
     * - Invalid words (e.g. HEILO, WORID, S0S) are corrected using Morse-aware similarity scoring.
     * - If confidence is low or word is distant garbage (e.g. QZXMVK), the raw token is preserved.
     * - An in-progress trailing word (when isFinal is false and no trailing space) is left uncorrected
     *   to avoid mutating words while the sender is still transmitting letters.
     */
    fun autoCorrectMessage(rawMessage: String, isFinal: Boolean = false): String {
        if (rawMessage.isBlank()) return rawMessage

        val hasTrailingSpace = rawMessage.endsWith(" ")
        val tokens = rawMessage.trim().split("\\s+".toRegex())
        if (tokens.isEmpty()) return rawMessage

        val correctedTokens = mutableListOf<String>()

        for (index in tokens.indices) {
            val token = tokens[index]
            val isLastToken = (index == tokens.size - 1)

            // If it's the last token and we have no trailing space and not finalized, leave as raw
            if (isLastToken && !hasTrailingSpace && !isFinal) {
                correctedTokens.add(token)
                continue
            }

            val (cleanedWord, prefix, suffix) = separatePunctuation(token)
            if (cleanedWord.isEmpty() || isPureNumericOrPunctuation(cleanedWord)) {
                correctedTokens.add(token)
                continue
            }

            val upperWord = cleanedWord.uppercase()
            // If already valid in dictionary, keep it untouched
            if (dictionary.contains(upperWord) || HIGH_PRIORITY_WORDS.contains(upperWord)) {
                correctedTokens.add(token)
                continue
            }

            // Word is invalid, attempt Morse-aware correction
            val correction = correctSingleWord(upperWord)
            if (correction != null && correction.second != CorrectionConfidence.NONE) {
                correctedTokens.add("$prefix${correction.first}$suffix")
            } else {
                // Low confidence or garbage: preserve raw token
                correctedTokens.add(token)
            }
        }

        val result = correctedTokens.joinToString(" ")
        return if (hasTrailingSpace) "$result " else result
    }

    /**
     * Corrects a single uppercase word token.
     */
    fun correctSingleWord(word: String): Pair<String, CorrectionConfidence>? {
        // 1. Exact match in dictionary: no correction needed
        if (dictionary.contains(word)) {
            return Pair(word, CorrectionConfidence.HIGH)
        }

        // 2. Pure numbers: no correction
        if (word.all { it.isDigit() }) {
            return null
        }

        // 3. Short word protection (length <= 3)
        if (word.length <= 3) {
            // For length 1: never correct single letters (too ambiguous in Morse)
            if (word.length <= 1) return null

            // For length 2..3: only allow optical digit substitutions (e.g. S0S -> SOS, N0 -> NO)
            val digitSubst = tryOpticalDigitSubstitutions(word)
            if (digitSubst != null && dictionary.contains(digitSubst)) {
                return Pair(digitSubst, CorrectionConfidence.HIGH)
            }
            return null
        }

        // 4. Try optical digit substitution first (e.g. HEL1O -> HELLO)
        val digitSubst = tryOpticalDigitSubstitutions(word)
        if (digitSubst != null && dictionary.contains(digitSubst)) {
            return Pair(digitSubst, CorrectionConfidence.HIGH)
        }

        // 5. Bounded search within candidate words of similar length
        val maxDist = if (word.length in 4..5) 1 else 2
        var bestCandidate: String? = null
        var bestScore = -100

        // Search candidates with length in [len - maxDist, len + maxDist]
        val minLen = (word.length - maxDist).coerceAtLeast(2)
        val maxLen = word.length + maxDist

        for (len in minLen..maxLen) {
            val candidates = wordsByLength[len] ?: continue
            for (cand in candidates) {
                val dist = levenshteinDistance(word, cand, maxDist)
                if (dist > maxDist) continue

                val score = computeCandidateScore(word, cand, dist)
                if (score > bestScore) {
                    bestScore = score
                    bestCandidate = cand
                }
            }
        }

        if (bestCandidate != null && bestCandidate != word) {
            val confidence = when {
                bestScore >= 75 -> CorrectionConfidence.HIGH
                bestScore >= 50 -> CorrectionConfidence.MEDIUM
                else -> CorrectionConfidence.NONE
            }
            if (confidence != CorrectionConfidence.NONE) {
                return Pair(bestCandidate, confidence)
            }
        }

        return null
    }

    private fun computeCandidateScore(raw: String, candidate: String, charDist: Int): Int {
        var score = 100 - (charDist * 25)

        // Anchor bonus: First and last characters are critical anchor points
        if (raw.isNotEmpty() && candidate.isNotEmpty()) {
            if (raw.first() == candidate.first()) {
                score += 10
            }
            if (raw.last() == candidate.last()) {
                score += 10
            }
        }

        // Double-letter omission/expansion bonus (common in Morse when letter gap is tight)
        if (isDoubleLetterExpansion(raw, candidate)) {
            score += 35
        }

        // Bonus for high priority Morse / emergency words
        if (HIGH_PRIORITY_WORDS.contains(candidate)) {
            score += 15
        }

        // Morse symbol distance evaluation
        val rawMorse = encodeToMorseLetters(raw)
        val candMorse = encodeToMorseLetters(candidate)
        if (rawMorse.isNotEmpty() && candMorse.isNotEmpty()) {
            val morseDist = levenshteinDistance(rawMorse, candMorse, 4)
            if (morseDist <= 1) {
                score += 15
            } else if (morseDist <= 2) {
                score += 10
            }
        }

        // Unspaced Morse stream comparison (captures Morse split/merge errors like 'D' -> 'E T')
        val rawStream = raw.mapNotNull { MorseCodec.encodeChar(it) }.joinToString("")
        val candStream = candidate.mapNotNull { MorseCodec.encodeChar(it) }.joinToString("")
        if (rawStream.isNotEmpty() && candStream.isNotEmpty()) {
            val streamDist = levenshteinDistance(rawStream, candStream, 4)
            if (streamDist <= 1) {
                score += 20
            } else if (streamDist <= 2) {
                score += 15
            } else if (streamDist <= 3) {
                score += 10
            }
        }

        return score
    }

    private fun isDoubleLetterExpansion(raw: String, candidate: String): Boolean {
        if (candidate.length != raw.length + 1) return false
        for (i in 0 until candidate.length - 1) {
            if (candidate[i] == candidate[i + 1]) {
                val collapsed = candidate.removeRange(i, i + 1)
                if (collapsed == raw) return true
            }
        }
        return false
    }

    private fun encodeToMorseLetters(text: String): String {
        return buildString {
            for (c in text) {
                val m = MorseCodec.encodeChar(c)
                if (m != null) append(m).append(' ')
            }
        }.trim()
    }

    private fun tryOpticalDigitSubstitutions(word: String): String? {
        val hasDigits = word.any { it.isDigit() }
        if (!hasDigits) return null

        val chars = word.toCharArray()
        for (i in chars.indices) {
            val c = chars[i]
            val replacements = OPTICAL_DIGIT_MAP[c]
            if (replacements != null) {
                for (r in replacements) {
                    chars[i] = r
                    val candidate = String(chars)
                    if (dictionary.contains(candidate)) {
                        return candidate
                    }
                }
            }
        }
        return null
    }

    private fun isPureNumericOrPunctuation(str: String): Boolean {
        return str.all { it.isDigit() || !it.isLetterOrDigit() }
    }

    private fun separatePunctuation(token: String): Triple<String, String, String> {
        var start = 0
        var end = token.length

        while (start < end && !token[start].isLetterOrDigit()) {
            start++
        }
        while (end > start && !token[end - 1].isLetterOrDigit()) {
            end--
        }

        val prefix = token.substring(0, start)
        val core = token.substring(start, end)
        val suffix = token.substring(end)
        return Triple(core, prefix, suffix)
    }

    private fun levenshteinDistance(s1: String, s2: String, maxLimit: Int): Int {
        if (abs(s1.length - s2.length) > maxLimit) return maxLimit + 1
        val len1 = s1.length
        val len2 = s2.length

        var prev = IntArray(len2 + 1) { it }
        var curr = IntArray(len2 + 1)

        for (i in 1..len1) {
            curr[0] = i
            var minInRow = i
            for (j in 1..len2) {
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                curr[j] = min(min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost)
                if (curr[j] < minInRow) {
                    minInRow = curr[j]
                }
            }
            if (minInRow > maxLimit) {
                return maxLimit + 1
            }
            val temp = prev
            prev = curr
            curr = temp
        }
        return prev[len2]
    }
}
