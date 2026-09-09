package com.saitamagrs.flashnow.utils

import android.content.Context
import android.content.Intent
import android.os.Build
import com.saitamagrs.flashnow.services.FlashlightTimerService
import io.mockk.MockKAnnotations
import io.mockk.Runs
import io.mockk.every
import io.mockk.impl.annotations.RelaxedMockK
import io.mockk.just
import io.mockk.slot
import io.mockk.verify
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.P])
class TimerManagerTest {

    @RelaxedMockK
    private lateinit var context: Context

    private lateinit var timerManager: TimerManager

    @Before
    fun setup() {
        MockKAnnotations.init(this)
        timerManager = TimerManager(context)
    }

    @Test
    fun `startTimer should create intent with correct action and duration`() {
        // Given
        val durationMinutes = 5
        val intentSlot = slot<Intent>()

        every { context.startService(capture(intentSlot)) } returns null

        // When
        timerManager.startTimer(durationMinutes)

        // Then
        val capturedIntent = intentSlot.captured
        assert(capturedIntent.action == FlashlightTimerService.ACTION_START_TIMER)
        assert(
            capturedIntent.getLongExtra(FlashlightTimerService.EXTRA_DURATION_MINUTES, 0L) ==
                durationMinutes.toLong()
        )
    }

    @Test
    fun `startTimer should start service`() {
        // Given
        every { context.startService(any()) } returns null

        // When
        timerManager.startTimer(10)

        // Then
        verify { context.startService(any()) }
    }

    @Test
    fun `stopTimer should create intent with stop action`() {
        // Given
        val intentSlot = slot<Intent>()
        every { context.startService(capture(intentSlot)) } returns null

        // When
        timerManager.stopTimer()

        // Then
        val capturedIntent = intentSlot.captured
        assert(capturedIntent.action == FlashlightTimerService.ACTION_STOP_TIMER)
    }

    @Test
    fun `stopTimer should start service`() {
        // Given
        every { context.startService(any()) } returns null

        // When
        timerManager.stopTimer()

        // Then
        verify { context.startService(any()) }
    }

    @Test
    fun `isTimerRunning should return false by default`() {
        // Then
        assert(!timerManager.isTimerRunning())
    }

    @Test
    fun `presetDurations should contain expected presets`() {
        // Then
        val presets = TimerManager.presetDurations

        assert(presets.any { it.minutes == 1 && it.title == "1 mins" })
        assert(presets.any { it.minutes == 5 && it.title == "5 mins" })
        assert(presets.any { it.minutes == 10 && it.title == "10 mins" })
        assert(presets.any { it.minutes == 15 && it.title == "15 mins" })
        assert(presets.any { it.minutes == 30 && it.title == "30 mins" })
        assert(presets.any { it.minutes == 60 && it.title == "1 hour" })
    }

    @Test
    fun `presetDurations should have correct descriptions`() {
        // Then
        val presets = TimerManager.presetDurations

        assert(presets[0].description == "Quick test")
        assert(presets[1].description == "Quick use")
        assert(presets[2].description == "Short task")
    }

    @Test
    fun `TimerPreset should store values correctly`() {
        // Given
        val preset = TimerPreset(30, "30 mins", "Medium task")

        // Then
        assert(preset.minutes == 30)
        assert(preset.title == "30 mins")
        assert(preset.description == "Medium task")
    }

    @Test(expected = Exception::class)
    fun `startTimer should throw exception when service start fails`() {
        // Given
        every { context.startService(any()) } throws RuntimeException("Service failed")

        // When - should throw
        timerManager.startTimer(5)
    }

    @Test
    fun `stopTimer should not throw when service start fails`() {
        // Given
        every { context.startService(any()) } throws RuntimeException("Service failed")

        // When - should not throw
        timerManager.stopTimer()
    }
}
