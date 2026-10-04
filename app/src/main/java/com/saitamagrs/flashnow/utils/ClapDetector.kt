package com.saitamagrs.flashnow.utils

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import kotlin.concurrent.thread
import kotlin.math.abs

/**
 * Low-level audio DSP engine for detecting clap sounds using the microphone.
 * Encapsulates AudioRecord, background worker thread, dynamic noise averaging,
 * sensitivity-adjusted threshold calculation, and cooldown timing.
 */
class ClapDetector(
    private val context: Context,
    private val onClapDetected: () -> Unit
) {
    companion object {
        private const val TAG = "ClapDetector"
        private const val SAMPLE_RATE = 44100
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val COOLDOWN_MS = 1200L
        private const val MAX_NOISE_HISTORY = 50
        private const val MIN_CALIBRATION_BUFFERS = 5

        // Settings seekbar range: 0..25000 (lower values = more sensitive)
        private const val MIN_SEEKBAR_VALUE = 0
        private const val MAX_SEEKBAR_VALUE = 25000
        private const val DEFAULT_SEEKBAR_VALUE = 9000

        // Dynamic multiplier range relative to ambient noise floor
        private const val MIN_MULTIPLIER = 4.5    // At max sensitivity (100%)
        private const val MAX_MULTIPLIER = 14.0   // At min sensitivity (0%)

        // Minimum amplitude floor range in 16-bit PCM units [0..32767]
        private const val MIN_AMPLITUDE_FLOOR = 1800.0  // At max sensitivity (100%)
        private const val MAX_AMPLITUDE_FLOOR = 22000.0 // At min sensitivity (0%)

        // Ambient noise sample cap to prevent loud transient spikes from contaminating the baseline
        private const val MAX_AMBIENT_NOISE_SAMPLE = 3500.0
    }

    @Volatile
    private var isListeningForClaps = false
    private var audioRecord: AudioRecord? = null
    private var audioThread: Thread? = null
    private var activeSessionId: Long = 0L

    private var isClapOnCooldown = false
    private val noiseHistory = mutableListOf<Double>()

    // Sensitivity-derived thresholds (dynamically calculated)
    private var currentClapMultiplier = 8.0
    private var currentMinAmplitudeFloor = 9000.0

    private val handler = Handler(Looper.getMainLooper())
    private val lock = Any()
    @Volatile
    private var isReleased = false

    val isListening: Boolean
        get() = synchronized(lock) { isListeningForClaps && !isReleased }

    /**
     * Starts listening for clap sounds on a background worker thread.
     * Safe to call repeatedly; will no-op if already listening or released.
     */
    fun startListening() {
        synchronized(lock) {
            if (isReleased) {
                Log.w(TAG, "Cannot start: ClapDetector has been released")
                return
            }
            if (isListeningForClaps) return

            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "Cannot start clap detection: Permission not granted")
                return
            }

            loadSensitivityParameters()

            isListeningForClaps = true
            val currentSession = ++activeSessionId
            noiseHistory.clear()
            Log.d(TAG, "Clap detection starting (session $currentSession) - Multiplier: $currentClapMultiplier, MinFloor: $currentMinAmplitudeFloor")

            audioThread = thread(name = "ClapDetectionThread-$currentSession", start = true) {
                runAudioLoop(currentSession)
            }
        }
    }

    /**
     * Reads the sensitivity preference from Settings and calculates the dynamic
     * multiplier and minimum amplitude floor.
     *
     * In Settings:
     * - Progress ranges from 0 to 25000 (default 9000).
     * - Lower progress = "More Sensitive" (quieter claps trigger).
     * - Higher progress = "Less Sensitive" (louder claps trigger).
     */
    private fun loadSensitivityParameters() {
        val sharedPreferences = context.getSharedPreferences(AppConstants.PREFS_NAME, Context.MODE_PRIVATE)
        val rawPref = sharedPreferences.getInt(AppConstants.KEY_SENSITIVITY, DEFAULT_SEEKBAR_VALUE)
        val clampedPref = rawPref.coerceIn(MIN_SEEKBAR_VALUE, MAX_SEEKBAR_VALUE)

        // sensitivityRatio in [0.0, 1.0]: 1.0 = most sensitive (progress 0), 0.0 = least sensitive (progress 25000)
        val sensitivityRatio = (MAX_SEEKBAR_VALUE - clampedPref).toDouble() / (MAX_SEEKBAR_VALUE - MIN_SEEKBAR_VALUE)

        // Interpolate multiplier: lower multiplier when more sensitive
        currentClapMultiplier = MAX_MULTIPLIER - (MAX_MULTIPLIER - MIN_MULTIPLIER) * sensitivityRatio

        // Interpolate minimum amplitude floor: lower floor when more sensitive
        currentMinAmplitudeFloor = MAX_AMPLITUDE_FLOOR - (MAX_AMPLITUDE_FLOOR - MIN_AMPLITUDE_FLOOR) * sensitivityRatio
    }

    private fun runAudioLoop(sessionId: Long) {
        val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        if (minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
            Log.e(TAG, "AudioRecord.getMinBufferSize failed with error: $minBufferSize")
            synchronized(lock) {
                if (activeSessionId == sessionId) {
                    isListeningForClaps = false
                }
            }
            return
        }

        var record: AudioRecord? = null
        try {
            val buffer = ShortArray(minBufferSize)
            val newRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                minBufferSize
            )

            synchronized(lock) {
                if (!isListeningForClaps || isReleased || activeSessionId != sessionId) {
                    try { newRecord.release() } catch (e: Exception) {}
                    return
                }
                record = newRecord
                audioRecord = newRecord
            }

            if (newRecord.state == AudioRecord.STATE_INITIALIZED) {
                try {
                    newRecord.startRecording()
                } catch (e: IllegalStateException) {
                    Log.e(TAG, "AudioRecord.startRecording failed", e)
                    synchronized(lock) {
                        if (activeSessionId == sessionId) isListeningForClaps = false
                    }
                    return
                }

                while (isListeningForClaps && !isReleased && activeSessionId == sessionId) {
                    val readBytes = try {
                        newRecord.read(buffer, 0, minBufferSize)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error reading from AudioRecord", e)
                        -1
                    }
                    if (readBytes > 0) {
                        processAudioBuffer(buffer, sessionId)
                    } else if (readBytes < 0) {
                        Log.w(TAG, "AudioRecord read returned error code: $readBytes")
                        break
                    }
                }
            } else {
                Log.e(TAG, "AudioRecord failed to initialize: state=${newRecord.state}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in audio thread (session $sessionId)", e)
        } finally {
            synchronized(lock) {
                try {
                    record?.stop()
                } catch (e: Exception) {}
                try {
                    record?.release()
                } catch (e: Exception) {}

                if (activeSessionId == sessionId) {
                    audioRecord = null
                    audioThread = null
                    isListeningForClaps = false
                }
            }
            Log.d(TAG, "ClapDetectionThread finished (session $sessionId)")
        }
    }

    /**
     * Stops listening for claps and releases AudioRecord.
     * Safe to call multiple times or when not listening.
     */
    fun stopListening() {
        synchronized(lock) {
            isListeningForClaps = false
            activeSessionId++
            try {
                audioRecord?.stop()
            } catch (e: Exception) {}
            try {
                audioRecord?.release()
            } catch (e: Exception) {}
            audioRecord = null
            audioThread = null
        }
        Log.d(TAG, "Clap detection stopped")
    }

    /**
     * Releases all resources, cancels pending cooldown callbacks,
     * and guarantees no further callbacks are emitted.
     */
    fun release() {
        synchronized(lock) {
            isReleased = true
            isListeningForClaps = false
            activeSessionId++
            try {
                audioRecord?.stop()
            } catch (e: Exception) {}
            try {
                audioRecord?.release()
            } catch (e: Exception) {}
            audioRecord = null
            audioThread = null
            handler.removeCallbacksAndMessages(null)
            noiseHistory.clear()
        }
        Log.d(TAG, "ClapDetector released")
    }

    private fun processAudioBuffer(buffer: ShortArray, sessionId: Long) {
        var peakAmplitude = 0.0
        for (s in buffer) {
            peakAmplitude = maxOf(peakAmplitude, abs(s.toDouble()))
        }

        synchronized(lock) {
            if (activeSessionId != sessionId || !isListeningForClaps || isReleased) return

            // Initial calibration: establish baseline ambient noise floor without letting loud spikes pollute it
            if (noiseHistory.size < MIN_CALIBRATION_BUFFERS) {
                val safeSample = minOf(peakAmplitude, MAX_AMBIENT_NOISE_SAMPLE)
                noiseHistory.add(safeSample)
                return
            }

            val currentNoiseFloor = if (noiseHistory.isEmpty()) 200.0 else maxOf(noiseHistory.average(), 150.0)
            val noiseThreshold = currentNoiseFloor * currentClapMultiplier
            val effectiveThreshold = maxOf(noiseThreshold, currentMinAmplitudeFloor)

            val isClap = peakAmplitude > effectiveThreshold

            if (isClap && !isClapOnCooldown) {
                isClapOnCooldown = true
                handler.postDelayed({
                    synchronized(lock) {
                        isClapOnCooldown = false
                    }
                }, COOLDOWN_MS)

                handler.post {
                    synchronized(lock) {
                        if (!isReleased && isListeningForClaps && activeSessionId == sessionId) {
                            onClapDetected()
                        }
                    }
                }
            } else if (!isClap && peakAmplitude < currentMinAmplitudeFloor * 0.75) {
                // Update ambient noise history dynamically with non-clap background samples
                val safeSample = minOf(peakAmplitude, MAX_AMBIENT_NOISE_SAMPLE)
                noiseHistory.add(safeSample)
                if (noiseHistory.size > MAX_NOISE_HISTORY) {
                    noiseHistory.removeAt(0)
                }
            }
        }
    }
}
