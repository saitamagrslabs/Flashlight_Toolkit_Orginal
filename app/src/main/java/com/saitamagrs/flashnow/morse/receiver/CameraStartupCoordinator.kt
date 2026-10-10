package com.saitamagrs.flashnow.morse.receiver

/**
 * Coordination guard for asynchronous CameraX receiver startup requests.
 *
 * Provides thread-safe generation/token management to invalidate obsolete in-flight
 * startup callbacks when the receiver is stopped, restarted, or paused.
 *
 * Responsibilities:
 * - Generates monotonic generation tokens for startup attempts.
 * - Invalidates obsolete callbacks on receiver stop, pause, mode change, or view destruction.
 * - Validates callback state prior to binding use cases or touching UI.
 * - Protects against race conditions where an older, delayed camera initialization callback
 *   could overwrite or unbind a newer active session, or report stale errors.
 */
class CameraStartupCoordinator {

    private val lock = Any()

    @Volatile
    var currentGeneration: Long = 0L
        private set

    /**
     * Begins a new startup attempt, returning a unique generation token for this request.
     * Automatically invalidates any previous in-flight startup request.
     */
    fun startNewAttempt(): Long = synchronized(lock) {
        return ++currentGeneration
    }

    /**
     * Explicitly invalidates any active or in-flight startup request.
     * @return The new generation token after invalidation.
     */
    fun invalidate(): Long = synchronized(lock) {
        return ++currentGeneration
    }

    /**
     * Checks whether the given [generation] token is still current and valid to proceed.
     *
     * @param generation The token captured when the startup attempt began.
     * @param isSessionActive Whether the receiver session is actively running.
     * @param isViewValid Whether the hosting view/fragment binding is valid.
     * @return True if the callback is valid and current; false if obsolete or invalidated.
     */
    fun isAttemptValid(
        generation: Long,
        isSessionActive: Boolean = true,
        isViewValid: Boolean = true
    ): Boolean = synchronized(lock) {
        return generation == currentGeneration && isSessionActive && isViewValid
    }
}
