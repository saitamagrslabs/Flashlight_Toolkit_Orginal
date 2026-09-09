package com.saitamagrs.flashnow.viewmodels

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import app.cash.turbine.test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {

    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var viewModel: HomeViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        viewModel = HomeViewModel()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `sosActive should be false by default`() = runTest {
        // Then
        viewModel.sosActive.test {
            assertEquals(false, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `strobeActive should be false by default`() = runTest {
        // Then
        viewModel.strobeActive.test {
            assertEquals(false, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `setSosActive should update sosActive to true`() = runTest {
        // When
        viewModel.setSosActive(true)

        // Then
        viewModel.sosActive.test {
            assertEquals(true, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `setSosActive should update sosActive to false`() = runTest {
        // Given
        viewModel.setSosActive(true)

        // When
        viewModel.setSosActive(false)

        // Then
        viewModel.sosActive.test {
            assertEquals(false, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `setStrobeActive should update strobeActive to true`() = runTest {
        // When
        viewModel.setStrobeActive(true)

        // Then
        viewModel.strobeActive.test {
            assertEquals(true, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `setStrobeActive should update strobeActive to false`() = runTest {
        // Given
        viewModel.setStrobeActive(true)

        // When
        viewModel.setStrobeActive(false)

        // Then
        viewModel.strobeActive.test {
            assertEquals(false, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `sosActive LiveData should emit multiple values when changed`() = runTest {
        // When
        viewModel.setSosActive(true)
        viewModel.setSosActive(false)
        viewModel.setSosActive(true)

        // Then
        viewModel.sosActive.test {
            assertEquals(true, awaitItem()) // Latest value should be true
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `strobeActive LiveData should emit multiple values when changed`() = runTest {
        // When
        viewModel.setStrobeActive(true)
        viewModel.setStrobeActive(false)
        viewModel.setStrobeActive(true)

        // Then
        viewModel.strobeActive.test {
            assertEquals(true, awaitItem()) // Latest value should be true
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `viewModel should maintain independent states for sos and strobe`() = runTest {
        // When
        viewModel.setSosActive(true)
        viewModel.setStrobeActive(false)

        // Then
        viewModel.sosActive.test {
            assertEquals(true, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }

        viewModel.strobeActive.test {
            assertEquals(false, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `setSosActive with same value should not cause issues`() {
        // Given
        viewModel.setSosActive(true)

        // When - set same value again
        viewModel.setSosActive(true)

        // Then - value should remain true
        assertEquals(true, viewModel.sosActive.value)
    }

    @Test
    fun `setStrobeActive with same value should not cause issues`() {
        // Given
        viewModel.setStrobeActive(true)

        // When - set same value again
        viewModel.setStrobeActive(true)

        // Then - value should remain true
        assertEquals(true, viewModel.strobeActive.value)
    }

    @Test
    fun `initial state of both LiveData should be false`() {
        // Then
        assertFalse(viewModel.sosActive.value ?: true)
        assertFalse(viewModel.strobeActive.value ?: true)
    }
}
