package com.saitamagrs.flashnow.utils

import android.content.Context
import com.google.android.gms.ads.AdView
import com.saitamagrs.flashnow.fragments.BaseAdFragment
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class MobileAdsManagerTest {

    private lateinit var mockContext: Context

    @Before
    fun setUp() {
        mockContext = mock(Context::class.java)
        MobileAdsManager.resetForTesting()
        GoogleMobileAdsConsentManager.resetForTesting()
    }

    @After
    fun tearDown() {
        MobileAdsManager.resetForTesting()
        GoogleMobileAdsConsentManager.resetForTesting()
    }

    @Test
    fun testInitialStateIsNotStarted() {
        assertEquals(MobileAdsManager.State.NOT_STARTED, MobileAdsManager.getState())
        assertFalse(MobileAdsManager.isInitialized())
        assertFalse(MobileAdsManager.isInitializing())
    }

    @Test
    fun testConcurrentInitializationAttemptsStartSdkOnce() {
        GoogleMobileAdsConsentManager.testCanRequestAds = true
        val sdkInitCount = AtomicInteger(0)
        MobileAdsManager.testInitializer = { _, _ ->
            sdkInitCount.incrementAndGet()
        }

        val threadCount = 10
        val latch = CountDownLatch(threadCount)
        val startLatch = CountDownLatch(1)

        for (i in 0 until threadCount) {
            Thread {
                startLatch.await(5, TimeUnit.SECONDS)
                MobileAdsManager.initializeIfPermitted(mockContext)
                latch.countDown()
            }.start()
        }

        startLatch.countDown()
        assertTrue(latch.await(5, TimeUnit.SECONDS))

        // SDK initialization must only have commenced once across all concurrent threads
        assertEquals(1, sdkInitCount.get())
        assertEquals(MobileAdsManager.State.INITIALIZING, MobileAdsManager.getState())
    }

    @Test
    fun testCompletionCallbacksInvokedExactlyOnce() {
        GoogleMobileAdsConsentManager.testCanRequestAds = true
        var callbackInvocationCount = 0
        var completeCallback: (() -> Unit)? = null

        MobileAdsManager.testInitializer = { _, onComplete ->
            completeCallback = onComplete
        }

        MobileAdsManager.initializeIfPermitted(mockContext) {
            callbackInvocationCount++
        }

        // Before completion: must not have been invoked
        assertEquals(0, callbackInvocationCount)
        assertFalse(MobileAdsManager.isInitialized())

        // Trigger completion callback
        completeCallback?.invoke()

        // After completion: must be invoked exactly once
        assertEquals(1, callbackInvocationCount)
        assertTrue(MobileAdsManager.isInitialized())
        assertEquals(MobileAdsManager.State.INITIALIZED, MobileAdsManager.getState())
    }

    @Test
    fun testCallbacksArrivingDuringInitializationWaitUntilCompletion() {
        GoogleMobileAdsConsentManager.testCanRequestAds = true
        var triggerComplete: (() -> Unit)? = null

        MobileAdsManager.testInitializer = { _, onComplete ->
            triggerComplete = onComplete
        }

        MobileAdsManager.initializeIfPermitted(mockContext)
        assertTrue(MobileAdsManager.isInitializing())

        var listenerFired = false
        var initParamCallbackFired = false

        MobileAdsManager.addInitializationListener {
            listenerFired = true
        }

        MobileAdsManager.initializeIfPermitted(mockContext) {
            initParamCallbackFired = true
        }

        // Callbacks arriving while INITIALIZING must wait and not execute yet
        assertFalse(listenerFired)
        assertFalse(initParamCallbackFired)

        // Complete initialization
        triggerComplete?.invoke()

        // Both waiting callbacks must have fired exactly once
        assertTrue(listenerFired)
        assertTrue(initParamCallbackFired)
        assertTrue(MobileAdsManager.isInitialized())
    }

    @Test
    fun testCallbacksArrivingAfterCompletionRunOnce() {
        GoogleMobileAdsConsentManager.testCanRequestAds = true
        MobileAdsManager.testInitializer = { _, onComplete ->
            onComplete()
        }

        MobileAdsManager.initializeIfPermitted(mockContext)
        assertTrue(MobileAdsManager.isInitialized())

        var listenerCallCount = 0
        var initParamCallCount = 0

        MobileAdsManager.addInitializationListener {
            listenerCallCount++
        }

        MobileAdsManager.initializeIfPermitted(mockContext) {
            initParamCallCount++
        }

        assertEquals(1, listenerCallCount)
        assertEquals(1, initParamCallCount)
    }

    @Test
    fun testListenerRemovalPreventsDeliveryToDestroyedView() {
        GoogleMobileAdsConsentManager.testCanRequestAds = true
        var triggerComplete: (() -> Unit)? = null

        MobileAdsManager.testInitializer = { _, onComplete ->
            triggerComplete = onComplete
        }

        MobileAdsManager.initializeIfPermitted(mockContext)

        var destroyedViewCallbackRan = false
        val registration = MobileAdsManager.addInitializationListener {
            destroyedViewCallbackRan = true
        }

        // Simulate Fragment onDestroyView: remove listener registration
        registration.remove()

        // SDK initialization finishes
        triggerComplete?.invoke()

        // Removed listener must never receive the callback
        assertFalse(destroyedViewCallbackRan)
    }

    @Test
    fun testConsentWithheldBlocksInitialization() {
        GoogleMobileAdsConsentManager.testCanRequestAds = false
        var sdkStarted = false

        MobileAdsManager.testInitializer = { _, onComplete ->
            sdkStarted = true
            onComplete()
        }

        var callbackRan = false
        MobileAdsManager.initializeIfPermitted(mockContext) {
            callbackRan = true
        }

        assertFalse(sdkStarted)
        assertFalse(callbackRan)
        assertFalse(MobileAdsManager.isInitialized())
        assertEquals(MobileAdsManager.State.NOT_STARTED, MobileAdsManager.getState())
    }

    @Test
    fun testConsentGrantedLaterTriggersAppropriateInitializationPath() {
        // 1. Consent initially withheld
        GoogleMobileAdsConsentManager.testCanRequestAds = false
        var sdkStarted = false
        var completeCallback: (() -> Unit)? = null

        MobileAdsManager.testInitializer = { _, onComplete ->
            sdkStarted = true
            completeCallback = onComplete
        }

        MobileAdsManager.initializeIfPermitted(mockContext)
        assertFalse(sdkStarted)

        // 2. User later grants consent (e.g. from UMP or Settings)
        GoogleMobileAdsConsentManager.testCanRequestAds = true
        var listenerRan = false
        MobileAdsManager.addInitializationListener {
            listenerRan = true
        }

        MobileAdsManager.initializeIfPermitted(mockContext)
        assertTrue(sdkStarted)

        completeCallback?.invoke()
        assertTrue(listenerRan)
        assertTrue(MobileAdsManager.isInitialized())
    }

    @Test
    fun testConsentWithdrawnPreventsSubsequentAdRequests() {
        GoogleMobileAdsConsentManager.testCanRequestAds = true
        MobileAdsManager.testInitializer = { _, onComplete -> onComplete() }
        MobileAdsManager.initializeIfPermitted(mockContext)
        assertTrue(MobileAdsManager.isInitialized())

        // User opens Settings and revokes consent
        GoogleMobileAdsConsentManager.testCanRequestAds = false

        // Subsequent check in any fragment or manager confirms ads cannot be requested
        val consentManager = GoogleMobileAdsConsentManager.getInstance(mockContext)
        assertFalse(consentManager.canRequestAds)
    }

    @Test
    fun testBaseAdFragmentDuplicateAdLoadTriggersDoNotCreateConcurrentRequests() {
        GoogleMobileAdsConsentManager.testCanRequestAds = true
        MobileAdsManager.testInitializer = { _, onComplete -> onComplete() }
        MobileAdsManager.initializeIfPermitted(mockContext)

        val testFragment = object : BaseAdFragment() {
            override fun getContext(): Context = mockContext
            override val adView: AdView? = null
        }

        // Initially IDLE
        assertEquals(BaseAdFragment.AdLoadState.IDLE, testFragment.getAdLoadState())

        // Simulate in-flight loading state
        testFragment.setAdLoadStateForTesting(BaseAdFragment.AdLoadState.LOADING)
        testFragment.attemptLoadBannerAd()

        // Must remain in LOADING state, not restarted
        assertEquals(BaseAdFragment.AdLoadState.LOADING, testFragment.getAdLoadState())

        // Simulate loaded state
        testFragment.setAdLoadStateForTesting(BaseAdFragment.AdLoadState.LOADED)
        testFragment.attemptLoadBannerAd()

        // Must remain in LOADED state, not restarted
        assertEquals(BaseAdFragment.AdLoadState.LOADED, testFragment.getAdLoadState())
    }

    @Test
    fun testBaseAdFragmentFailedAdLoadPermitsLegitimateLaterRetry() {
        val mockAdView = mock(AdView::class.java)
        val testFragment = object : BaseAdFragment() {
            override fun getContext(): Context = mockContext
            override val adView: AdView = mockAdView
        }

        // Set state to FAILED
        testFragment.setAdLoadStateForTesting(BaseAdFragment.AdLoadState.FAILED)
        assertEquals(BaseAdFragment.AdLoadState.FAILED, testFragment.getAdLoadState())

        // 1. Consent withdrawn: onResume resets state to IDLE
        GoogleMobileAdsConsentManager.testCanRequestAds = false
        testFragment.onResume()
        assertEquals(BaseAdFragment.AdLoadState.IDLE, testFragment.getAdLoadState())

        // 2. Retry permitted when consent granted and SDK initialized
        GoogleMobileAdsConsentManager.testCanRequestAds = true
        MobileAdsManager.testInitializer = { _, onComplete -> onComplete() }
        MobileAdsManager.initializeIfPermitted(mockContext)
        assertTrue(MobileAdsManager.isInitialized())

        testFragment.setAdLoadStateForTesting(BaseAdFragment.AdLoadState.FAILED)
        testFragment.onResume()
        // onResume triggers attemptLoadBannerAd(), transitioning FAILED -> LOADING
        assertEquals(BaseAdFragment.AdLoadState.LOADING, testFragment.getAdLoadState())
    }
}
