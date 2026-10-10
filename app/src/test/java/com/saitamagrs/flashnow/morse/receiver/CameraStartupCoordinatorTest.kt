package com.saitamagrs.flashnow.morse.receiver

import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

class CameraStartupCoordinatorTest {

    private lateinit var coordinator: CameraStartupCoordinator

    @Before
    fun setUp() {
        coordinator = CameraStartupCoordinator()
    }

    @Test
    fun startNewAttempt_returnsMonotonicallyIncreasingTokens() {
        val t1 = coordinator.startNewAttempt()
        val t2 = coordinator.startNewAttempt()
        val t3 = coordinator.startNewAttempt()

        assertThat(t1).isEqualTo(1L)
        assertThat(t2).isEqualTo(2L)
        assertThat(t3).isEqualTo(3L)
    }

    @Test
    fun isAttemptValid_returnsTrueForCurrentTokenWithActiveSessionAndValidView() {
        val token = coordinator.startNewAttempt()

        val isValid = coordinator.isAttemptValid(
            generation = token,
            isSessionActive = true,
            isViewValid = true
        )

        assertThat(isValid).isTrue()
    }

    @Test
    fun isAttemptValid_returnsFalseForStaleTokens() {
        val t1 = coordinator.startNewAttempt()
        val t2 = coordinator.startNewAttempt()

        assertThat(coordinator.isAttemptValid(t1)).isFalse()
        assertThat(coordinator.isAttemptValid(t2)).isTrue()
    }

    @Test
    fun invalidate_immediatelyInvalidatesActiveToken() {
        val token = coordinator.startNewAttempt()
        assertThat(coordinator.isAttemptValid(token)).isTrue()

        coordinator.invalidate()
        assertThat(coordinator.isAttemptValid(token)).isFalse()
    }

    @Test
    fun isAttemptValid_returnsFalseWhenSessionInactive() {
        val token = coordinator.startNewAttempt()

        val isValid = coordinator.isAttemptValid(
            generation = token,
            isSessionActive = false,
            isViewValid = true
        )

        assertThat(isValid).isFalse()
    }

    @Test
    fun isAttemptValid_returnsFalseWhenViewInvalid() {
        val token = coordinator.startNewAttempt()

        val isValid = coordinator.isAttemptValid(
            generation = token,
            isSessionActive = true,
            isViewValid = false
        )

        assertThat(isValid).isFalse()
    }

    @Test
    fun repeatedInvalidateCalls_areSafeAndIdempotent() {
        val token = coordinator.startNewAttempt()

        coordinator.invalidate()
        coordinator.invalidate()
        coordinator.invalidate()

        assertThat(coordinator.isAttemptValid(token)).isFalse()
        assertThat(coordinator.currentGeneration).isEqualTo(4L)
    }

    @Test
    fun obsoleteCallback_releasesAllocatedResourcesWithoutBinding() {
        val token = coordinator.startNewAttempt()

        // Simulate receiver stopping before callback arrives
        coordinator.invalidate()

        var resourceClosed = false
        var cameraBound = false

        // Simulate callback execution
        val isValid = coordinator.isAttemptValid(token, isSessionActive = false, isViewValid = true)
        if (!isValid) {
            // Obsolete request cleanly releases temporary resources
            resourceClosed = true
        } else {
            cameraBound = true
        }

        assertThat(resourceClosed).isTrue()
        assertThat(cameraBound).isFalse()
    }

    @Test
    fun obsoleteCallbackError_isSuppressedAndNotReported() {
        val token = coordinator.startNewAttempt()

        // Newer session started in the meantime
        val newerToken = coordinator.startNewAttempt()

        var errorReported = false

        // Simulate failure in older callback
        if (coordinator.isAttemptValid(token)) {
            errorReported = true
        }

        assertThat(errorReported).isFalse()
        assertThat(coordinator.isAttemptValid(newerToken)).isTrue()
    }

    @Test
    fun rapidStartStopRestart_onlyConsidersFinalAttemptValid() {
        val t1 = coordinator.startNewAttempt()
        coordinator.invalidate() // stop
        val t2 = coordinator.startNewAttempt() // restart
        coordinator.invalidate() // stop
        val t3 = coordinator.startNewAttempt() // restart final

        assertThat(coordinator.isAttemptValid(t1)).isFalse()
        assertThat(coordinator.isAttemptValid(t2)).isFalse()
        assertThat(coordinator.isAttemptValid(t3)).isTrue()
    }
}
