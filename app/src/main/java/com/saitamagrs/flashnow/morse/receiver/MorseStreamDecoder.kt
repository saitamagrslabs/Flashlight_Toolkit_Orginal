package com.saitamagrs.flashnow.morse.receiver

import com.saitamagrs.flashnow.morse.core.MorseCodec
import com.saitamagrs.flashnow.morse.core.MorseProtocol
import com.saitamagrs.flashnow.morse.core.MorseTiming

/**
 * Pure, deterministic optical Morse code streaming decoder.
 *
 * Implements FlashNow's T = 150ms timing contract with wide tolerance windows:
 * - Pulse Dot:  [45ms .. 275ms)
 * - Pulse Dash: [275ms .. 850ms]
 * - Symbol Gap: [0ms .. 280ms)
 * - Letter Gap: [280ms .. 750ms)
 * - Word Gap:   [750ms .. 1300ms)
 * - End Gap:    >= 1300ms
 *
 * Handles protocol preamble synchronization ('...') without emitting phantom 'S',
 * and accurately preserves real leading 'S' in words like "SOS", "SAFE", "SHELL".
 */
class MorseStreamDecoder(
    private val onSymbolsUpdated: (String) -> Unit = {},
    private val onMessageUpdated: (String) -> Unit = {},
    private val onPreambleDetected: (Boolean) -> Unit = {},
    private val onSessionCompleted: () -> Unit = {}
) {
    companion object {
        const val SESSION_END_TIMEOUT_MS = 1400L
    }

    private val preambleCandidateBuffer = StringBuilder()
    private val currentSymbolBuffer = StringBuilder()
    private val decodedMessageBuilder = StringBuilder()

    var preambleDetected: Boolean = false
        private set

    val decodedText: String get() = decodedMessageBuilder.toString()
    val currentSymbols: String get() = currentSymbolBuffer.toString()

    fun reset() {
        preambleCandidateBuffer.clear()
        currentSymbolBuffer.clear()
        decodedMessageBuilder.clear()
        preambleDetected = false
        onSymbolsUpdated("")
        onMessageUpdated("")
        onPreambleDetected(false)
    }

    fun handlePulse(durationMs: Long) {
        if (durationMs < MorseTiming.MIN_PULSE_DURATION_MS) return
        val pulseType = MorseTiming.classifyPulse(durationMs)
        val symbolChar = when (pulseType) {
            MorseTiming.PulseType.DOT -> '.'
            MorseTiming.PulseType.DASH -> '-'
            MorseTiming.PulseType.UNKNOWN -> return
        }

        if (!preambleDetected) {
            if (symbolChar == '.' && preambleCandidateBuffer.length < 3) {
                preambleCandidateBuffer.append(symbolChar)
            } else {
                // Non-preamble pattern (contains dash or exceeds 3 dots):
                // Direct message without preamble. Promote candidate buffer to message!
                preambleDetected = true
                onPreambleDetected(true)
                currentSymbolBuffer.append(preambleCandidateBuffer)
                currentSymbolBuffer.append(symbolChar)
                preambleCandidateBuffer.clear()
                onSymbolsUpdated(currentSymbolBuffer.toString())
            }
        } else {
            currentSymbolBuffer.append(symbolChar)
            onSymbolsUpdated(currentSymbolBuffer.toString())
        }
    }

    fun handleGap(durationMs: Long) {
        if (durationMs < MorseTiming.MIN_PULSE_DURATION_MS) return
        val gapType = MorseTiming.classifyGap(durationMs)

        when (gapType) {
            MorseTiming.GapType.SYMBOL_GAP -> {
                // Intra-character gap: continuation of the same letter
            }
            MorseTiming.GapType.LETTER_GAP -> {
                finalizeLetter()
            }
            MorseTiming.GapType.WORD_GAP -> {
                finalizeLetter()
                if (decodedMessageBuilder.isNotEmpty() && !decodedMessageBuilder.endsWith(" ")) {
                    decodedMessageBuilder.append(" ")
                    onMessageUpdated(decodedMessageBuilder.toString())
                }
            }
            MorseTiming.GapType.END_GAP -> {
                finalizeLetter()
                onSessionCompleted()
            }
        }
    }

    fun handleIdleGap(idleGapMs: Long) {
        // Responsively finalize letter as soon as letter gap threshold is crossed
        if ((currentSymbolBuffer.isNotEmpty() || (!preambleDetected && preambleCandidateBuffer.isNotEmpty()))
            && idleGapMs >= MorseTiming.GAP_SYMBOL_LETTER_THRESHOLD_MS) {
            finalizeLetter()
        }

        // Responsively complete session on end gap timeout
        if (idleGapMs >= SESSION_END_TIMEOUT_MS) {
            onSessionCompleted()
        }
    }

    fun finalizeLetter() {
        if (!preambleDetected) {
            val candidate = preambleCandidateBuffer.toString()
            if (candidate == MorseProtocol.PREAMBLE_MORSE_PATTERN) {
                // Preamble detected and synchronized! Discard from message text.
                preambleDetected = true
                preambleCandidateBuffer.clear()
                onPreambleDetected(true)
                return
            } else if (candidate.isNotEmpty()) {
                preambleDetected = true
                onPreambleDetected(true)
                currentSymbolBuffer.append(candidate)
                preambleCandidateBuffer.clear()
                onSymbolsUpdated(currentSymbolBuffer.toString())
            } else {
                return
            }
        }

        val pattern = currentSymbolBuffer.toString()
        if (pattern.isEmpty()) return

        val char = MorseCodec.decodeLetter(pattern)
        if (char != null) {
            decodedMessageBuilder.append(char)
            onMessageUpdated(decodedMessageBuilder.toString())
        }

        currentSymbolBuffer.clear()
        onSymbolsUpdated("")
    }
}
