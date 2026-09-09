package com.saitamagrs.flashnow.fragments

import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.fragment.app.Fragment
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.LoadAdError

/**
 * A base fragment that handles the common logic for loading and managing a banner ad.
 * Child fragments must provide their own AdView instance from their binding.
 */
abstract class BaseAdFragment : Fragment() {

    /**
     * The AdView from the child fragment's layout binding.
     * Must be implemented by subclasses.
     */
    protected abstract val adView: AdView?

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        loadBannerAd()
    }

    private fun loadBannerAd() {
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
        // If the ad failed to load previously (e.g., no network), try again when the user returns to the fragment.
        if (adView?.visibility == View.GONE) {
            loadBannerAd()
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