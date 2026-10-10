package com.saitamagrs.flashnow.morse.detector

/**
 * Optical state of the detected light beam.
 */
enum class LightState {
    /**
     * Optical silence / ambient background (flashlight OFF).
     */
    OFF,

    /**
     * Active optical illumination (flashlight ON).
     */
    ON
}
