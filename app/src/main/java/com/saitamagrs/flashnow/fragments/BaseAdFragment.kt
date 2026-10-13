package com.saitamagrs.flashnow.fragments

import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.fragment.app.Fragment
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.LoadAdError
import com.saitamagrs.flashnow.utils.GoogleMobileAdsConsentManager
import com.saitamagrs.flashnow.utils.MobileAdsManager

/**
 * A base fragment that handles common logic for loading and managing a banner ad,
 * strictly gated by Google UMP consent verification before any ad request is initiated.
 */
abstract class BaseAdFragment : Fragment() {

    /**
     * The AdView from the child fragment's layout binding.
     * Must be implemented by subclasses.
     */
    protected abstract val adView: AdView?

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        attemptLoadBannerAd()

        // If Mobile Ads finishes initialization after this view was created (e.g. initial consent grant),
        // safely trigger the ad request.
        MobileAdsManager.addInitializationListener {
            if (isAdded && !isDetached && this.view != null) {
                activity?.runOnUiThread {
                    if (isAdded && !isDetached && this.view != null && adView?.visibility != View.VISIBLE) {
                        attemptLoadBannerAd()
                    }
                }
            }
        }
    }

    private fun attemptLoadBannerAd() {
        val currentContext = context ?: return
        val consentManager = GoogleMobileAdsConsentManager.getInstance(currentContext)

        // Strict UMP consent check: do not request ads unless canRequestAds returns true
        if (!consentManager.canRequestAds) {
            Log.d("AdMob", "Ad request deferred: user consent not granted yet in ${this.javaClass.simpleName}")
            adView?.visibility = View.GONE
            return
        }

        adView?.let { adView ->
            val adRequest = AdRequest.Builder().build()
            adView.loadAd(adRequest)
            adView.adListener = object : AdListener() {
                override fun onAdLoaded() {
                    Log.d("AdMob", "Ad loaded successfully in ${this@BaseAdFragment.javaClass.simpleName}")
                    adView.visibility = View.VISIBLE
                }

                override fun onAdFailedToLoad(adError: LoadAdError) {
                    Log.e("AdMob", "Ad failed to load in ${this@BaseAdFragment.javaClass.simpleName}: ${adError.message} (Code: ${adError.code})")
                    adView.visibility = View.GONE
                }
            }
        } ?: run {
            Log.w("AdMob", "AdView is null in ${this.javaClass.simpleName}")
        }
    }

    override fun onResume() {
        super.onResume()
        val currentContext = context
        if (currentContext != null) {
            val consentManager = GoogleMobileAdsConsentManager.getInstance(currentContext)
            // If the ad failed or was deferred previously, retry when user returns if consent is valid
            if (consentManager.canRequestAds && adView?.visibility == View.GONE) {
                attemptLoadBannerAd()
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
        adView?.destroy()
    }
}