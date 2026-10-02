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
}
