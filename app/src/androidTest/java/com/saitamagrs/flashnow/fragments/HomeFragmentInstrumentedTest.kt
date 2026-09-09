package com.saitamagrs.flashnow.fragments

import androidx.fragment.app.testing.launchFragmentInContainer
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.saitamagrs.flashnow.R
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HomeFragmentInstrumentedTest {

    @Test
    fun testHomeFragmentLaunches() {
        // Given & When
        launchFragmentInContainer<HomeFragment>(themeResId = R.style.Theme_FlashNow)

        // Then - Check that main UI elements are displayed
        onView(withId(R.id.mainPowerButton)).check(matches(isDisplayed()))
    }

    @Test
    fun testHomeFragmentHasToolbar() {
        // Given & When
        launchFragmentInContainer<HomeFragment>(themeResId = R.style.Theme_FlashNow)

        // Then - Toolbar should be displayed
        onView(withId(R.id.toolbar)).check(matches(isDisplayed()))
    }

    @Test
    fun testHomeFragmentHasSettingsButton() {
        // Given & When
        launchFragmentInContainer<HomeFragment>(themeResId = R.style.Theme_FlashNow)

        // Then - Settings button should be displayed
        onView(withId(R.id.btnSettings)).check(matches(isDisplayed()))
    }

    @Test
    fun testHomeFragmentHasTimerButton() {
        // Given & When
        launchFragmentInContainer<HomeFragment>(themeResId = R.style.Theme_FlashNow)

        // Then - Timer button should be displayed
        onView(withId(R.id.btnTimer)).check(matches(isDisplayed()))
    }
}
