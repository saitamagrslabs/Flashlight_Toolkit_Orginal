package com.saitamagrs.flashnow.utils

import android.app.Activity
import android.content.Context
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.FormError
import com.google.android.ump.UserMessagingPlatform

/**
 * Encapsulates the Google User Messaging Platform (UMP) SDK consent flow.
 *
 * Implements Google's official recommended pattern for:
 * 1. Requesting consent information updates
 * 2. Loading and displaying consent forms when required
 * 3. Gating ad requests via canRequestAds
 * 4. Displaying privacy options / consent revocation forms from Settings
 */
class GoogleMobileAdsConsentManager private constructor(context: Context) {

    private val consentInformation: ConsentInformation? = try {
        UserMessagingPlatform.getConsentInformation(context.applicationContext)
    } catch (_: Throwable) {
        null
    }

    /**
     * Determines whether the app can request ads based on the user's consent status.
     * True if consent has been obtained or if consent is not required (e.g., non-EEA).
     */
    val canRequestAds: Boolean
        get() = testCanRequestAds ?: (consentInformation?.canRequestAds() ?: false)

    /**
     * Determines whether privacy options (consent management entry point) are required
     * to be shown to the user (e.g., users in the EEA/UK under IAB TCF requirements).
     */
    val isPrivacyOptionsRequired: Boolean
        get() = testPrivacyOptionsRequired ?: (consentInformation?.privacyOptionsRequirementStatus ==
                ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED)

    /**
     * Requests consent information updates and presents the consent form if required.
     */
    fun gatherConsent(
        activity: Activity,
        onConsentGatheringCompleteListener: (FormError?) -> Unit
    ) {
        val params = ConsentRequestParameters.Builder()
            .setTagForUnderAgeOfConsent(false)
            .build()

        val info = consentInformation ?: run {
            onConsentGatheringCompleteListener(null)
            return
        }

        info.requestConsentInfoUpdate(
            activity,
            params,
            {
                UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) { formError ->
                    onConsentGatheringCompleteListener(formError)
                }
            },
            { requestConsentError ->
                onConsentGatheringCompleteListener(requestConsentError)
            }
        )
    }

    /**
     * Presents the privacy options form allowing the user to update their consent choices.
     */
    fun showPrivacyOptionsForm(
        activity: Activity,
        onConsentFormDismissedListener: (FormError?) -> Unit
    ) {
        UserMessagingPlatform.showPrivacyOptionsForm(activity) { formError ->
            onConsentFormDismissedListener(formError)
        }
    }

    companion object {
        @Volatile
        private var instance: GoogleMobileAdsConsentManager? = null

        @androidx.annotation.VisibleForTesting
        internal var testCanRequestAds: Boolean? = null

        @androidx.annotation.VisibleForTesting
        internal var testPrivacyOptionsRequired: Boolean? = null

        fun getInstance(context: Context): GoogleMobileAdsConsentManager =
            instance ?: synchronized(this) {
                instance ?: GoogleMobileAdsConsentManager(context).also { instance = it }
            }

        @androidx.annotation.VisibleForTesting
        internal fun resetForTesting() {
            synchronized(this) {
                instance = null
                testCanRequestAds = null
                testPrivacyOptionsRequired = null
            }
        }
    }
}
