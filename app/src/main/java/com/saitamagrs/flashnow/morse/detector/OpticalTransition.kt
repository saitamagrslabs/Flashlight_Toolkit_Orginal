package com.saitamagrs.flashnow.morse.detector

/**
 * Represents a confirmed light-state transition event with monotonic timing data.
 *
 * @property previousState The state before the transition (ON or OFF).
 * @property newState The confirmed state after the transition (ON or OFF).
 * @property transitionTimestampMs The monotonic timestamp when the transition was confirmed (in milliseconds).
 * @property previousStateDurationMs The duration the previous state lasted before this transition (in milliseconds).
 */
data class OpticalTransition(
    val previousState: LightState,
    val newState: LightState,
    val transitionTimestampMs: Long,
    val previousStateDurationMs: Long
)
