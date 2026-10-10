package com.saitamagrs.flashnow.morse.receiver

import android.os.SystemClock
import com.saitamagrs.flashnow.morse.decoder.MorseDecoder
import com.saitamagrs.flashnow.morse.detector.LightState
import com.saitamagrs.flashnow.morse.detector.OpticalDiagnostics
import com.saitamagrs.flashnow.morse.detector.OpticalSignalDetector
import com.saitamagrs.flashnow.morse.detector.OpticalSignalListener
import com.saitamagrs.flashnow.morse.detector.OpticalTransition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * High-level status of the optical Morse receiver subsystem.
 */
enum class ReceiverStatus {
    /** Receiver is inactive / stopped. */
    STOPPED,

    /** Camera permission is required before receiver can start. */
    PERMISSION_REQUIRED,

    /** Camera acquisition is initializing. */
    STARTING,

    /** Background light baseline is calibrating. */
    CALIBRATING,

    /** Optical detector is calibrated and actively listening for signals. */
    LISTENING,

    /** A recoverable camera or initialization error occurred. */
    ERROR
}

/**
 * Controller bridging the CameraX optical detector ([OpticalSignalDetector]) to the pure
 * Kotlin Morse timing decoder ([MorseDecoder]).
 *
 * Responsibilities:
 * - Coordinates receiver sessions with monotonic timestamp isolation.
 * - Converts confirmed [OpticalTransition] events into pulse durations and intervening gaps.
 * - Evaluates elapsed OFF gaps asynchronously to finalize characters, words, and end-of-transmission
 *   without requiring another incoming pulse.
 * - Protects against out-of-order transitions, retrograde timestamps, and stale session callbacks.
 * - Exposes unified observable state flows for the UI.
 *
 * Pure Kotlin design enables 100% testability on JVM with simulated clocks.
 */
