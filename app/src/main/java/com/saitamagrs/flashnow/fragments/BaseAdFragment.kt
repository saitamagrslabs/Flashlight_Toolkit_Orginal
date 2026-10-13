package com.saitamagrs.flashnow.fragments

import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.annotation.VisibleForTesting
import androidx.fragment.app.Fragment
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.LoadAdError
import com.saitamagrs.flashnow.utils.GoogleMobileAdsConsentManager
import com.saitamagrs.flashnow.utils.MobileAdsManager

/**
 * A base fragment that handles common logic for loading and managing a banner ad,
 * strictly gated by Google UMP consent verification and MobileAdsManager initialization state.
 *
 * Implements explicit lifecycle-scoped ad-load state tracking to prevent duplicate/concurrent requests.
 */
abstract class BaseAdFragment : Fragment() {

    enum class AdLoadState {
        IDLE,
        LOADING,
        LOADED,
        FAILED
    }

    /**
     * The AdView from the child fragment's layout binding.
     * Must be implemented by subclasses.
     */
    protected abstract val adView: AdView?

    private var adLoadState = AdLoadState.IDLE
    private var initRegistration: MobileAdsManager.ListenerRegistration? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        adLoadState = AdLoadState.IDLE

        // Attempt ad load if initialization and consent are already satisfied
        attemptLoadBannerAd()

        // If Mobile Ads SDK is not yet initialized (e.g. awaiting initial consent grant),
        // register a lifecycle-safe listener to trigger the load once initialization completes.
        if (!MobileAdsManager.isInitialized()) {
            initRegistration = MobileAdsManager.addInitializationListener {
                if (isAdded && !isDetached && this.view != null) {
                    activity?.runOnUiThread {
                        if (isAdded && !isDetached && this.view != null) {
                            if (adLoadState == AdLoadState.IDLE || adLoadState == AdLoadState.FAILED) {
                                attemptLoadBannerAd()
                            }
                        }
                    }
                }
            }
        }
    }

    @VisibleForTesting
    internal fun attemptLoadBannerAd() {
        val currentContext = context ?: return
        val consentManager = GoogleMobileAdsConsentManager.getInstance(currentContext)

        // Strict UMP consent check: do not request ads unless canRequestAds returns true
        if (!consentManager.canRequestAds) {
            Log.d("AdMob", "Ad request deferred: user consent not granted yet in ${this.javaClass.simpleName}")
            adView?.visibility = View.GONE
            adLoadState = AdLoadState.IDLE
            return
        }

        // Strict initialization check: do not request ads before Mobile Ads SDK initialization completes
        if (!MobileAdsManager.isInitialized()) {
            Log.d("AdMob", "Ad request deferred: Mobile Ads SDK not initialized yet in ${this.javaClass.simpleName}")
            return
        }

        // Prevent concurrent or duplicate ad loads for the current view lifecycle
        if (adLoadState == AdLoadState.LOADING || adLoadState == AdLoadState.LOADED) {
            Log.d("AdMob", "Ad request skipped: already in state $adLoadState in ${this.javaClass.simpleName}")
            return
        }

        val targetAdView = adView ?: run {
            Log.w("AdMob", "AdView is null in ${this.javaClass.simpleName}")
            return
        }

        adLoadState = AdLoadState.LOADING
        val adRequest = AdRequest.Builder().build()
        targetAdView.adListener = object : AdListener() {
            override fun onAdLoaded() {
                Log.d("AdMob", "Ad loaded successfully in ${this@BaseAdFragment.javaClass.simpleName}")
                adLoadState = AdLoadState.LOADED
                targetAdView.visibility = View.VISIBLE
            }

            override fun onAdFailedToLoad(adError: LoadAdError) {
                Log.e("AdMob", "Ad failed to load in ${this@BaseAdFragment.javaClass.simpleName}: ${adError.message} (Code: ${adError.code})")
                adLoadState = AdLoadState.FAILED
                targetAdView.visibility = View.GONE
            }
        }
        targetAdView.loadAd(adRequest)
    }

    override fun onResume() {
        super.onResume()
        val currentContext = context
        if (currentContext != null) {
            val consentManager = GoogleMobileAdsConsentManager.getInstance(currentContext)
            if (!consentManager.canRequestAds) {
                // Consent revoked or unavailable: hide ad view and reset state
                adView?.visibility = View.GONE
                adLoadState = AdLoadState.IDLE
            } else if (adLoadState == AdLoadState.FAILED || adLoadState == AdLoadState.IDLE) {
                // Retry failed ad or load idle ad if consent and initialization now allow it
                if (MobileAdsManager.isInitialized()) {
                    attemptLoadBannerAd()
                }
            }
        }
        adView?.resume()
    }

    override fun onPause() {
        super.onPause()
        adView?.pause()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // Unregister initialization listener to prevent callbacks to destroyed view
        initRegistration?.remove()
        initRegistration = null
        adLoadState = AdLoadState.IDLE
        adView?.destroy()
    }

    @VisibleForTesting
    internal fun getAdLoadState(): AdLoadState = adLoadState

    @VisibleForTesting
    internal fun setAdLoadStateForTesting(state: AdLoadState) {
        adLoadState = state
    }
}