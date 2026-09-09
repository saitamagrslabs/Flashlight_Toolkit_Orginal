package com.saitamagrs.flashnow.utils

import io.mockk.MockKAnnotations
import io.mockk.impl.annotations.RelaxedMockK
import io.mockk.verify
import org.junit.Before
import org.junit.Test

class MorseCodeManagerTest {

    @RelaxedMockK
    private lateinit var flashlightController: FlashlightController

    private lateinit var morseCodeManager: MorseCodeManager

    @Before
    fun setup() {
        MockKAnnotations.init(this)
        morseCodeManager = MorseCodeManager(flashlightController)
    }

    @Test
    fun `convertToMorse should convert SOS correctly`() {
        // When
        val result = morseCodeManager.convertToMorse("SOS")

        // Then
        assert(result == "... --- ...")
    }

    @Test
    fun `convertToMorse should convert HELP correctly`() {
        // When
        val result = morseCodeManager.convertToMorse("HELP")

        // Then
        assert(result == ".... . .-.. .--.")
    }

    @Test
    fun `convertToMorse should convert numbers correctly`() {
        // When
        val result = morseCodeManager.convertToMorse("123")

        // Then
        assert(result == ".---- ..--- ...--")
    }

    @Test
    fun `convertToMorse should handle mixed alphanumeric`() {
        // When
        val result = morseCodeManager.convertToMorse("A1B2")

        // Then
        assert(result == ".- .---- -... ..---")
    }

    @Test
    fun `convertToMorse should handle spaces as word separators`() {
        // When
        val result = morseCodeManager.convertToMorse("HELLO WORLD")

        // Then
        assert(result.contains("/"))
    }

    @Test
    fun `convertToMorse should handle empty string`() {
        // When
        val result = morseCodeManager.convertToMorse("")

        // Then
        assert(result == "")
    }

    @Test
    fun `convertToMorse should handle unknown characters as spaces`() {
        // When
        val result = morseCodeManager.convertToMorse("@#$")

        // Then
        assert(result.trim() == "")
    }

    @Test
    fun `flashPredefinedSignal should flash SOS when called`() {
        // When
        morseCodeManager.flashPredefinedSignal("SOS")

        // Then - Flashlight should be turned on at least once
        verify(timeout = 1000) { flashlightController.turnOn() }
    }

    @Test
    fun `flashPredefinedSignal should flash HELP when called`() {
        // When
        morseCodeManager.flashPredefinedSignal("HELP")

        // Then - Flashlight should be turned on at least once
        verify(timeout = 1000) { flashlightController.turnOn() }
    }

    @Test
    fun `flashPredefinedSignal should not flash unknown signal`() {
        // When
        morseCodeManager.flashPredefinedSignal("UNKNOWN")

        // Then - Flashlight should not be called immediately
        verify(exactly = 0, timeout = 500) { flashlightController.turnOn() }
    }

    @Test
    fun `flashCustomMessage should convert and flash message`() {
        // When
        morseCodeManager.flashCustomMessage("HI")

        // Then
        verify(timeout = 1000) { flashlightController.turnOn() }
    }

    @Test
    fun `stopFlashing should stop the current sequence`() {
        // Given - Start flashing
        morseCodeManager.flashCustomMessage("SOS")

        // When
        morseCodeManager.stopFlashing()

        // Then
        verify { flashlightController.turnOff() }
        assert(!morseCodeManager.isFlashing())
    }

    @Test
    fun `isFlashing should return false initially`() {
        // Then
        assert(!morseCodeManager.isFlashing())
    }

    @Test
    fun `isFlashing should return true when flashing`() {
        // Given
        morseCodeManager.flashPredefinedSignal("SOS")

        // Then
        assert(morseCodeManager.isFlashing())

        // Clean up
        morseCodeManager.stopFlashing()
    }

    @Test
    fun `predefinedSignals should contain all expected signals`() {
        // Then
        assert(morseCodeManager.predefinedSignals.containsKey("SOS"))
        assert(morseCodeManager.predefinedSignals.containsKey("HELP"))
        assert(morseCodeManager.predefinedSignals.containsKey("OK"))
        assert(morseCodeManager.predefinedSignals.containsKey("YES"))
        assert(morseCodeManager.predefinedSignals.containsKey("NO"))
        assert(morseCodeManager.predefinedSignals.containsKey("DANGER"))
    }

    @Test
    fun `predefinedSignals SOS should have correct morse code`() {
        // Then
        assert(morseCodeManager.predefinedSignals["SOS"] == "... --- ...")
    }

    @Test
    fun `predefinedSignals HELP should have correct morse code`() {
        // Then
        assert(morseCodeManager.predefinedSignals["HELP"] == ".... . .-.. .--.")
    }

    @Test
    fun `convertToMorse should handle lowercase input by converting to uppercase`() {
        // When
        val result = morseCodeManager.convertToMorse("sos")

        // Then
        assert(result == "... --- ...")
    }

    @Test
    fun `convertToMorse should handle complex sentence`() {
        // When
        val result = morseCodeManager.convertToMorse("SAVE ME")

        // Then
        val expectedS = "..."
        val expectedA = " .-"
        val expectedV = " ...-"
        val expectedE = " ."
        val expectedSeparator = " / "
        val expectedM = "--"
        val expectedE2 = " ."

        assert(result == "$expectedS$expectedA$expectedV$expectedE$expectedSeparator$expectedM$expectedE2")
    }
}