class MorseReceiverController(
    val detector: OpticalSignalDetector = OpticalSignalDetector(),
    val decoder: MorseDecoder = MorseDecoder(),
    val timeProvider: () -> Long = {
        try {
            SystemClock.elapsedRealtime()
        } catch (_: Throwable) {
            System.nanoTime() / 1_000_000L
        }
    }
) : OpticalSignalListener {

    private val lock = Any()

    @Volatile
    var isSessionActive: Boolean = false
        private set

    @Volatile
    var currentSessionId: Long = 0L
        private set

    private var hasReceivedFirstPulse: Boolean = false
    private var pulseStartTimestampMs: Long = 0L
    private var lastOffTimestampMs: Long = 0L

    internal enum class IdleGapStage {
        NONE,
        LETTER_FINALIZED,
        WORD_FINALIZED,
        END_FINALIZED
    }
    internal var idleGapStage: IdleGapStage = IdleGapStage.NONE

    private var tickerJob: Job? = null

    // State flows for UI binding
    private val _receiverStatus = MutableStateFlow(ReceiverStatus.STOPPED)
    val receiverStatus: StateFlow<ReceiverStatus> = _receiverStatus.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    val opticalState: StateFlow<LightState> = detector.opticalState
    val diagnostics: StateFlow<OpticalDiagnostics> = detector.diagnostics
    val decodedText: StateFlow<String> = decoder.decodedText
    val currentSymbols: StateFlow<String> = decoder.currentSymbols

    init {
        detector.listener = this
    }

    /**
     * Starts a new receiver session.
     * Resets detector calibration and session-specific timestamps while isolating from any previous session.
     * Does NOT erase existing decoded message text (preserves user context across routine lifecycles).
     *
     * @param coroutineScope Optional scope for the idle gap finalization ticker.
     */
    fun startSession(coroutineScope: CoroutineScope? = null) = synchronized(lock) {
        // Stop any running session first
        internalStopSession()

        currentSessionId++
        val session = currentSessionId
        isSessionActive = true
        hasReceivedFirstPulse = false
        pulseStartTimestampMs = 0L
        lastOffTimestampMs = 0L
        idleGapStage = IdleGapStage.NONE

        _errorMessage.value = null
        _receiverStatus.value = ReceiverStatus.CALIBRATING

        detector.reset()
        detector.start()

        coroutineScope?.let { scope ->
            tickerJob = scope.launch {
                while (isActive && isSessionActive && currentSessionId == session) {
                    delay(50L)
                    evaluateIdleGap(timeProvider())
                }
            }
        }
    }

    /**
     * Stops the active receiver session and shuts down the optical detector.
     * Cancels idle gap tickers and prevents any in-flight frames or callbacks from mutating state.
     */
    fun stopSession() = synchronized(lock) {
        internalStopSession()
    }

    private fun internalStopSession() {
        isSessionActive = false
        currentSessionId++
        tickerJob?.cancel()
        tickerJob = null

        decoder.flush()
        detector.stop()
        hasReceivedFirstPulse = false
        pulseStartTimestampMs = 0L
        lastOffTimestampMs = 0L
        idleGapStage = IdleGapStage.NONE

        _receiverStatus.value = ReceiverStatus.STOPPED
    }

    /**
     * Sets an error state on the controller (e.g. CameraX initialization failure).
     */
    fun setError(message: String) = synchronized(lock) {
        internalStopSession()
        _errorMessage.value = message
        _receiverStatus.value = ReceiverStatus.ERROR
    }

    /**
     * Updates receiver status (e.g. PERMISSION_REQUIRED, STARTING).
     */
    fun setStatus(status: ReceiverStatus) = synchronized(lock) {
        _receiverStatus.value = status
    }

    /**
     * Clears all accumulated decoded message text and pending symbols.
     */
    fun clearDecodedText() = synchronized(lock) {
        decoder.reset()
    }

    // =========================================================================
    // Optical Signal Listener Implementation
    // =========================================================================

    override fun onOpticalTransition(transition: OpticalTransition) = synchronized(lock) {
        if (!isSessionActive) return

        // Guard against negative durations or invalid self-transitions
        if (transition.previousStateDurationMs < 0) return
        if (transition.previousState == transition.newState) return

        when (transition.newState) {
            LightState.ON -> {
                // Confirmed OFF -> ON transition:
                // An active pulse is beginning.
                idleGapStage = IdleGapStage.NONE

                // If a prior pulse occurred in this session, the intervening OFF duration is a gap!
                if (hasReceivedFirstPulse) {
                    val gapDuration = transition.previousStateDurationMs
                    decoder.onGap(gapDuration)
                }

                hasReceivedFirstPulse = true
                pulseStartTimestampMs = transition.transitionTimestampMs
            }
            LightState.OFF -> {
                // Confirmed ON -> OFF transition:
                // An active pulse has ended.
                if (!hasReceivedFirstPulse) return // Ignore spurious OFF before any ON

                val pulseDuration = transition.previousStateDurationMs
                decoder.onPulse(pulseDuration)

                lastOffTimestampMs = transition.transitionTimestampMs
                idleGapStage = IdleGapStage.NONE
            }
        }
    }

    override fun onDiagnostics(diagnostics: OpticalDiagnostics) {
        synchronized(lock) {
            if (isSessionActive && _receiverStatus.value == ReceiverStatus.CALIBRATING && diagnostics.isCalibrated) {
                _receiverStatus.value = ReceiverStatus.LISTENING
            }
        }
    }

    /**
     * Evaluates elapsed OFF time during continued silence to finalize pending characters,
     * word boundaries, and end-of-transmission frames without requiring another pulse.
     *
     * Idempotent: stages are tracked so identical boundaries are not repeatedly emitted.
     *
     * @param currentTimeMs Monotonic timestamp in milliseconds.
     */
    fun evaluateIdleGap(currentTimeMs: Long) = synchronized(lock) {
        if (!isSessionActive || !hasReceivedFirstPulse || lastOffTimestampMs <= 0L) return
        if (detector.currentState != LightState.OFF) return

        val elapsedOff = currentTimeMs - lastOffTimestampMs
        if (elapsedOff < 0) return

        val cfg = decoder.config
        when {
            elapsedOff >= cfg.wordEndGapThresholdMs -> {
                if (idleGapStage < IdleGapStage.END_FINALIZED) {
                    decoder.onGap(elapsedOff)
                    idleGapStage = IdleGapStage.END_FINALIZED
                }
            }
            elapsedOff >= cfg.letterWordGapThresholdMs -> {
                if (idleGapStage < IdleGapStage.WORD_FINALIZED) {
                    decoder.onGap(elapsedOff)
                    idleGapStage = IdleGapStage.WORD_FINALIZED
                }
            }
            elapsedOff >= cfg.symbolLetterGapThresholdMs -> {
                if (idleGapStage < IdleGapStage.LETTER_FINALIZED) {
                    decoder.onGap(elapsedOff)
                    idleGapStage = IdleGapStage.LETTER_FINALIZED
                }
            }
        }
    }
}
