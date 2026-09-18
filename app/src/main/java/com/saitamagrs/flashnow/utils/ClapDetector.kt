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
 * threshold calculation, and cooldown timing.
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
        private const val CLAP_MULTIPLIER = 15.0
        private const val DEFAULT_MIN_AMPLITUDE_THRESHOLD = 9000
        private const val COOLDOWN_MS = 1200L
        private const val MAX_NOISE_HISTORY = 50
    }

    @Volatile
    private var isListeningForClaps = false
    private var audioRecord: AudioRecord? = null
    private var audioThread: Thread? = null

    private var isClapOnCooldown = false
    private val noiseHistory = mutableListOf<Double>()
    private var minAmplitudeThreshold = DEFAULT_MIN_AMPLITUDE_THRESHOLD

    private val handler = Handler(Looper.getMainLooper())
    private val lock = Any()
    @Volatile
    private var isReleased = false

    val isListening: Boolean
        get() = isListeningForClaps

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

            val sharedPreferences = context.getSharedPreferences(AppConstants.PREFS_NAME, Context.MODE_PRIVATE)
            minAmplitudeThreshold = sharedPreferences.getInt(AppConstants.KEY_SENSITIVITY, DEFAULT_MIN_AMPLITUDE_THRESHOLD)

            isListeningForClaps = true
            Log.d(TAG, "Clap detection starting with threshold: $minAmplitudeThreshold")

            audioThread = thread(name = "ClapDetectionThread", start = true) {
                val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
                if (minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
                    Log.e(TAG, "AudioRecord.getMinBufferSize failed with error: $minBufferSize")
                    isListeningForClaps = false
                    return@thread
                }

                try {
                    val buffer = ShortArray(minBufferSize)
                    val record = AudioRecord(
                        MediaRecorder.AudioSource.MIC,
                        SAMPLE_RATE,
                        CHANNEL_CONFIG,
                        AUDIO_FORMAT,
                        minBufferSize
                    )
                    synchronized(lock) {
                        if (!isListeningForClaps || isReleased) {
                            try {
                                record.release()
                            } catch (e: Exception) {
                                Log.e(TAG, "Error releasing early AudioRecord", e)
                            }
                            return@thread
                        }
                        audioRecord = record
                    }

                    if (record.state == AudioRecord.STATE_INITIALIZED) {
                        try {
                            record.startRecording()
                        } catch (e: IllegalStateException) {
                            Log.e(TAG, "AudioRecord.startRecording failed", e)
                            isListeningForClaps = false
                            return@thread
                        }

                        while (isListeningForClaps && !isReleased) {
                            val readBytes = try {
                                record.read(buffer, 0, minBufferSize)
                            } catch (e: Exception) {
                                Log.e(TAG, "Error reading from AudioRecord", e)
                                -1
                            }
                            if (readBytes > 0) {
                                processAudioBuffer(buffer)
                            } else if (readBytes < 0) {
                                Log.w(TAG, "AudioRecord read returned error code: $readBytes")
                                break
                            }
                        }
                    } else {
                        Log.e(TAG, "AudioRecord failed to initialize: state=${record.state}")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in audio thread", e)
                } finally {
                    synchronized(lock) {
                        try {
                            audioRecord?.stop()
                        } catch (e: Exception) {
                            Log.e(TAG, "Error stopping audio record in worker thread", e)
                        }
                        try {
                            audioRecord?.release()
                        } catch (e: Exception) {
                            Log.e(TAG, "Error releasing audio record in worker thread", e)
                        }
                        audioRecord = null
                        isListeningForClaps = false
                    }
                    Log.d(TAG, "ClapDetectionThread finished")
                }
            }
        }
    }

    /**
     * Stops listening for claps and releases AudioRecord.
     * Safe to call multiple times or when not listening.
     */
    fun stopListening() {
        synchronized(lock) {
            isListeningForClaps = false
            try {
                audioRecord?.stop()
            } catch (e: Exception) {
                // Ignore if already stopped or uninitialized
            }
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
            try {
                audioRecord?.stop()
            } catch (e: Exception) {
                // Ignore
            }
            try {
                audioRecord?.release()
            } catch (e: Exception) {
                // Ignore
            }
            audioRecord = null
            audioThread = null
            handler.removeCallbacksAndMessages(null)
            noiseHistory.clear()
        }
        Log.d(TAG, "ClapDetector released")
    }

    private fun processAudioBuffer(buffer: ShortArray) {
        var peakAmplitude = 0.0
        for (s in buffer) {
            peakAmplitude = maxOf(peakAmplitude, abs(s.toDouble()))
        }

        val averageNoise = if (noiseHistory.isEmpty()) 0.0 else noiseHistory.average()
        val isClap = peakAmplitude > averageNoise * CLAP_MULTIPLIER && peakAmplitude > minAmplitudeThreshold

        if (isClap && !isClapOnCooldown) {
            isClapOnCooldown = true
            handler.postDelayed({ isClapOnCooldown = false }, COOLDOWN_MS)

            // Notify callback on main thread if not released
            handler.post {
                if (!isReleased) {
                    onClapDetected()
                }
            }
        } else if (peakAmplitude < averageNoise * 1.5) {
            noiseHistory.add(peakAmplitude)
            if (noiseHistory.size > MAX_NOISE_HISTORY) {
                noiseHistory.removeAt(0)
            }
        }
    }
}
