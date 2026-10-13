package com.saitamagrs.flashnow.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MobileAdsManagerTest {

    @Test
    fun testInitializationListenersQueueWhenNotInitialized() {
        var callbackExecuted = false
        if (!MobileAdsManager.isInitialized()) {
            MobileAdsManager.addInitializationListener {
                callbackExecuted = true
            }
            // Listener should be queued, not executed yet
            assertFalse(callbackExecuted)
        }
    }
}
