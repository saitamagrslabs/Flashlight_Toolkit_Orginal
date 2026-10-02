package com.saitamagrs.flashnow.morse.core

/**
 * Encapsulates optical Morse communication protocol details, including synchronization preamble
 * and complete frame sequence generation.
 */
object MorseProtocol {

    /**
     * Pulse element representing a state and duration in light transmission.
     */
    data class FlashPulse(
        val isOn: Boolean,
        val durationMs: Long
    )

    /**
     * Preamble pattern: ON 1T, OFF 1T, ON 1T, OFF 1T, ON 1T, OFF 3T
     * Pattern is 3 DOTs separated by SYMBOL_GAPs, ending with a LETTER_GAP.
     */
    val PREAMBLE_PULSES: List<FlashPulse> = listOf(
        FlashPulse(true, MorseTiming.DOT_DURATION_MS),
        FlashPulse(false, MorseTiming.SYMBOL_GAP_MS),
        FlashPulse(true, MorseTiming.DOT_DURATION_MS),
        FlashPulse(false, MorseTiming.SYMBOL_GAP_MS),
        FlashPulse(true, MorseTiming.DOT_DURATION_MS),
        FlashPulse(false, MorseTiming.LETTER_GAP_MS)
    )

    /**
     * Pattern string for preamble ("...").
     */
    const val PREAMBLE_MORSE_PATTERN = "..."

    /**
     * Generates a complete frame sequence for a given text prompt.
     * Frame structure: Preamble + Encoded Message Pulses + End Gap (OFF 10T).
     */
    fun buildFrame(text: String): List<FlashPulse> {
        val pulses = mutableListOf<FlashPulse>()

        // 1. Add Preamble
        pulses.addAll(PREAMBLE_PULSES)

        // 2. Add Body text
        val cleanedText = text.trim().uppercase()
        val words = cleanedText.split("\\s+".toRegex())

        for ((wIdx, word) in words.withIndex()) {
            val chars = word.toCharArray()
            for ((cIdx, char) in chars.withIndex()) {
                val morse = MorseCodec.encodeChar(char) ?: continue
                val symbols = morse.toCharArray()
                for ((sIdx, symbol) in symbols.withIndex()) {
                    when (symbol) {
                        '.' -> pulses.add(FlashPulse(true, MorseTiming.DOT_DURATION_MS))
                        '-' -> pulses.add(FlashPulse(true, MorseTiming.DASH_DURATION_MS))
                    }

                    // Symbol gap (1T) if not last symbol in char
                    if (sIdx < symbols.size - 1) {
                        pulses.add(FlashPulse(false, MorseTiming.SYMBOL_GAP_MS))
                    }
                }

                // Letter gap (3T) if not last char in word
                if (cIdx < chars.size - 1) {
                    pulses.add(FlashPulse(false, MorseTiming.LETTER_GAP_MS))
                }
            }

            // Word gap (7T) if not last word
            if (wIdx < words.size - 1) {
                pulses.add(FlashPulse(false, MorseTiming.WORD_GAP_MS))
            }
        }

        // 3. Add End Frame Gap (10T)
        pulses.add(FlashPulse(false, MorseTiming.END_GAP_MS))

        return pulses
    }
}
