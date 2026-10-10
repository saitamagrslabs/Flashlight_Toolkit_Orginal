package com.saitamagrs.flashnow.morse.receiver

import com.google.common.truth.Truth.assertThat
import com.saitamagrs.flashnow.morse.decoder.MorseDecoder
import com.saitamagrs.flashnow.morse.detector.LightState
import com.saitamagrs.flashnow.morse.detector.OpticalDiagnostics
import com.saitamagrs.flashnow.morse.detector.OpticalSignalDetector
import com.saitamagrs.flashnow.morse.detector.OpticalTransition
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MorseReceiverControllerTest {

    private lateinit var detector: OpticalSignalDetector
    private lateinit var decoder: MorseDecoder
    private lateinit var controller: MorseReceiverController

    private var currentTime: Long = 1000L

    @Before
    fun setUp() {
        detector = OpticalSignalDetector()
        decoder = MorseDecoder()
        currentTime = 1000L
        controller = MorseReceiverController(
            detector = detector,
            decoder = decoder,
            timeProvider = { currentTime }
        )
    }

    // 1. A confirmed ON/OFF pair produces one pulse with the expected elapsed duration.
    @Test
    fun confirmedOnOffPair_producesOnePulseWithExpectedDuration() {
        controller.startSession()

        // Confirmed OFF -> ON transition at t=1000
        val onTransition = OpticalTransition(
            previousState = LightState.OFF,
            newState = LightState.ON,
            transitionTimestampMs = 1000L,
            previousStateDurationMs = 500L
        )
        controller.onOpticalTransition(onTransition)

        // Confirmed ON -> OFF transition at t=1150 with 150ms duration (DOT)
        val offTransition = OpticalTransition(
            previousState = LightState.ON,
            newState = LightState.OFF,
            transitionTimestampMs = 1150L,
            previousStateDurationMs = 150L
        )
        controller.onOpticalTransition(offTransition)

        // One dot symbol should be in the current symbol buffer
        assertThat(controller.currentSymbols.value).isEqualTo(".")
    }

    // 2. A dot-like pulse is passed to the decoder correctly.
    @Test
    fun dotLikePulse_passedToDecoderCorrectly() {
        controller.startSession()

        // 150ms pulse
        controller.onOpticalTransition(
            OpticalTransition(LightState.OFF, LightState.ON, 1000L, 0L)
        )
        controller.onOpticalTransition(
            OpticalTransition(LightState.ON, LightState.OFF, 1150L, 150L)
        )

        assertThat(controller.currentSymbols.value).isEqualTo(".")
    }

    // 3. A dash-like pulse is passed to the decoder correctly.
    @Test
    fun dashLikePulse_passedToDecoderCorrectly() {
        controller.startSession()

        // 450ms pulse
        controller.onOpticalTransition(
            OpticalTransition(LightState.OFF, LightState.ON, 1000L, 0L)
        )
        controller.onOpticalTransition(
            OpticalTransition(LightState.ON, LightState.OFF, 1450L, 450L)
        )

        assertThat(controller.currentSymbols.value).isEqualTo("-")
    }

    // 4. The gap between two pulses is measured from OFF to the next ON transition.
    @Test
    fun gapBetweenPulses_measuredFromOffToNextOnTransition() {
        controller.startSession()

        // Pulse 1: DOT (150ms ON)
        controller.onOpticalTransition(
            OpticalTransition(LightState.OFF, LightState.ON, 1000L, 0L)
        )
        controller.onOpticalTransition(
            OpticalTransition(LightState.ON, LightState.OFF, 1150L, 150L)
        )
        assertThat(controller.currentSymbols.value).isEqualTo(".")

        // Gap: 150ms OFF (Symbol gap) followed by Pulse 2: DASH (450ms ON)
        controller.onOpticalTransition(
            OpticalTransition(LightState.OFF, LightState.ON, 1300L, 150L) // gap = 150ms
        )
        // Symbol gap preserves buffer: "."
        assertThat(controller.currentSymbols.value).isEqualTo(".")

        controller.onOpticalTransition(
            OpticalTransition(LightState.ON, LightState.OFF, 1750L, 450L)
        )
        // Now buffer contains ".-" (A)
        assertThat(controller.currentSymbols.value).isEqualTo(".-")
    }

    // 5. Letter and word gap evaluation does not duplicate characters or spaces.
    @Test
    fun letterAndWordGapEvaluation_doesNotDuplicateCharactersOrSpaces() {
        controller.startSession()

        // Send pulse for 'E' (dot, 150ms)
        controller.onOpticalTransition(
            OpticalTransition(LightState.OFF, LightState.ON, 1000L, 0L)
        )
        controller.onOpticalTransition(
            OpticalTransition(LightState.ON, LightState.OFF, 1150L, 150L)
        )

        // At t=1600ms (elapsed 450ms OFF -> Letter gap >= 280ms)
        controller.evaluateIdleGap(1600L)
        assertThat(controller.decodedText.value).isEqualTo("E")

        // Repeated check at t=1650ms should not re-finalize 'E'
        controller.evaluateIdleGap(1650L)
        assertThat(controller.decodedText.value).isEqualTo("E")

        // At t=2000ms (elapsed 850ms OFF -> Word gap >= 750ms)
        controller.evaluateIdleGap(2000L)
        assertThat(controller.decodedText.value).isEqualTo("E ")

        // Repeated check at t=2100ms should not append duplicate space
        controller.evaluateIdleGap(2100L)
        assertThat(controller.decodedText.value).isEqualTo("E ")
    }

    // 6. End-gap finalization works without requiring another pulse.
    @Test
    fun endGapFinalization_worksWithoutRequiringAnotherPulse() {
        controller.startSession()

        // Send 'T' (dash, 450ms)
        controller.onOpticalTransition(
            OpticalTransition(LightState.OFF, LightState.ON, 1000L, 0L)
        )
        controller.onOpticalTransition(
            OpticalTransition(LightState.ON, LightState.OFF, 1450L, 450L)
        )

        // Elapsed OFF exceeds wordEndGapThresholdMs (1300ms)
        controller.evaluateIdleGap(1450L + 1400L)

        // Character 'T' is decoded without trailing extra spaces or needing another pulse
        assertThat(controller.decodedText.value).isEqualTo("T")
    }

    // 7. A receiver restart cannot reuse the previous session's pulse timestamps.
    @Test
    fun receiverRestart_cannotReusePreviousSessionTimestamps() {
        controller.startSession()

        // Pulse in session 1
        controller.onOpticalTransition(
            OpticalTransition(LightState.OFF, LightState.ON, 1000L, 0L)
        )
        controller.onOpticalTransition(
            OpticalTransition(LightState.ON, LightState.OFF, 1150L, 150L)
        )
        assertThat(controller.currentSymbols.value).isEqualTo(".")

        // Restart session
        controller.stopSession()
        controller.startSession()

        // Newly started session should not think there was a prior pulse
        // An incoming OFF->ON should NOT measure gap against the old session's 1150ms
        controller.onOpticalTransition(
            OpticalTransition(LightState.OFF, LightState.ON, 5000L, 3850L)
        )
        // Decoder should have no prior gap fed
        controller.onOpticalTransition(
            OpticalTransition(LightState.ON, LightState.OFF, 5150L, 150L)
        )
        assertThat(controller.currentSymbols.value).isEqualTo(".")
    }

    // 8. A stopped session cannot mutate the active session's decoded message.
    @Test
    fun stoppedSession_cannotMutateDecodedMessage() {
        controller.startSession()
        controller.stopSession()

        // Inactive session ignores any transitions
        controller.onOpticalTransition(
            OpticalTransition(LightState.OFF, LightState.ON, 1000L, 0L)
        )
        controller.onOpticalTransition(
            OpticalTransition(LightState.ON, LightState.OFF, 1150L, 150L)
        )

        assertThat(controller.currentSymbols.value).isEmpty()
        assertThat(controller.decodedText.value).isEmpty()
    }

    // 9. Missing or out-of-order transitions do not crash or create bogus symbols.
    @Test
    fun outOfOrderAndSpuriousTransitions_doNotCrashOrCorruptState() {
        controller.startSession()

        // Spurious OFF before any ON
        controller.onOpticalTransition(
            OpticalTransition(LightState.ON, LightState.OFF, 1000L, 150L)
        )
        assertThat(controller.currentSymbols.value).isEmpty()

        // Negative duration transition
        controller.onOpticalTransition(
            OpticalTransition(LightState.OFF, LightState.ON, 1000L, -50L)
        )
        assertThat(controller.currentSymbols.value).isEmpty()

        // Same-state transition (no-op)
        controller.onOpticalTransition(
            OpticalTransition(LightState.OFF, LightState.OFF, 1200L, 100L)
        )
        assertThat(controller.currentSymbols.value).isEmpty()

        // Valid pulse afterwards still works cleanly
        controller.onOpticalTransition(
            OpticalTransition(LightState.OFF, LightState.ON, 2000L, 0L)
        )
        controller.onOpticalTransition(
            OpticalTransition(LightState.ON, LightState.OFF, 2150L, 150L)
        )
        assertThat(controller.currentSymbols.value).isEqualTo(".")
    }

    // 10. Permission denial and camera initialization failure leave the screen in a recoverable state.
    @Test
    fun permissionDenialAndCameraFailure_leaveRecoverableState() {
        // Permission denied
        controller.setStatus(ReceiverStatus.PERMISSION_REQUIRED)
        assertThat(controller.receiverStatus.value).isEqualTo(ReceiverStatus.PERMISSION_REQUIRED)

        // Camera initialization failure
        controller.setError("Camera provider failed to initialize")
        assertThat(controller.receiverStatus.value).isEqualTo(ReceiverStatus.ERROR)
        assertThat(controller.errorMessage.value).isEqualTo("Camera provider failed to initialize")
        assertThat(controller.isSessionActive).isFalse()

        // User retries -> starts cleanly
        controller.startSession()
        assertThat(controller.receiverStatus.value).isEqualTo(ReceiverStatus.CALIBRATING)
        assertThat(controller.errorMessage.value).isNull()
        assertThat(controller.isSessionActive).isTrue()
    }

    // 11. Asynchronous idle gap ticker verifies letter and word finalization over coroutine time.
    @Test
    fun coroutineIdleTicker_automaticallyFinalizesCharactersAndWords() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val testScope = TestScope(testDispatcher)

        currentTime = 1000L
        val tickingController = MorseReceiverController(
            detector = detector,
            decoder = decoder,
            timeProvider = { currentTime }
        )

        tickingController.startSession(testScope)

        // Send 'S' (3 dots: 150ms ON, 150ms OFF, 150ms ON, 150ms OFF, 150ms ON)
        tickingController.onOpticalTransition(OpticalTransition(LightState.OFF, LightState.ON, 1000L, 0L))
        tickingController.onOpticalTransition(OpticalTransition(LightState.ON, LightState.OFF, 1150L, 150L))

        tickingController.onOpticalTransition(OpticalTransition(LightState.OFF, LightState.ON, 1300L, 150L))
        tickingController.onOpticalTransition(OpticalTransition(LightState.ON, LightState.OFF, 1450L, 150L))

        tickingController.onOpticalTransition(OpticalTransition(LightState.OFF, LightState.ON, 1600L, 150L))
        tickingController.onOpticalTransition(OpticalTransition(LightState.ON, LightState.OFF, 1750L, 150L))

        assertThat(tickingController.currentSymbols.value).isEqualTo("...")

        // Advance simulated time past letter gap threshold (e.g. 500ms after last OFF at 1750 = 2250)
        currentTime = 2250L
        advanceTimeBy(100L) // Run the 50ms ticker

        assertThat(tickingController.decodedText.value).isEqualTo("S")
        assertThat(tickingController.currentSymbols.value).isEmpty()

        tickingController.stopSession()
    }

    // 12. Calibration transition: Calibrating -> Listening upon detector calibration.
    @Test
    fun calibrationProgress_transitionsStatusFromCalibratingToListening() {
        controller.startSession()
        assertThat(controller.receiverStatus.value).isEqualTo(ReceiverStatus.CALIBRATING)

        // Emit diagnostics with isCalibrated = true
        controller.onDiagnostics(
            OpticalDiagnostics(
                timestampMs = 1500L,
                measuredLuma = 40.0f,
                ambientLuma = 38.0f,
                onThreshold = 63.0f,
                offThreshold = 50.0f,
                currentState = LightState.OFF,
                isCalibrated = true
            )
        )

        assertThat(controller.receiverStatus.value).isEqualTo(ReceiverStatus.LISTENING)
    }

    // 13. Clear decoded text resets decoder message.
    @Test
    fun clearDecodedText_clearsDecoderState() {
        controller.startSession()

        // Send 'E'
        controller.onOpticalTransition(OpticalTransition(LightState.OFF, LightState.ON, 1000L, 0L))
        controller.onOpticalTransition(OpticalTransition(LightState.ON, LightState.OFF, 1150L, 150L))
        controller.evaluateIdleGap(1600L)
        assertThat(controller.decodedText.value).isEqualTo("E")

        controller.clearDecodedText()
        assertThat(controller.decodedText.value).isEmpty()
    }
}
