package com.saitamagrs.flashnow.lightbulb

import android.graphics.Color
import com.saitamagrs.flashnow.R

/**
 * Supported real-world light source / bulb types.
 */
enum class BulbType {
    STANDARD,
    TUBE_LIGHT,
    SPOTLIGHT,
    TORCH,
    EDISON,
    SMART,
    CANDLE
}

/**
 * Characterization of light emission pattern.
 */
enum class IlluminationType {
    BROAD,
    LINEAR,
    DIRECTIONAL,
    CONCENTRATED,
    DECORATIVE
}

/**
 * Illumination profile defining physical light distribution parameters.
 */
data class IlluminationProfile(
    val patternType: IlluminationType,
    val spreadAngleDegrees: Int,
    val glowRadiusScale: Float,
    val beamFocusScale: Float
)

/**
 * Complete definition of a real-world bulb light source including visual resource mappings
 * and educational metadata.
 */
data class BulbDefinition(
    val type: BulbType,
    val displayName: String,
    val subtitle: String,
    val description: String,
    val typicalUses: String,
    val lightBehavior: String,
    val illustrationResId: Int,
    val glowColor: Int,
    val filamentColor: Int,
    val illuminationProfile: IlluminationProfile
) {
    companion object {
        fun getAllBulbs(): List<BulbDefinition> = listOf(
            BulbDefinition(
                type = BulbType.STANDARD,
                displayName = "Standard Bulb",
                subtitle = "A19 / A60 Household",
                description = "Common household bulb with a rounded pear shape providing broad general illumination.",
                typicalUses = "Home lighting, table lamps, ceiling fixtures, and hallway lighting.",
                lightBehavior = "Produces broad, evenly distributed 360° ambient light.",
                illustrationResId = R.drawable.img_bulb_standard,
                glowColor = Color.parseColor("#FFFFD166"), // Warm Soft Yellow
                filamentColor = Color.parseColor("#FFFFE082"),
                illuminationProfile = IlluminationProfile(
                    patternType = IlluminationType.BROAD,
                    spreadAngleDegrees = 360,
                    glowRadiusScale = 1.0f,
                    beamFocusScale = 0.2f
                )
            ),
            BulbDefinition(
                type = BulbType.TUBE_LIGHT,
                displayName = "Tube Light",
                subtitle = "Linear T8 / LED Tube",
                description = "Long cylindrical linear light source providing a wide wash of light over large areas.",
                typicalUses = "Offices, garages, kitchens, classrooms, and workshops.",
                lightBehavior = "Emits wide linear illumination horizontally along the tube.",
                illustrationResId = R.drawable.img_bulb_tube,
                glowColor = Color.parseColor("#FFE0F7FA"), // Bright Daylight White
                filamentColor = Color.parseColor("#FFFFFFFF"),
                illuminationProfile = IlluminationProfile(
                    patternType = IlluminationType.LINEAR,
                    spreadAngleDegrees = 180,
                    glowRadiusScale = 1.2f,
                    beamFocusScale = 0.4f
                )
            ),
            BulbDefinition(
                type = BulbType.SPOTLIGHT,
                displayName = "Spotlight",
                subtitle = "PAR38 / MR16 Reflector",
                description = "Compact directional bulb with a parabolic reflector to focus light into a cone beam.",
                typicalUses = "Recessed ceiling lights, track lighting, art displays, and accent lighting.",
                lightBehavior = "Projects a concentrated directional beam of light.",
                illustrationResId = R.drawable.img_bulb_spotlight,
                glowColor = Color.parseColor("#FF00D4FF"), // Cool Daylight Blue/Cyan
                filamentColor = Color.parseColor("#E0F7FA"),
                illuminationProfile = IlluminationProfile(
                    patternType = IlluminationType.DIRECTIONAL,
                    spreadAngleDegrees = 60,
                    glowRadiusScale = 0.8f,
                    beamFocusScale = 0.8f
                )
            ),
            BulbDefinition(
                type = BulbType.TORCH,
                displayName = "Torch Bulb",
                subtitle = "Flashlight Emitter",
                description = "Miniature incandescent or LED emitter designed for portable handheld flashlights.",
                typicalUses = "Handheld flashlights, emergency torches, and portable lamps.",
                lightBehavior = "Produces a tight, high-intensity focal spot.",
                illustrationResId = R.drawable.img_bulb_torch,
                glowColor = Color.parseColor("#FFFFFFFF"), // Crisp Intense White
                filamentColor = Color.parseColor("#FF80D8FF"),
                illuminationProfile = IlluminationProfile(
                    patternType = IlluminationType.CONCENTRATED,
                    spreadAngleDegrees = 30,
                    glowRadiusScale = 0.6f,
                    beamFocusScale = 1.0f
                )
            ),
            BulbDefinition(
                type = BulbType.EDISON,
                displayName = "Edison Bulb",
                subtitle = "ST64 Vintage Decorative",
                description = "Teardrop-shaped vintage bulb with intricate exposed filaments and warm amber glass.",
                typicalUses = "Cafes, restaurants, pendant fixtures, and decorative interiors.",
                lightBehavior = "Emits warm, decorative filament illumination with ambient glow.",
                illustrationResId = R.drawable.img_bulb_edison,
                glowColor = Color.parseColor("#FFFF9800"), // Warm Amber
                filamentColor = Color.parseColor("#FFFFB74D"),
                illuminationProfile = IlluminationProfile(
                    patternType = IlluminationType.DECORATIVE,
                    spreadAngleDegrees = 360,
                    glowRadiusScale = 0.9f,
                    beamFocusScale = 0.3f
                )
            ),
            BulbDefinition(
                type = BulbType.SMART,
                displayName = "Smart Bulb",
                subtitle = "Connected LED Diffuser",
                description = "Modern smart bulb with a white frosted diffuser dome and integrated electronic controller.",
                typicalUses = "Smart homes, automated ambient lighting, and mood lighting.",
                lightBehavior = "Produces soft diffused ambient light across broad angles.",
                illustrationResId = R.drawable.img_bulb_smart,
                glowColor = Color.parseColor("#FFFFF8E1"), // Warm Soft Diffused White
                filamentColor = Color.parseColor("#FFFFFFFF"),
                illuminationProfile = IlluminationProfile(
                    patternType = IlluminationType.BROAD,
                    spreadAngleDegrees = 270,
                    glowRadiusScale = 1.1f,
                    beamFocusScale = 0.3f
                )
            ),
            BulbDefinition(
                type = BulbType.CANDLE,
                displayName = "Candle Bulb",
                subtitle = "C7 / C9 Flame Tip",
                description = "Flame-shaped torpedo bulb designed to imitate candle flame aesthetics.",
                typicalUses = "Chandeliers, wall sconces, decorative lamps, and night lights.",
                lightBehavior = "Provides soft, warm decorative candle-like light.",
                illustrationResId = R.drawable.img_bulb_candle,
                glowColor = Color.parseColor("#FFFF5722"), // Warm Flame Orange Red
                filamentColor = Color.parseColor("#FF8A65"),
                illuminationProfile = IlluminationProfile(
                    patternType = IlluminationType.DECORATIVE,
                    spreadAngleDegrees = 300,
                    glowRadiusScale = 0.85f,
                    beamFocusScale = 0.3f
                )
            )
        )

        fun fromType(type: BulbType): BulbDefinition {
            return getAllBulbs().firstOrNull { it.type == type } ?: getAllBulbs().first()
        }
    }
}

