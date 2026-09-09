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
class MorseCodeFragmentInstrumentedTest {

    @Test
    fun testMorseCodeFragmentLaunches() {
        // Given & When
        launchFragmentInContainer<MorseCodeFragment>(themeResId = R.style.Theme_FlashNow)

        // Then - Check that main UI elements are displayed
        onView(withId(R.id.toolbar)).check(matches(isDisplayed()))
    }
}
