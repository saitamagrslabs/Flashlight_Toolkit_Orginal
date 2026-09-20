package com.saitamagrs.flashnow.utils

import android.os.Handler
import android.os.Looper
import android.util.Log

data class MorseAction(val isFlashOn: Boolean, val duration: Long)

class MorseCodeManager(private val flashlightController: FlashlightController) {

    private val handler = Handler(Looper.getMainLooper())
    private var isFlashing = false
    private var onStatusChange: ((Boolean) -> Unit)? = null

    // Standard Morse timings (dot = 1 unit)
    private val DOT_DURATION = 200L
    private val DASH_DURATION = 600L       // 3 units
    private val SYMBOL_GAP = 200L           // 1 unit (between dots/dashes)
    private val LETTER_GAP = 600L           // 3 units (between letters)
    private val WORD_GAP = 1400L             // 7 units (between words)

    val predefinedSignals = mapOf(
        "SOS" to "... --- ...",
        "HELP" to ".... . .-.. .--.",
        "OK" to "--- -.-",
        "YES" to "-.-- . ...",
        "NO" to "-. ---",
        "DANGER" to "-.. .- -. --. . .-."
    )

    // Removed all trailing spaces
    private val morseCodeMap = mapOf(
        'A' to ".-", 'B' to "-...", 'C' to "-.-.", 'D' to "-..", 'E' to ".",
        'F' to "..-.", 'G' to "--.", 'H' to "....", 'I' to "..", 'J' to ".---",
        'K' to "-.-", 'L' to ".-..", 'M' to "--", 'N' to "-.", 'O' to "---",
        'P' to ".--.", 'Q' to "--.-", 'R' to ".-.", 'S' to "...", 'T' to "-",
        'U' to "..-", 'V' to "...-", 'W' to ".--", 'X' to "-..-", 'Y' to "-.--",
        'Z' to "--..", '1' to ".----", '2' to "..---", '3' to "...--",
        '4' to "....-", '5' to ".....", '6' to "-....", '7' to "--...",
        '8' to "---..", '9' to "----.", '0' to "-----", ' ' to "/"
    )

    fun setOnStatusChangeListener(listener: (isFlashing: Boolean) -> Unit) {
        onStatusChange = listener
    }

    fun flashPredefinedSignal(signalName: String) {
        val morse = predefinedSignals[signalName]
        if (morse != null) {
            flashMorseSequence(morse)
        } else {
            Log.w("MorseCodeManager", "Unknown signal: $signalName")
        }
    }

    fun flashCustomMessage(message: String) {
        val morseCode = convertToMorse(message.uppercase())
        flashMorseSequence(morseCode)
    }

    fun convertToMorse(text: String): String {
        return text.map { morseCodeMap[it] ?: "?" }.joinToString(" ")
    }

    private fun flashMorseSequence(morse: String) {
        if (isFlashing) return

        isFlashing = true
        onStatusChange?.invoke(true)
        val sequence = parseMorseToSequence(morse)
        executeSequence(sequence.toMutableList())
    }

    private fun parseMorseToSequence(morse: String): List<MorseAction> {
        val sequence = mutableListOf<MorseAction>()
        morse.forEach { symbol ->
            when (symbol) {
                '.' -> {
                    sequence.add(MorseAction(true, DOT_DURATION))
                    sequence.add(MorseAction(false, SYMBOL_GAP))
                }
                '-' -> {
                    sequence.add(MorseAction(true, DASH_DURATION))
                    sequence.add(MorseAction(false, SYMBOL_GAP))
                }
                ' ' -> {
                    // Letter gap: replace the previous SYMBOL_GAP with longer LETTER_GAP
                    if (sequence.isNotEmpty() && sequence.last().duration == SYMBOL_GAP && !sequence.last().isFlashOn) {
                        sequence[sequence.size - 1] = MorseAction(false, LETTER_GAP)
                    } else {
                        sequence.add(MorseAction(false, LETTER_GAP))
                    }
                }
                '/' -> {
                    // Word gap: similar handling
                    if (sequence.isNotEmpty() && sequence.last().duration == SYMBOL_GAP && !sequence.last().isFlashOn) {
                        sequence[sequence.size - 1] = MorseAction(false, WORD_GAP)
                    } else {
                        sequence.add(MorseAction(false, WORD_GAP))
                    }
                }
            }
        }
        // Remove trailing OFF gap if present (optional)
        if (sequence.isNotEmpty() && !sequence.last().isFlashOn) {
            sequence.removeAt(sequence.size - 1)
        }
        return sequence
    }

    private fun executeSequence(sequence: MutableList<MorseAction>) {
        if (sequence.isEmpty() || !isFlashing) {
            stopFlashing()
            return
        }

        val action = sequence.removeAt(0)
        if (action.isFlashOn) {
            flashlightController.turnOn()
        } else {
            flashlightController.turnOff()
        }

        handler.postDelayed({
            executeSequence(sequence)
        }, action.duration)
    }

    fun stopFlashing() {
        isFlashing = false
        onStatusChange?.invoke(false)
        handler.removeCallbacksAndMessages(null)
        flashlightController.turnOff()
    }

    fun isFlashing(): Boolean = isFlashing
}