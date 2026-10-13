package com.saitamagrs.flashnow.utils

import android.content.Context
import android.util.Log
import com.google.android.gms.ads.MobileAds
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Thread-safe manager responsible for single-initialization of the Google Mobile Ads SDK
 * once user consent has been established or determined not required.
 */
object MobileAdsManager {

    private const val TAG = "MobileAdsManager"
    private val isMobileAdsInitialized = AtomicBoolean(false)
    private val initializationListeners = mutableListOf<() -> Unit>()

    /**
     * Initializes the Mobile Ads SDK if [GoogleMobileAdsConsentManager.canRequestAds] is true.
     * Guaranteed to execute initialization at most once per process lifecycle.
     */
    fun initializeIfPermitted(context: Context, onInitialized: (() -> Unit)? = null) {
        val consentManager = GoogleMobileAdsConsentManager.getInstance(context)
        if (consentManager.canRequestAds) {
            if (isMobileAdsInitialized.compareAndSet(false, true)) {
                Log.d(TAG, "Initializing Google Mobile Ads SDK")
                MobileAds.initialize(context.applicationContext) {
                    Log.d(TAG, "Google Mobile Ads SDK initialization complete")
                    notifyListeners()
                }
            } else {
                onInitialized?.invoke()
            }
        } else {
            Log.d(TAG, "Mobile Ads initialization skipped: user consent not granted yet")
        }

        if (onInitialized != null) {
            synchronized(initializationListeners) {
                if (isMobileAdsInitialized.get()) {
                    onInitialized()
                } else {
                    initializationListeners.add(onInitialized)
                }
            }
        }
    }

    /**
     * Checks if the Mobile Ads SDK has completed initialization.
     */
    fun isInitialized(): Boolean = isMobileAdsInitialized.get()

    /**
     * Registers a callback to be notified when Mobile Ads finishes initialization.
     * If already initialized, the callback is executed immediately.
     */
    fun addInitializationListener(listener: () -> Unit) {
        synchronized(initializationListeners) {
            if (isMobileAdsInitialized.get()) {
                listener()
            } else {
                initializationListeners.add(listener)
            }
        }
    }

    private fun notifyListeners() {
        val callbacks = synchronized(initializationListeners) {
            val list = ArrayList(initializationListeners)
            initializationListeners.clear()
            list
        }
        callbacks.forEach { it() }
    }
}
