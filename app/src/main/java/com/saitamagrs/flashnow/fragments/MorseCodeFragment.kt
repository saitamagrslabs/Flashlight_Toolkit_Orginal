package com.saitamagrs.flashnow.fragments

import android.Manifest
import android.content.res.ColorStateList
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.setMargins
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.ads.AdView
import com.google.android.material.card.MaterialCardView
import com.saitamagrs.flashnow.MainActivity
import com.saitamagrs.flashnow.R
import com.saitamagrs.flashnow.databinding.FragmentMorseCodeBinding
import com.saitamagrs.flashnow.morse.core.MorseCodec
import com.saitamagrs.flashnow.morse.core.MorseProtocol
import com.saitamagrs.flashnow.morse.correction.MorseEnglishCorrector
import com.saitamagrs.flashnow.morse.receiver.CameraStartupCoordinator
import com.saitamagrs.flashnow.morse.receiver.MorseReceiverEngine
import com.saitamagrs.flashnow.morse.sender.MorseSenderEngine
import com.saitamagrs.flashnow.utils.FlashlightController
import com.saitamagrs.flashnow.utils.MorseCodeManager
import com.saitamagrs.flashnow.utils.PermissionManager
import android.content.Context
import androidx.appcompat.app.AlertDialog
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MorseCodeFragment : BaseAdFragment() {

    companion object {
        private const val TAG = "MorseCodeFragment"
        private const val PREFS_MORSE = "flashnow_morse_prefs"
        private const val KEY_AUTO_CORRECTION = "morse_auto_correction_enabled"
    }

    private var _binding: FragmentMorseCodeBinding? = null
    private val binding get() = _binding!!
    override val adView: AdView? get() = binding.adView

    private lateinit var morseCodeManager: MorseCodeManager
    private lateinit var morseSenderEngine: MorseSenderEngine
    private lateinit var morseEnglishCorrector: MorseEnglishCorrector
    private lateinit var morseReceiverEngine: MorseReceiverEngine
    private val startupCoordinator = CameraStartupCoordinator()

    private var cameraProvider: ProcessCameraProvider? = null
    private var cameraExecutor: ExecutorService? = null
    private var hasCameraPermission = false
    private var isReceiverActive = false
    private var isAutoCorrectionEnabled = false
    private var tipsDialog: AlertDialog? = null

    private val requestCameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isAdded || _binding == null || isDetached) return@registerForActivityResult
        hasCameraPermission = isGranted
        if (isGranted) {
            if (binding.containerReceiver.visibility == View.VISIBLE) {
                startReceiver()
            }
        } else {
            startupCoordinator.invalidate()
            if (!shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
                PermissionManager.showPermanentlyDeniedDialog(requireContext(), Manifest.permission.CAMERA)
            } else {
                Toast.makeText(requireContext(), "Camera permission is required for the optical receiver.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentMorseCodeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, 0)
            insets
        }

        ViewCompat.setOnApplyWindowInsetsListener(binding.adView) { adView, insets ->
            val navigationBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            adView.updatePadding(bottom = navigationBars.bottom)
            insets
        }

        val flashlightController = FlashlightController(requireContext())
        morseCodeManager = MorseCodeManager(flashlightController)
        morseSenderEngine = MorseSenderEngine(requireContext())
        morseEnglishCorrector = MorseEnglishCorrector.fromAssets(requireContext().applicationContext)
        morseReceiverEngine = MorseReceiverEngine()
        morseReceiverEngine.englishCorrector = morseEnglishCorrector

        binding.btnReceiverTips.setOnClickListener {
            showReceiverTipsDialog()
        }

        setupModeToggle()
        setupSenderUI()
        setupReceiverUI()
        observeSenderEngine()
        observeReceiverEngine()
        populateEmergencySignalsGrid()
        setupMorsePreview()

        (activity as? MainActivity)?.setToolbarTitle("Morse Communicator")
    }

    private fun setupModeToggle() {
        binding.toggleModeGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                when (checkedId) {
                    R.id.btn_mode_sender -> {
                        stopReceiver()
                        binding.containerSender.visibility = View.VISIBLE
                        binding.containerReceiver.visibility = View.GONE
                    }
                    R.id.btn_mode_receiver -> {
                        morseSenderEngine.stopTransmission()
                        morseCodeManager.stopFlashing()
                        binding.containerSender.visibility = View.GONE
                        binding.containerReceiver.visibility = View.VISIBLE
                    }
                }
            }
        }
    }

    // ================= SENDER LOGIC =================

    private fun setupSenderUI() {
        binding.btnSendCustom.setOnClickListener {
            if (!hasCameraPermission) {
                Toast.makeText(requireContext(), "Camera permission required", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (morseSenderEngine.isTransmitting.value) return@setOnClickListener

            val message = binding.etCustomMessage.text.toString().trim()
            if (message.isNotEmpty()) {
                morseSenderEngine.startTransmission(message)
            } else {
                Toast.makeText(requireContext(), "Enter a message", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnStop.setOnClickListener {
            morseSenderEngine.stopTransmission()
            morseCodeManager.stopFlashing()
        }
    }

    private fun getThemeColor(attrRes: Int): Int {
        val typedValue = android.util.TypedValue()
        requireContext().theme.resolveAttribute(attrRes, typedValue, true)
        return typedValue.data
    }

    private fun observeSenderEngine() {
        viewLifecycleOwner.lifecycleScope.launch {
            morseSenderEngine.isTransmitting.collectLatest { isTransmitting ->
                if (isTransmitting) {
                    binding.tvStatus.text = "Transmitting Optical Morse..."
                    binding.ivStatus.setImageResource(R.drawable.ic_power_on)
                    binding.ivStatus.setColorFilter(getThemeColor(R.attr.fnAccentGreen))
                    binding.btnStop.visibility = View.VISIBLE
                    setSenderControlsEnabled(false)
                } else {
                    binding.tvStatus.text = morseSenderEngine.statusText.value
                    binding.ivStatus.setImageResource(R.drawable.ic_power_off)
                    binding.ivStatus.setColorFilter(getThemeColor(R.attr.fnTextSecondary))
                    binding.btnStop.visibility = View.GONE
                    setSenderControlsEnabled(true)
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            morseSenderEngine.statusText.collectLatest { status ->
                if (!morseSenderEngine.isTransmitting.value) {
                    binding.tvStatus.text = status
                }
            }
        }
    }

    private fun setSenderControlsEnabled(enabled: Boolean) {
        for (i in 0 until binding.gridEmergencySignals.childCount) {
            binding.gridEmergencySignals.getChildAt(i).isEnabled = enabled
        }
        binding.btnSendCustom.isEnabled = enabled
        binding.btnSendCustom.alpha = if (enabled) 1.0f else 0.5f
    }

    private fun setupMorsePreview() {
        binding.etCustomMessage.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val text = s?.toString()?.trim()
                if (!text.isNullOrEmpty()) {
                    val morse = MorseCodec.encode(text)
                    binding.tvMorsePreview.text = "Protocol Frame: [Preamble '...'] -> $morse"
                } else {
                    binding.tvMorsePreview.text = "Morse code will appear here"
                }
            }
        })
    }

    private fun populateEmergencySignalsGrid() {
        val gridLayout = binding.gridEmergencySignals
        val signals = morseCodeManager.predefinedSignals

        for ((signalName, morseCode) in signals) {
            val cardView = LayoutInflater.from(requireContext()).inflate(R.layout.item_emergency_signal, gridLayout, false) as MaterialCardView
            val signalNameTextView = cardView.findViewById<TextView>(R.id.tv_signal_name)
            val morseCodeTextView = cardView.findViewById<TextView>(R.id.tv_morse_code)

            signalNameTextView.text = signalName
            morseCodeTextView.text = morseCode

            cardView.setOnClickListener {
                if (!hasCameraPermission) {
                    Toast.makeText(requireContext(), "Camera permission required", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                morseSenderEngine.startTransmission(signalName)
            }

            val params = GridLayout.LayoutParams().apply {
                width = 0
                height = GridLayout.LayoutParams.WRAP_CONTENT
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                rowSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setMargins(8)
            }
            gridLayout.addView(cardView, params)
        }
    }

    // ================= RECEIVER LOGIC =================

    private fun setupReceiverUI() {
        val prefs = requireContext().getSharedPreferences(PREFS_MORSE, Context.MODE_PRIVATE)
        isAutoCorrectionEnabled = prefs.getBoolean(KEY_AUTO_CORRECTION, false)
        binding.switchAutoCorrection.isChecked = isAutoCorrectionEnabled
        binding.switchAutoCorrection.setOnCheckedChangeListener { _, isChecked ->
            isAutoCorrectionEnabled = isChecked
            prefs.edit().putBoolean(KEY_AUTO_CORRECTION, isChecked).apply()
            updateDecodedMessageDisplay()
        }

        binding.tvReceiverSync.text = "Waiting for signal"
        binding.tvReceiverSync.setTextColor(getThemeColor(R.attr.fnTextSecondary))
        binding.tvDecodedMessage.text = "[Decoded text will appear here]"
        binding.tvDetectedSymbols.text = "Current Symbols: -"
        binding.tvLumaMetrics.text = "Luma: 0.0 | Thresh: 0.0 | Light: OFF"
        binding.tvReceiverDebug.text = "Debug: Receiver ready"
        binding.btnToggleReceiver.text = "START RECEIVER"
        binding.btnToggleReceiver.isEnabled = true
        binding.btnToggleReceiver.backgroundTintList = ColorStateList.valueOf(getThemeColor(R.attr.fnAccentGreen))

        binding.btnToggleReceiver.setOnClickListener {
            if (isReceiverActive) {
                stopReceiver()
            } else {
                startReceiver()
            }
        }

        binding.btnResetReceiver.setOnClickListener {
            morseReceiverEngine.reset()
            updateDecodedMessageDisplay()
        }
    }

    private fun startReceiver() {
        if (!PermissionManager.isCameraGranted(requireContext())) {
            hasCameraPermission = false
            startupCoordinator.invalidate()
            requestCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            return
        }
        hasCameraPermission = true
        isReceiverActive = true
        morseReceiverEngine.reset()

        binding.btnToggleReceiver.text = "STOP RECEIVER"
        binding.btnToggleReceiver.backgroundTintList = ColorStateList.valueOf(getThemeColor(R.attr.fnAccentRed))
        binding.tvReceiverSync.text = "Starting camera..."
        binding.tvReceiverSync.setTextColor(getThemeColor(R.attr.fnTextSecondary))

        val currentGeneration = startupCoordinator.startNewAttempt()

        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener({
            if (!startupCoordinator.isAttemptValid(
                    generation = currentGeneration,
                    isSessionActive = isReceiverActive,
                    isViewValid = isAdded && _binding != null && !isDetached
                )
            ) {
                return@addListener
            }

            var localExecutor: ExecutorService? = null
            try {
                val provider = cameraProviderFuture.get()

                if (!startupCoordinator.isAttemptValid(
                        generation = currentGeneration,
                        isSessionActive = isReceiverActive,
                        isViewValid = isAdded && _binding != null && !isDetached
                    )
                ) {
                    return@addListener
                }

                provider.unbindAll()

                val cameraSelector = if (provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) {
                    CameraSelector.DEFAULT_BACK_CAMERA
                } else if (provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)) {
                    CameraSelector.DEFAULT_FRONT_CAMERA
                } else {
                    Toast.makeText(requireContext(), "No camera available on device", Toast.LENGTH_SHORT).show()
                    stopReceiver()
                    return@addListener
                }

                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(binding.viewFinderReceiver.surfaceProvider)
                }

                val imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                localExecutor = Executors.newSingleThreadExecutor()
                imageAnalysis.setAnalyzer(localExecutor, morseReceiverEngine)

                if (!startupCoordinator.isAttemptValid(
                        generation = currentGeneration,
                        isSessionActive = isReceiverActive,
                        isViewValid = isAdded && _binding != null && !isDetached
                    )
                ) {
                    localExecutor.shutdown()
                    return@addListener
                }

                provider.bindToLifecycle(
                    viewLifecycleOwner,
                    cameraSelector,
                    preview,
                    imageAnalysis
                )

                cameraExecutor?.shutdown()
                cameraExecutor = localExecutor
                this@MorseCodeFragment.cameraProvider = provider
                binding.tvReceiverSync.text = "Waiting for signal..."
                binding.tvReceiverSync.setTextColor(getThemeColor(R.attr.fnAccentGreen))
            } catch (e: Exception) {
                Log.e(TAG, "Camera initialization failed", e)
                localExecutor?.shutdown()
                if (cameraExecutor == localExecutor) {
                    cameraExecutor = null
                }
                stopReceiver()
                Toast.makeText(requireContext(), "Camera error: ${e.localizedMessage ?: "Unknown error"}", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    private fun stopReceiver() {
        startupCoordinator.invalidate()
        isReceiverActive = false
        morseReceiverEngine.reset()
        try {
            cameraProvider?.unbindAll()
        } catch (e: Exception) {
            Log.e(TAG, "Error unbinding camera provider", e)
        }
        cameraProvider = null

        try {
            cameraExecutor?.shutdown()
        } catch (e: Exception) {
            Log.e(TAG, "Error shutting down camera executor", e)
        }
        cameraExecutor = null

        if (_binding != null) {
            binding.btnToggleReceiver.text = "START RECEIVER"
            binding.btnToggleReceiver.backgroundTintList = ColorStateList.valueOf(getThemeColor(R.attr.fnAccentGreen))
            binding.tvReceiverSync.text = "Receiver Stopped"
            binding.tvReceiverSync.setTextColor(getThemeColor(R.attr.fnTextSecondary))
            binding.tvLumaMetrics.text = "Luma: 0.0 | Thresh: 0.0 | Light: OFF"
            binding.tvReceiverDebug.text = "Debug: Receiver ready"
            updateDecodedMessageDisplay()
        }
    }

    private fun updateDecodedMessageDisplay() {
        if (_binding == null) return
        val raw = morseReceiverEngine.rawDecodedText.value
        val isFinal = morseReceiverEngine.sessionState.value == MorseReceiverEngine.SessionState.WAITING ||
                morseReceiverEngine.sessionState.value == MorseReceiverEngine.SessionState.READY
        val textToDisplay = if (raw.isBlank()) {
            "[Decoded text will appear here]"
        } else if (isAutoCorrectionEnabled) {
            morseEnglishCorrector.autoCorrectMessage(raw, isFinal)
        } else {
            raw
        }
        binding.tvDecodedMessage.text = textToDisplay
    }

    private fun observeReceiverEngine() {
        viewLifecycleOwner.lifecycleScope.launch {
            combine(morseReceiverEngine.sessionState, morseReceiverEngine.preambleDetected) { session, synced ->
                Pair(session, synced)
            }.collectLatest { (session, synced) ->
                when (session) {
                    MorseReceiverEngine.SessionState.WAITING -> {
                        if (isReceiverActive) {
                            binding.tvReceiverSync.text = "Waiting for signal..."
                            binding.tvReceiverSync.setTextColor(getThemeColor(R.attr.fnAccentGreen))
                        } else {
                            binding.tvReceiverSync.text = "Receiver Stopped"
                            binding.tvReceiverSync.setTextColor(getThemeColor(R.attr.fnTextSecondary))
                        }
                    }
                    MorseReceiverEngine.SessionState.RECEIVING -> {
                        if (synced) {
                            binding.tvReceiverSync.text = "Receiving (Synced '...')"
                            binding.tvReceiverSync.setTextColor(getThemeColor(R.attr.fnAccentGreen))
                        } else {
                            binding.tvReceiverSync.text = "Syncing preamble..."
                            binding.tvReceiverSync.setTextColor(getThemeColor(R.attr.fnPrimaryBlue))
                        }
                    }
                    MorseReceiverEngine.SessionState.ENDING -> {
                        binding.tvReceiverSync.text = "Ending transmission..."
                        binding.tvReceiverSync.setTextColor(getThemeColor(R.attr.fnAccentGreen))
                    }
                    MorseReceiverEngine.SessionState.READY -> {
                        binding.tvReceiverSync.text = "Ready (Armed)"
                        binding.tvReceiverSync.setTextColor(getThemeColor(R.attr.fnAccentGreen))
                    }
                }
                updateDecodedMessageDisplay()
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            morseReceiverEngine.currentSymbols.collectLatest { symbols ->
                binding.tvDetectedSymbols.text = "Current Symbols: ${symbols.ifEmpty { "-" }}"
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            morseReceiverEngine.rawDecodedText.collectLatest {
                updateDecodedMessageDisplay()
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            morseReceiverEngine.diagnostics.collectLatest { d ->
                if (d.frameWidth > 0) {
                    binding.tvLumaMetrics.text = "Sig:%d dS:%.1f | Th:%.1f/%.1f | Light:%s (#%d)".format(
                        d.candidateLuma,
                        d.signalDelta,
                        d.onThreshold,
                        d.offThreshold,
                        if (d.isLightOn) "ON" else "OFF",
                        d.transitionCount
                    )
                    binding.tvReceiverDebug.text = "%s | Base:%.0f/C:%.0f | ROI:%.0f/%d (%dms)".format(
                        d.receiverSignalState.name,
                        d.ambientBaseline,
                        d.restingContrast,
                        d.roiMeanLuma,
                        d.roiMaxLuma,
                        d.frameIntervalMs
                    )
                } else {
                    binding.tvLumaMetrics.text = "Luma: %.1f | Thresh: %.1f | Light: %s".format(
                        morseReceiverEngine.rawLuminance.value,
                        morseReceiverEngine.threshold.value,
                        if (morseReceiverEngine.isLightOn.value) "ON" else "OFF"
                    )
                    binding.tvReceiverDebug.text = "Source: %s (Contrast: %.1f) | %s".format(
                        morseReceiverEngine.sourceState.value.name,
                        morseReceiverEngine.sourceContrast.value,
                        morseReceiverEngine.debugLog.value
                    )
                }
            }
        }
    }

    private fun showReceiverTipsDialog() {
        val currentContext = context ?: return
        if (tipsDialog?.isShowing == true) return

        val message = buildString {
            append("For best decoding results:\n\n")
            append("1. Keep some distance: Position the transmitting flashlight and receiving camera approximately 1 metre apart. Avoid placing the flashlight too close to the camera, because excessive brightness can saturate the camera sensor and make the light pulses difficult to distinguish.\n\n")
            append("2. Avoid bright environments: Reception may be unreliable in direct sunlight, full daylight, or other brightly lit environments where ambient light overwhelms the transmitted flashes.\n\n")
            append("3. Aim the camera at the light: Keep the transmitting flashlight visible within the receiving camera's view. Avoid moving the camera or flashlight during transmission.\n\n")
            append("4. Reduce reflections and glare: Avoid reflective surfaces, strong light sources behind the transmitter, and other flashing lights that could interfere with detection.\n\n")
            append("5. Keep the light pulses clear: Use a steady flashlight and allow the sender to finish transmitting before judging the decoded result. Avoid interrupting the transmission.\n\n")
            append("6. Check the receiving screen: Make sure the camera preview is active and the receiver has the necessary camera permission. If decoding is unreliable, try adjusting the distance and reducing ambient light.\n\n")
            append("Tip: Start in a dim indoor environment with the flashlight about 1 metre from the camera. If decoding is unreliable, try a slightly different distance and reduce background light.")
        }

        tipsDialog = AlertDialog.Builder(currentContext)
            .setTitle("Morse Receiver Tips")
            .setMessage(message)
            .setPositiveButton("Got it") { dialog, _ ->
                dialog.dismiss()
            }
            .create()

        tipsDialog?.show()
    }

    override fun onResume() {
        super.onResume()
        hasCameraPermission = PermissionManager.isCameraGranted(requireContext())
    }

    override fun onPause() {
        super.onPause()
        stopReceiver()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        tipsDialog?.dismiss()
        tipsDialog = null
        stopReceiver()
        morseSenderEngine.release()
        morseCodeManager.stopFlashing()
        _binding = null
    }
}