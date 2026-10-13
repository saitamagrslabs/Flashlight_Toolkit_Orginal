package com.saitamagrs.flashnow.utils

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.google.android.gms.ads.MobileAds
import java.util.concurrent.atomic.AtomicReference

/**
 * Thread-safe manager responsible for single-initialization of the Google Mobile Ads SDK
 * once user consent has been established or determined not required.
 *
 * Implements a strict 3-state lifecycle:
 * - [State.NOT_STARTED]: Initialization has not yet commenced.
 * - [State.INITIALIZING]: SDK initialization is currently in-flight.
 * - [State.INITIALIZED]: SDK initialization has completed via MobileAds.initialize callback.
 */
object MobileAdsManager {

    private const val TAG = "MobileAdsManager"

    enum class State {
        NOT_STARTED,
        INITIALIZING,
        INITIALIZED
    }

    fun interface ListenerRegistration {
        fun remove()
    }

    private val currentState = AtomicReference(State.NOT_STARTED)
    private val lock = Any()
    private val pendingListeners = mutableListOf<() -> Unit>()

    @VisibleForTesting
    internal var testInitializer: ((Context, () -> Unit) -> Unit)? = null

    /**
     * Checks if the Mobile Ads SDK has completed initialization.
     */
    fun isInitialized(): Boolean = currentState.get() == State.INITIALIZED

    /**
     * Checks if the Mobile Ads SDK is currently initializing.
     */
    fun isInitializing(): Boolean = currentState.get() == State.INITIALIZING

    /**
     * Returns the current initialization state (for verification and testing).
     */
    fun getState(): State = currentState.get()

    /**
     * Initializes the Mobile Ads SDK if [GoogleMobileAdsConsentManager.canRequestAds] is true.
     * Guaranteed to execute SDK initialization at most once per process lifecycle.
     *
     * @param context Application or Activity context.
     * @param onInitialized Optional callback executed once when initialization completes.
     *                      If already initialized and consent is valid, dispatched on the main thread.
     */
    fun initializeIfPermitted(context: Context, onInitialized: (() -> Unit)? = null) {
        val consentManager = GoogleMobileAdsConsentManager.getInstance(context)
        if (!consentManager.canRequestAds) {
            Log.d(TAG, "Mobile Ads initialization skipped: user consent not granted yet")
            return
        }

        val needsInitialization = synchronized(lock) {
            when (currentState.get()) {
                State.INITIALIZED -> {
                    if (onInitialized != null) {
                        postToMainThread(onInitialized)
                    }
                    false
                }
                State.INITIALIZING -> {
                    if (onInitialized != null) {
                        pendingListeners.add(onInitialized)
                    }
                    false
                }
                State.NOT_STARTED -> {
                    currentState.set(State.INITIALIZING)
                    if (onInitialized != null) {
                        pendingListeners.add(onInitialized)
                    }
                    true
                }
            }
        }

        if (needsInitialization) {
            Log.d(TAG, "Initializing Google Mobile Ads SDK")
            val customInitializer = testInitializer
            if (customInitializer != null) {
                customInitializer.invoke(context) {
                    onInitializationCompleted()
                }
            } else {
                try {
                    MobileAds.initialize(context.applicationContext) {
                        onInitializationCompleted()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error initiating MobileAds.initialize", e)
                }
            }
        }
    }

    /**
     * Registers a callback to be notified when Mobile Ads finishes initialization.
     * If already initialized, the callback is dispatched immediately to the main thread.
     *
     * @param listener Callback invoked upon initialization completion.
     * @return A [ListenerRegistration] allowing lifecycle-safe removal (e.g. on onDestroyView).
     */
    fun addInitializationListener(listener: () -> Unit): ListenerRegistration {
        synchronized(lock) {
            if (currentState.get() == State.INITIALIZED) {
                postToMainThread(listener)
                return ListenerRegistration { /* No-op: already dispatched */ }
            } else {
                pendingListeners.add(listener)
                return ListenerRegistration {
                    removeInitializationListener(listener)
                }
            }
        }
    }

    /**
     * Removes a previously registered listener to prevent memory leaks or
     * callbacks to destroyed views.
     */
    fun removeInitializationListener(listener: () -> Unit) {
        synchronized(lock) {
            pendingListeners.remove(listener)
        }
    }

    /**
     * Internal completion handler invoked by the MobileAds.initialize callback.
     * Marks state as INITIALIZED and notifies all queued listeners outside the lock on the main thread.
     */
    internal fun onInitializationCompleted() {
        val callbacks = synchronized(lock) {
            currentState.set(State.INITIALIZED)
            val list = ArrayList(pendingListeners)
            pendingListeners.clear()
            list
        }
        Log.d(TAG, "Google Mobile Ads SDK initialization complete. Dispatching to ${callbacks.size} listeners.")
        postToMainThread {
            callbacks.forEach { callback ->
                try {
                    callback()
                } catch (t: Throwable) {
                    Log.e(TAG, "Exception in initialization listener", t)
                }
            }
        }
    }

    private fun postToMainThread(action: () -> Unit) {
        try {
            val mainLooper = Looper.getMainLooper()
            if (mainLooper != null && Looper.myLooper() != mainLooper) {
                Handler(mainLooper).post(action)
                return
            }
        } catch (_: Throwable) {
            // Fallback for JVM unit tests where Looper may not be mocked
        }
        action()
    }

    @VisibleForTesting
    internal fun resetForTesting() {
        synchronized(lock) {
            currentState.set(State.NOT_STARTED)
            pendingListeners.clear()
            testInitializer = null
        }
    }
}
