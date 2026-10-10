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
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.ads.AdView
import com.google.android.material.card.MaterialCardView
import com.saitamagrs.flashnow.MainActivity
import com.saitamagrs.flashnow.R
import com.saitamagrs.flashnow.databinding.FragmentMorseCodeBinding
import com.saitamagrs.flashnow.morse.core.MorseCodec
import com.saitamagrs.flashnow.morse.core.MorseProtocol
import com.saitamagrs.flashnow.morse.correction.MorseEnglishCorrector
import com.saitamagrs.flashnow.morse.detector.OpticalFrameAnalyzer
import com.saitamagrs.flashnow.morse.receiver.CameraStartupCoordinator
import com.saitamagrs.flashnow.morse.receiver.MorseReceiverController
import com.saitamagrs.flashnow.morse.receiver.ReceiverStatus
import com.saitamagrs.flashnow.morse.sender.MorseSenderEngine
import com.saitamagrs.flashnow.utils.FlashlightController
import com.saitamagrs.flashnow.utils.MorseCodeManager
import com.saitamagrs.flashnow.utils.PermissionManager
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MorseCodeFragment : BaseAdFragment() {

    companion object {
        private const val TAG = "MorseCodeFragment"
    }

    private var _binding: FragmentMorseCodeBinding? = null
    private val binding get() = _binding!!
    override val adView: AdView? get() = binding.adView

    private lateinit var morseCodeManager: MorseCodeManager
    private lateinit var morseSenderEngine: MorseSenderEngine
    private lateinit var morseEnglishCorrector: MorseEnglishCorrector
    private val morseReceiverController = MorseReceiverController()
    private val startupCoordinator = CameraStartupCoordinator()

    private var cameraProvider: ProcessCameraProvider? = null
    private var cameraExecutor: ExecutorService? = null
    private var hasCameraPermission = false

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
            morseReceiverController.setStatus(ReceiverStatus.PERMISSION_REQUIRED)
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
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        val flashlightController = FlashlightController(requireContext())
        morseCodeManager = MorseCodeManager(flashlightController)
        morseSenderEngine = MorseSenderEngine(requireContext())
        morseEnglishCorrector = MorseEnglishCorrector.fromAssets(requireContext().applicationContext)

        setupModeToggle()
        setupSenderUI()
        setupReceiverUI()
        observeSenderEngine()
        observeReceiverController()
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
        binding.tvReceiverSync.text = "Receiver Ready"
        binding.tvReceiverSync.setTextColor(getThemeColor(R.attr.fnTextSecondary))
        binding.tvDecodedMessage.text = "[Decoded text will appear here]"
        binding.tvDetectedSymbols.text = "Current Symbols: -"
        binding.tvLumaMetrics.text = "Optical Engine: Standby"
        binding.tvReceiverDebug.text = "Ready to start receiver"
        binding.btnToggleReceiver.text = "START RECEIVER"
        binding.btnToggleReceiver.isEnabled = true
        binding.btnToggleReceiver.backgroundTintList = ColorStateList.valueOf(getThemeColor(R.attr.fnAccentGreen))

        binding.btnToggleReceiver.setOnClickListener {
            if (morseReceiverController.isSessionActive) {
                stopReceiver()
            } else {
                startReceiver()
            }
        }

        binding.btnResetReceiver.setOnClickListener {
            morseReceiverController.clearDecodedText()
            binding.tvDecodedMessage.text = "[Decoded text will appear here]"
            binding.cardSuggestion.visibility = View.GONE
        }

        binding.btnAcceptSuggestion.setOnClickListener {
            val suggestion = binding.tvSuggestedMessage.text.toString()
            if (suggestion.isNotBlank()) {
                binding.tvDecodedMessage.text = suggestion
                binding.cardSuggestion.visibility = View.GONE
            }
        }
    }

    private fun startReceiver() {
        if (!PermissionManager.isCameraGranted(requireContext())) {
            hasCameraPermission = false
            startupCoordinator.invalidate()
            morseReceiverController.setStatus(ReceiverStatus.PERMISSION_REQUIRED)
            requestCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            return
        }
        hasCameraPermission = true

        val currentGeneration = startupCoordinator.startNewAttempt()
        morseReceiverController.setStatus(ReceiverStatus.STARTING)
        morseReceiverController.startSession(viewLifecycleOwner.lifecycleScope)

        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener({
            if (!startupCoordinator.isAttemptValid(
                    generation = currentGeneration,
                    isSessionActive = morseReceiverController.isSessionActive,
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
                        isSessionActive = morseReceiverController.isSessionActive,
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
                    if (startupCoordinator.isAttemptValid(
                            generation = currentGeneration,
                            isSessionActive = morseReceiverController.isSessionActive,
                            isViewValid = isAdded && _binding != null && !isDetached
                        )
                    ) {
                        morseReceiverController.setError("No camera available on device")
                    }
                    return@addListener
                }

                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(binding.viewFinderReceiver.surfaceProvider)
                }

                val imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                localExecutor = Executors.newSingleThreadExecutor()
                val analyzer = OpticalFrameAnalyzer(detector = morseReceiverController.detector)
                imageAnalysis.setAnalyzer(localExecutor, analyzer)

                if (!startupCoordinator.isAttemptValid(
                        generation = currentGeneration,
                        isSessionActive = morseReceiverController.isSessionActive,
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
            } catch (e: Exception) {
                Log.e(TAG, "Camera initialization failed", e)
                localExecutor?.shutdown()
                if (cameraExecutor == localExecutor) {
                    cameraExecutor = null
                }

                if (startupCoordinator.isAttemptValid(
                        generation = currentGeneration,
                        isSessionActive = morseReceiverController.isSessionActive,
                        isViewValid = isAdded && _binding != null && !isDetached
                    )
                ) {
                    morseReceiverController.setError("Camera init error: ${e.localizedMessage ?: "Unknown error"}")
                }
            }
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    private fun stopReceiver() {
        startupCoordinator.invalidate()
        morseReceiverController.stopSession()
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
    }

    private fun observeReceiverController() {
        viewLifecycleOwner.lifecycleScope.launch {
            morseReceiverController.receiverStatus.collectLatest { status ->
                when (status) {
                    ReceiverStatus.STOPPED -> {
                        binding.btnToggleReceiver.text = "START RECEIVER"
                        binding.btnToggleReceiver.backgroundTintList = ColorStateList.valueOf(getThemeColor(R.attr.fnAccentGreen))
                        binding.btnToggleReceiver.isEnabled = true
                        binding.tvReceiverSync.text = "Receiver Stopped"
                        binding.tvReceiverSync.setTextColor(getThemeColor(R.attr.fnTextSecondary))
                    }
                    ReceiverStatus.PERMISSION_REQUIRED -> {
                        binding.btnToggleReceiver.text = "GRANT PERMISSION"
                        binding.btnToggleReceiver.backgroundTintList = ColorStateList.valueOf(getThemeColor(R.attr.fnPrimaryBlue))
                        binding.btnToggleReceiver.isEnabled = true
                        binding.tvReceiverSync.text = "Camera Permission Required"
                        binding.tvReceiverSync.setTextColor(getThemeColor(R.attr.fnAccentRed))
                    }
                    ReceiverStatus.STARTING -> {
                        binding.btnToggleReceiver.text = "STOP RECEIVER"
                        binding.btnToggleReceiver.backgroundTintList = ColorStateList.valueOf(getThemeColor(R.attr.fnAccentRed))
                        binding.btnToggleReceiver.isEnabled = true
                        binding.tvReceiverSync.text = "Starting camera..."
                        binding.tvReceiverSync.setTextColor(getThemeColor(R.attr.fnTextSecondary))
                    }
                    ReceiverStatus.CALIBRATING -> {
                        binding.btnToggleReceiver.text = "STOP RECEIVER"
                        binding.btnToggleReceiver.backgroundTintList = ColorStateList.valueOf(getThemeColor(R.attr.fnAccentRed))
                        binding.btnToggleReceiver.isEnabled = true
                        binding.tvReceiverSync.text = "Calibrating ambient light..."
                        binding.tvReceiverSync.setTextColor(getThemeColor(R.attr.fnPrimaryBlue))
                    }
                    ReceiverStatus.LISTENING -> {
                        binding.btnToggleReceiver.text = "STOP RECEIVER"
                        binding.btnToggleReceiver.backgroundTintList = ColorStateList.valueOf(getThemeColor(R.attr.fnAccentRed))
                        binding.btnToggleReceiver.isEnabled = true
                        binding.tvReceiverSync.text = "Listening for optical signals"
                        binding.tvReceiverSync.setTextColor(getThemeColor(R.attr.fnAccentGreen))
                    }
                    ReceiverStatus.ERROR -> {
                        binding.btnToggleReceiver.text = "RETRY RECEIVER"
                        binding.btnToggleReceiver.backgroundTintList = ColorStateList.valueOf(getThemeColor(R.attr.fnAccentGreen))
                        binding.btnToggleReceiver.isEnabled = true
                        val err = morseReceiverController.errorMessage.value ?: "Camera Error"
                        binding.tvReceiverSync.text = "Error: $err"
                        binding.tvReceiverSync.setTextColor(getThemeColor(R.attr.fnAccentRed))
                    }
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            morseReceiverController.decodedText.collectLatest { text ->
                val displayMsg = if (text.isEmpty()) "[Decoded text will appear here]" else text
                binding.tvDecodedMessage.text = displayMsg

                if (text.isNotBlank()) {
                    val result = morseEnglishCorrector.correct(text)
                    if (result.hasSuggestion && !result.suggestedText.isNullOrBlank()) {
                        binding.cardSuggestion.visibility = View.VISIBLE
                        binding.tvSuggestedMessage.text = result.suggestedText
                    } else {
                        binding.cardSuggestion.visibility = View.GONE
                    }
                } else {
                    binding.cardSuggestion.visibility = View.GONE
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            morseReceiverController.currentSymbols.collectLatest { symbols ->
                binding.tvDetectedSymbols.text = "Current Symbols: ${if (symbols.isEmpty()) "-" else symbols}"
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            morseReceiverController.diagnostics.collectLatest { diag ->
                binding.tvLumaMetrics.text = "Luma: %.1f | Thresh: %.1f | Light: %s".format(
                    diag.measuredLuma,
                    diag.onThreshold,
                    diag.currentState.name
                )
                binding.tvReceiverDebug.text = "Frames: %d | Ambient: %.1f | Baseline: %s".format(
                    diag.totalFramesProcessed,
                    diag.ambientLuma,
                    if (diag.isCalibrated) "Calibrated" else "Calibrating"
                )
            }
        }
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
        stopReceiver()
        morseSenderEngine.release()
        morseCodeManager.stopFlashing()
        _binding = null
    }
}