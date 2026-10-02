package com.saitamagrs.flashnow.lightbulb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BulbDefinitionTest {

    @Test
    fun `getAllBulbs returns all 7 real-world bulb types`() {
        val bulbs = BulbDefinition.getAllBulbs()
        assertEquals(7, bulbs.size)
        assertEquals(BulbType.STANDARD, bulbs[0].type)
        assertEquals(BulbType.TUBE_LIGHT, bulbs[1].type)
        assertEquals(BulbType.SPOTLIGHT, bulbs[2].type)
        assertEquals(BulbType.TORCH, bulbs[3].type)
        assertEquals(BulbType.EDISON, bulbs[4].type)
        assertEquals(BulbType.SMART, bulbs[5].type)
        assertEquals(BulbType.CANDLE, bulbs[6].type)
    }

    @Test
    fun `every bulb type has valid definition and non-empty metadata`() {
        for (type in BulbType.values()) {
            val def = BulbDefinition.fromType(type)
            assertNotNull(def)
            assertTrue(def.displayName.isNotBlank())
            assertTrue(def.subtitle.isNotBlank())
            assertTrue(def.description.isNotBlank())
            assertTrue(def.typicalUses.isNotBlank())
            assertTrue(def.lightBehavior.isNotBlank())
            assertNotNull(def.illuminationProfile)
            assertTrue(def.illustrationResId != 0)
        }
    }

    @Test
    fun `illumination profile pattern types map correctly for all bulb types`() {
        assertEquals(IlluminationType.BROAD, BulbDefinition.fromType(BulbType.STANDARD).illuminationProfile.patternType)
        assertEquals(IlluminationType.LINEAR, BulbDefinition.fromType(BulbType.TUBE_LIGHT).illuminationProfile.patternType)
        assertEquals(IlluminationType.DIRECTIONAL, BulbDefinition.fromType(BulbType.SPOTLIGHT).illuminationProfile.patternType)
        assertEquals(IlluminationType.CONCENTRATED, BulbDefinition.fromType(BulbType.TORCH).illuminationProfile.patternType)
        assertEquals(IlluminationType.DECORATIVE, BulbDefinition.fromType(BulbType.EDISON).illuminationProfile.patternType)
        assertEquals(IlluminationType.BROAD, BulbDefinition.fromType(BulbType.SMART).illuminationProfile.patternType)
        assertEquals(IlluminationType.DECORATIVE, BulbDefinition.fromType(BulbType.CANDLE).illuminationProfile.patternType)
    }

    @Test
    fun `LightBulbState default state is Standard bulb, lit, 100 percent brightness`() {
        val state = LightBulbState()
        assertEquals(BulbType.STANDARD, state.selectedBulb.type)
        assertTrue(state.isLit)
        assertEquals(100, state.brightnessPercent)
    }

    @Test
    fun `copyWithBulb updates selected bulb definition`() {
        val state = LightBulbState().copyWithBulb(BulbType.TUBE_LIGHT)
        assertEquals(BulbType.TUBE_LIGHT, state.selectedBulb.type)
        assertEquals("Tube Light", state.selectedBulb.displayName)
    }

    @Test
    fun `getNextBulb cycles forward through all 7 light source types`() {
        var state = LightBulbState()
        assertEquals(BulbType.STANDARD, state.selectedBulb.type)

        state = state.getNextBulb()
        assertEquals(BulbType.TUBE_LIGHT, state.selectedBulb.type)

        state = state.getNextBulb()
        assertEquals(BulbType.SPOTLIGHT, state.selectedBulb.type)

        state = state.getNextBulb()
        assertEquals(BulbType.TORCH, state.selectedBulb.type)

        state = state.getNextBulb()
        assertEquals(BulbType.EDISON, state.selectedBulb.type)

        state = state.getNextBulb()
        assertEquals(BulbType.SMART, state.selectedBulb.type)

        state = state.getNextBulb()
        assertEquals(BulbType.CANDLE, state.selectedBulb.type)

        state = state.getNextBulb()
        assertEquals(BulbType.STANDARD, state.selectedBulb.type) // Wraps around to start
    }

    @Test
    fun `getPreviousBulb cycles backward through all 7 light source types`() {
        var state = LightBulbState()
        assertEquals(BulbType.STANDARD, state.selectedBulb.type)

        state = state.getPreviousBulb()
        assertEquals(BulbType.CANDLE, state.selectedBulb.type) // Wraps around to end

        state = state.getPreviousBulb()
        assertEquals(BulbType.SMART, state.selectedBulb.type)
    }

    @Test
    fun `copyWithPower updates power state immutably`() {
        val offState = LightBulbState().copyWithPower(false)
        assertFalse(offState.isLit)

        val onState = offState.copyWithPower(true)
        assertTrue(onState.isLit)
    }

    @Test
    fun `copyWithBrightness coerces brightness immutably between 0 and 100`() {
        val validState = LightBulbState().copyWithBrightness(65)
        assertEquals(65, validState.brightnessPercent)

        val lowClamped = LightBulbState().copyWithBrightness(-20)
        assertEquals(0, lowClamped.brightnessPercent)

        val highClamped = LightBulbState().copyWithBrightness(200)
        assertEquals(100, highClamped.brightnessPercent)
    }
}