/**
 * State representation for the Virtual Light Bulb feature.
 */
data class LightBulbState(
    val selectedBulb: BulbDefinition = BulbDefinition.fromType(BulbType.STANDARD),
    val isLit: Boolean = true,
    val brightnessPercent: Int = 100
) {
    fun copyWithBulb(type: BulbType): LightBulbState {
        return copy(selectedBulb = BulbDefinition.fromType(type))
    }

    fun getNextBulb(): LightBulbState {
        val bulbs = BulbDefinition.getAllBulbs()
        val currentIndex = bulbs.indexOfFirst { it.type == selectedBulb.type }
        val nextIndex = if (currentIndex == -1) 0 else (currentIndex + 1) % bulbs.size
        return copy(selectedBulb = bulbs[nextIndex])
    }

    fun getPreviousBulb(): LightBulbState {
        val bulbs = BulbDefinition.getAllBulbs()
        val currentIndex = bulbs.indexOfFirst { it.type == selectedBulb.type }
        val prevIndex = if (currentIndex <= 0) bulbs.size - 1 else currentIndex - 1
        return copy(selectedBulb = bulbs[prevIndex])
    }

    fun copyWithPower(lit: Boolean): LightBulbState {
        return copy(isLit = lit)
    }

    fun copyWithBrightness(percent: Int): LightBulbState {
        return copy(brightnessPercent = percent.coerceIn(0, 100))
    }
}
