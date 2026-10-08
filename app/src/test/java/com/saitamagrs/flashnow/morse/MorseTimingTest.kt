package com.saitamagrs.flashnow.morse

import com.saitamagrs.flashnow.morse.core.MorseTiming
import org.junit.Assert.assertEquals
import org.junit.Test

class MorseTimingTest {

    @Test
    fun `classifyPulse classifies duration into DOT and DASH`() {
        assertEquals(MorseTiming.PulseType.DOT, MorseTiming.classifyPulse(100L))
        assertEquals(MorseTiming.PulseType.DOT, MorseTiming.classifyPulse(150L))
        assertEquals(MorseTiming.PulseType.DOT, MorseTiming.classifyPulse(274L))
        assertEquals(MorseTiming.PulseType.DASH, MorseTiming.classifyPulse(275L))
        assertEquals(MorseTiming.PulseType.DASH, MorseTiming.classifyPulse(450L))
        assertEquals(MorseTiming.PulseType.DASH, MorseTiming.classifyPulse(600L))
        assertEquals(MorseTiming.PulseType.UNKNOWN, MorseTiming.classifyPulse(0L))
    }

    @Test
    fun `classifyGap classifies duration into SYMBOL, LETTER, WORD, and END gaps`() {
        assertEquals(MorseTiming.GapType.SYMBOL_GAP, MorseTiming.classifyGap(150L))
        assertEquals(MorseTiming.GapType.SYMBOL_GAP, MorseTiming.classifyGap(250L))
        assertEquals(MorseTiming.GapType.LETTER_GAP, MorseTiming.classifyGap(300L))
        assertEquals(MorseTiming.GapType.LETTER_GAP, MorseTiming.classifyGap(450L))
        assertEquals(MorseTiming.GapType.LETTER_GAP, MorseTiming.classifyGap(700L))
        assertEquals(MorseTiming.GapType.WORD_GAP, MorseTiming.classifyGap(750L))
        assertEquals(MorseTiming.GapType.WORD_GAP, MorseTiming.classifyGap(1050L))
        assertEquals(MorseTiming.GapType.WORD_GAP, MorseTiming.classifyGap(1200L))
        assertEquals(MorseTiming.GapType.END_GAP, MorseTiming.classifyGap(1300L))
        assertEquals(MorseTiming.GapType.END_GAP, MorseTiming.classifyGap(1500L))
    }
}
