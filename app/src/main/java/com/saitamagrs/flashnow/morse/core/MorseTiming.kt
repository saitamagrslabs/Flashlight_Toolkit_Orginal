package com.saitamagrs.flashnow.morse.core

/**
 * Defines timing constants and pulse/gap classification rules for Morse Code optical communication.
 * Base unit T = 150ms.
 */
object MorseTiming {
    const val UNIT_T_MS: Long = 150L

    // Pulse durations
    const val DOT_DURATION_MS: Long = UNIT_T_MS // 150ms
    const val DASH_DURATION_MS: Long = 3 * UNIT_T_MS // 450ms

    // Gap durations
    const val SYMBOL_GAP_MS: Long = UNIT_T_MS // 150ms
    const val LETTER_GAP_MS: Long = 3 * UNIT_T_MS // 450ms
    const val WORD_GAP_MS: Long = 7 * UNIT_T_MS // 1050ms
    const val END_GAP_MS: Long = 10 * UNIT_T_MS // 1500ms

    // Classification boundaries (ms)
    private const val PULSE_DOT_DASH_THRESHOLD_MS: Long = 300L

    private const val GAP_SYMBOL_LETTER_THRESHOLD_MS: Long = 300L
    private const val GAP_LETTER_WORD_THRESHOLD_MS: Long = 750L
    private const val GAP_WORD_END_THRESHOLD_MS: Long = 1275L

    enum class PulseType {
        DOT,
        DASH,
        UNKNOWN
    }

    enum class GapType {
        SYMBOL_GAP,
        LETTER_GAP,
        WORD_GAP,
        END_GAP
    }

    /**
     * Classifies ON duration into DOT or DASH.
     */
    fun classifyPulse(durationMs: Long): PulseType {
        if (durationMs <= 0) return PulseType.UNKNOWN
        return if (durationMs < PULSE_DOT_DASH_THRESHOLD_MS) {
            PulseType.DOT
        } else {
            PulseType.DASH
        }
    }

    /**
     * Classifies OFF duration into SYMBOL_GAP, LETTER_GAP, WORD_GAP, or END_GAP.
     */
    fun classifyGap(durationMs: Long): GapType {
        return when {
            durationMs < GAP_SYMBOL_LETTER_THRESHOLD_MS -> GapType.SYMBOL_GAP
            durationMs < GAP_LETTER_WORD_THRESHOLD_MS -> GapType.LETTER_GAP
            durationMs < GAP_WORD_END_THRESHOLD_MS -> GapType.WORD_GAP
            else -> GapType.END_GAP
        }
    }
}
