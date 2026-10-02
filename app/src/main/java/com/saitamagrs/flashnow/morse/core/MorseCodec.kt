package com.saitamagrs.flashnow.morse.core

/**
 * Bidirectional codec for encoding alphanumeric text to Morse code patterns and decoding Morse patterns to text.
 */
object MorseCodec {

    private val CHAR_TO_MORSE = mapOf(
        'A' to ".-",    'B' to "-...",  'C' to "-.-.",  'D' to "-..",   'E' to ".",
        'F' to "..-.",  'G' to "--.",   'H' to "....",  'I' to "..",    'J' to ".---",
        'K' to "-.-",   'L' to ".-..",  'M' to "--",    'N' to "-.",    'O' to "---",
        'P' to ".--.",  'Q' to "--.-",  'R' to ".-.",   'S' to "...",   'T' to "-",
        'U' to "..-",   'V' to "...-",  'W' to ".--",   'X' to "-..-",  'Y' to ".-.--",
        'Z' to "--..",

        '0' to "-----", '1' to ".----", '2' to "..---", '3' to "...--", '4' to "....-",
        '5' to ".....", '6' to "-....", '7' to "--...", '8' to "---..", '9' to "----."
    )

    private val MORSE_TO_CHAR: Map<String, Char> = CHAR_TO_MORSE.entries.associate { (k, v) -> v to k }

    /**
     * Encodes a single character into its Morse pattern (e.g. 'S' -> "...").
     * Returns null if character is not supported.
     */
    fun encodeChar(c: Char): String? {
        return CHAR_TO_MORSE[c.uppercaseChar()]
    }

    /**
     * Decodes a single Morse pattern string into its corresponding uppercase character (e.g. "..." -> 'S').
     * Returns null if pattern is invalid.
     */
    fun decodeLetter(pattern: String): Char? {
        return MORSE_TO_CHAR[pattern.trim()]
    }

    /**
     * Encodes full text to Morse string with space-separated letters and ' / ' separated words.
     * Example: "SOS" -> "... --- ..."
     */
    fun encode(text: String): String {
        val uppercase = text.trim().uppercase()
        if (uppercase.isEmpty()) return ""

        val words = uppercase.split("\\s+".toRegex())
        return words.joinToString(" / ") { word ->
            word.mapNotNull { encodeChar(it) }.joinToString(" ")
        }
    }

    /**
     * Decodes a Morse string with space-separated letters and ' / ' or '/' separated words back to text.
     * Example: "... --- ..." -> "SOS"
     */
    fun decode(morseText: String): String {
        if (morseText.isBlank()) return ""

        val words = morseText.trim().split("\\s*/\\s*".toRegex())
        return words.joinToString(" ") { word ->
            word.trim().split("\\s+".toRegex()).mapNotNull { pattern ->
                decodeLetter(pattern)
            }.joinToString("")
        }
    }
}
