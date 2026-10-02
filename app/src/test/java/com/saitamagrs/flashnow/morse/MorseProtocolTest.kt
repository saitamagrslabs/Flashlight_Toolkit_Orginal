package com.saitamagrs.flashnow.morse

import com.saitamagrs.flashnow.morse.core.MorseProtocol
import com.saitamagrs.flashnow.morse.core.MorseTiming
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MorseProtocolTest {

    @Test
    fun `PREAMBLE_PULSES has correct sequence`() {
        val preamble = MorseProtocol.PREAMBLE_PULSES
        assertEquals(6, preamble.size)
        // ON 1T, OFF 1T, ON 1T, OFF 1T, ON 1T, OFF 3T
        assertTrue(preamble[0].isOn)
        assertEquals(MorseTiming.DOT_DURATION_MS, preamble[0].durationMs)
        assertFalse(preamble[1].isOn)
        assertEquals(MorseTiming.SYMBOL_GAP_MS, preamble[1].durationMs)
        assertTrue(preamble[2].isOn)
        assertEquals(MorseTiming.DOT_DURATION_MS, preamble[2].durationMs)
        assertFalse(preamble[3].isOn)
        assertEquals(MorseTiming.SYMBOL_GAP_MS, preamble[3].durationMs)
        assertTrue(preamble[4].isOn)
        assertEquals(MorseTiming.DOT_DURATION_MS, preamble[4].durationMs)
        assertFalse(preamble[5].isOn)
        assertEquals(MorseTiming.LETTER_GAP_MS, preamble[5].durationMs)
    }

    @Test
    fun `buildFrame for SOS starts with preamble and ends with END_GAP`() {
        val frame = MorseProtocol.buildFrame("SOS")
        assertTrue(frame.isNotEmpty())

        // First 6 pulses are preamble
        for (i in 0 until 6) {
            assertEquals(MorseProtocol.PREAMBLE_PULSES[i], frame[i])
        }

        // Last pulse is END_GAP (OFF 10T)
        val lastPulse = frame.last()
        assertFalse(lastPulse.isOn)
        assertEquals(MorseTiming.END_GAP_MS, lastPulse.durationMs)
    }
}
