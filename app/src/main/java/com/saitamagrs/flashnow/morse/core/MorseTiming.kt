package com.saitamagrs.flashnow.morse.core

/**
 * Defines timing constants and pulse/gap classification rules for Morse Code optical communication.
 * Base unit T = 150ms.
 * Designed with robust tolerance windows for real-world camera frame rates (20-30 FPS)
 * and optical transmission jitter at distances up to 3 meters.
 */
object MorseTiming {
    const val UNIT_T_MS: Long = 150L

    // Nominal pulse durations
    const val DOT_DURATION_MS: Long = UNIT_T_MS // 150ms
    const val DASH_DURATION_MS: Long = 3 * UNIT_T_MS // 450ms

    // Nominal gap durations
    const val SYMBOL_GAP_MS: Long = UNIT_T_MS // 150ms
    const val LETTER_GAP_MS: Long = 3 * UNIT_T_MS // 450ms
    const val WORD_GAP_MS: Long = 7 * UNIT_T_MS // 1050ms
    const val END_GAP_MS: Long = 10 * UNIT_T_MS // 1500ms

    // Pulse classification boundaries (ms) with wide tolerance windows:
    // DOT window: [45ms .. 275ms) (centered around nominal 150ms, accounts for frame quantization)
    // DASH window: [275ms .. 850ms] (centered around nominal 450ms)
    const val MIN_PULSE_DURATION_MS: Long = 45L
    const val PULSE_DOT_DASH_THRESHOLD_MS: Long = 275L
    const val MAX_PULSE_DURATION_MS: Long = 850L

    // Gap classification boundaries (ms) with robust tolerance windows:
    // SYMBOL GAP: [0ms .. 280ms) - protects intra-letter symbols (e.g. '.-' and '...') from premature splitting
    // LETTER GAP: [280ms .. 750ms) - responsively detects letter completion
    // WORD GAP: [750ms .. 1300ms) - separates words with space
    // END GAP: >= 1300ms - marks frame/message timeout
    const val GAP_SYMBOL_LETTER_THRESHOLD_MS: Long = 280L
    const val GAP_LETTER_WORD_THRESHOLD_MS: Long = 750L
    const val GAP_WORD_END_THRESHOLD_MS: Long = 1300L

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
     * Classifies ON duration into DOT or DASH using robust tolerance windows.
     */
    fun classifyPulse(durationMs: Long): PulseType {
        if (durationMs < MIN_PULSE_DURATION_MS || durationMs > MAX_PULSE_DURATION_MS) {
            return PulseType.UNKNOWN
        }
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
