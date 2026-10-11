package com.saitamagrs.flashnow.morse

import com.saitamagrs.flashnow.morse.core.MorseCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MorseCodecTest {

    @Test
    fun `encode text to morse pattern correctly`() {
        assertEquals("... --- ...", MorseCodec.encode("SOS"))
        assertEquals(".... . .-.. .--.", MorseCodec.encode("HELP"))
        assertEquals(".---- ..--- ...--", MorseCodec.encode("123"))
        assertEquals(".- / -...", MorseCodec.encode("A B"))
    }

    @Test
    fun `decode morse pattern to text correctly`() {
        assertEquals("SOS", MorseCodec.decode("... --- ..."))
        assertEquals("HELP", MorseCodec.decode(".... . .-.. .--."))
        assertEquals("123", MorseCodec.decode(".---- ..--- ...--"))
        assertEquals("A B", MorseCodec.decode(".- / -..."))
    }

    @Test
    fun `encode single char`() {
        assertEquals("...", MorseCodec.encodeChar('S'))
        assertEquals("---", MorseCodec.encodeChar('O'))
        assertNull(MorseCodec.encodeChar('@'))
    }

    @Test
    fun `decode single letter`() {
        assertEquals('S', MorseCodec.decodeLetter("..."))
        assertEquals('O', MorseCodec.decodeLetter("---"))
        assertNull(MorseCodec.decodeLetter("......"))
    }

    // ================= Letter Y Regression Tests =================

    @Test
    fun `encode uppercase and lowercase Y to expected Morse pattern`() {
        assertEquals("-.--", MorseCodec.encodeChar('Y'))
        assertEquals("-.--", MorseCodec.encodeChar('y'))
    }

    @Test
    fun `decode pattern to letter Y`() {
        assertEquals('Y', MorseCodec.decodeLetter("-.--"))
    }

    @Test
    fun `encode word containing Y produces standard Morse sequence`() {
        assertEquals("-.--", MorseCodec.encode("Y"))
        // 'Y' (-.--), 'E' (.), 'S' (...)
        assertEquals("-.-- . ...", MorseCodec.encode("YES"))
        assertEquals("YES", MorseCodec.decode("-.-- . ..."))
    }

    @Test
    fun `verify representative letters and digits mappings remain unchanged`() {
        // Representative letters
        assertEquals(".-", MorseCodec.encodeChar('A'))
        assertEquals("-...", MorseCodec.encodeChar('B'))
        assertEquals("-.-.", MorseCodec.encodeChar('C'))
        assertEquals(".", MorseCodec.encodeChar('E'))
        assertEquals("....", MorseCodec.encodeChar('H'))
        assertEquals("...", MorseCodec.encodeChar('S'))
        assertEquals("-", MorseCodec.encodeChar('T'))
        assertEquals("--..", MorseCodec.encodeChar('Z'))

        // Representative digits
        assertEquals("-----", MorseCodec.encodeChar('0'))
        assertEquals(".----", MorseCodec.encodeChar('1'))
        assertEquals(".....", MorseCodec.encodeChar('5'))
        assertEquals("----.", MorseCodec.encodeChar('9'))

        // Representative decoding
        assertEquals('A', MorseCodec.decodeLetter(".-"))
        assertEquals('Z', MorseCodec.decodeLetter("--.."))
        assertEquals('0', MorseCodec.decodeLetter("-----"))
        assertEquals('9', MorseCodec.decodeLetter("----."))
    }
}
