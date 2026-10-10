package com.saitamagrs.flashnow.morse.decoder

import com.saitamagrs.flashnow.morse.core.MorseCodec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Result of classifying an optical light pulse duration.
 */
enum class PulseResult {
    DOT,
    DASH,
    INVALID
}

/**
 * Result of classifying an optical silence / gap duration.
 */
enum class GapResult {
    SYMBOL_GAP,
    LETTER_GAP,
    WORD_GAP,
    END_GAP,
    INVALID
}

/**
 * Configuration for Morse timing classification thresholds.
 *
 * All thresholds are derived from base unit T = 150ms:
 * - Nominal Dot: 150ms (window: [minPulseMs .. dotDashThresholdMs))
 * - Nominal Dash: 450ms (window: [dotDashThresholdMs .. maxPulseMs])
 * - Nominal Symbol Gap: 150ms (window: [0 .. symbolLetterGapThresholdMs))
 * - Nominal Letter Gap: 450ms (window: [symbolLetterGapThresholdMs .. letterWordGapThresholdMs))
 * - Nominal Word Gap: 1050ms (window: [letterWordGapThresholdMs .. wordEndGapThresholdMs))
 * - Nominal End Gap: 1500ms (window: >= wordEndGapThresholdMs)
 */
data class MorseDecoderConfig(
    val minPulseMs: Long = 40L,
    val dotDashThresholdMs: Long = 275L,
    val maxPulseMs: Long = 900L,
    val symbolLetterGapThresholdMs: Long = 280L,
    val letterWordGapThresholdMs: Long = 750L,
    val wordEndGapThresholdMs: Long = 1300L,
    val maxGapMs: Long = 10_000L,
    val emitReplacementOnInvalid: Boolean = false,
    val replacementChar: Char = '?'
)

/**
 * Optional listener for decoder lifecycle and symbol events.
 */
interface MorseDecoderListener {
    fun onSymbolAppended(symbol: Char, currentBuffer: String) {}
    fun onCharacterDecoded(char: Char, pattern: String) {}
    fun onWordBoundary() {}
    fun onInvalidPattern(pattern: String) {}
    fun onReset() {}
}

/**
 * Deterministic, camera-independent Morse code timing decoder.
 *
 * Converts completed light-pulse durations and silence gap durations into Morse symbols,
 * characters, and words. Has no dependency on CameraX, Android Views, or Android Lifecycle.
 */
class MorseDecoder(
    val config: MorseDecoderConfig = MorseDecoderConfig(),
    var listener: MorseDecoderListener? = null
) {
    companion object {
        // Standard international Morse punctuation and character fallbacks not present in base MorseCodec
        private val EXTENDED_MAP = mapOf(
            "-.--"   to 'Y', // Standard International Morse Y
            ".-.-.-" to '.',
            "--..--" to ',',
            "..--.." to '?',
            "-..-."  to '/',
            "-....-" to '-',
            "---..." to ':',
            ".----." to '\'',
            ".-..-." to '"',
            ".--.-." to '@',
            "-...-"  to '='
        )
    }

    private val symbolBuffer = java.lang.StringBuilder()
    private val messageBuilder = java.lang.StringBuilder()

    private val _currentSymbols = MutableStateFlow("")
    val currentSymbols: StateFlow<String> = _currentSymbols.asStateFlow()

    private val _decodedText = MutableStateFlow("")
    val decodedText: StateFlow<String> = _decodedText.asStateFlow()

    val symbols: String get() = _currentSymbols.value
    val text: String get() = _decodedText.value

    /**
     * Ingests a completed light-pulse duration in milliseconds.
     *
     * Classifies the pulse as DOT or DASH and appends to the current character symbol buffer.
     * Pulses outside [config.minPulseMs .. config.maxPulseMs] are rejected as INVALID.
     */
    fun onPulse(durationMs: Long): PulseResult {
        if (durationMs < config.minPulseMs || durationMs > config.maxPulseMs) {
            return PulseResult.INVALID
        }

        val symbol = if (durationMs < config.dotDashThresholdMs) {
            symbolBuffer.append('.')
            '.'
        } else {
            symbolBuffer.append('-')
            '-'
        }

        val currentStr = symbolBuffer.toString()
        _currentSymbols.value = currentStr
        listener?.onSymbolAppended(symbol, currentStr)

        return if (symbol == '.') PulseResult.DOT else PulseResult.DASH
    }

    /**
     * Ingests a completed silence / gap duration in milliseconds.
     *
     * Classifies the gap:
     * - SYMBOL_GAP: continues accumulating symbols within the current character.
     * - LETTER_GAP: finalizes the current character.
     * - WORD_GAP: finalizes the current character (if any) and appends a space.
     * - END_GAP: finalizes the current character (if any) and marks end of transmission frame.
     */
    fun onGap(durationMs: Long): GapResult {
        if (durationMs < 0) {
            return GapResult.INVALID
        }

        return when {
            durationMs < config.symbolLetterGapThresholdMs -> {
                GapResult.SYMBOL_GAP
            }
            durationMs < config.letterWordGapThresholdMs -> {
                finalizeCharacter()
                GapResult.LETTER_GAP
            }
            durationMs < config.wordEndGapThresholdMs -> {
                finalizeCharacter()
                appendWordBoundary()
                GapResult.WORD_GAP
            }
            else -> {
                finalizeCharacter()
                GapResult.END_GAP
            }
        }
    }

    /**
     * Finalizes the current symbol buffer into a decoded character.
     * Returns the decoded character, or null if buffer was empty or unrecognized.
     */
    fun finalizeCharacter(): Char? {
        if (symbolBuffer.isEmpty()) return null

        val pattern = symbolBuffer.toString()
        val char = MorseCodec.decodeLetter(pattern) ?: EXTENDED_MAP[pattern]

        if (char != null) {
            messageBuilder.append(char)
            _decodedText.value = messageBuilder.toString()
            listener?.onCharacterDecoded(char, pattern)
        } else {
            listener?.onInvalidPattern(pattern)
            if (config.emitReplacementOnInvalid) {
                messageBuilder.append(config.replacementChar)
                _decodedText.value = messageBuilder.toString()
            }
        }

        symbolBuffer.setLength(0)
        _currentSymbols.value = ""
        return char
    }

    /**
     * Appends a word boundary (space) if the current text is not empty and does not already end with space.
     */
    private fun appendWordBoundary() {
        if (messageBuilder.isNotEmpty() && !messageBuilder.endsWith(" ")) {
            messageBuilder.append(' ')
            _decodedText.value = messageBuilder.toString()
            listener?.onWordBoundary()
        }
    }

    /**
     * Explicitly flushes any pending symbol buffer at the end of a transmission.
     */
    fun flush(): Char? {
        return finalizeCharacter()
    }

    /**
     * Resets all internal buffers and state flows to initial empty state.
     */
    fun reset() {
        symbolBuffer.setLength(0)
        messageBuilder.setLength(0)
        _currentSymbols.value = ""
        _decodedText.value = ""
        listener?.onReset()
    }
}
