package com.saitamagrs.flashnow.fragments

import android.Manifest
import android.content.pm.PackageManager
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
import com.saitamagrs.flashnow.morse.receiver.MorseReceiverEngine
import com.saitamagrs.flashnow.morse.sender.MorseSenderEngine
import com.saitamagrs.flashnow.utils.FlashlightController
import com.saitamagrs.flashnow.utils.MorseCodeManager
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
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
    private lateinit var morseReceiverEngine: MorseReceiverEngine

    private var cameraProvider: ProcessCameraProvider? = null
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private var isReceiverActive = false

    private var hasCameraPermission = false

    private val requestCameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasCameraPermission = isGranted
        if (!isGranted) {
            Toast.makeText(requireContext(), "Camera permission required for Morse features", Toast.LENGTH_SHORT).show()
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

        checkCameraPermission()

        val flashlightController = FlashlightController(requireContext())
        morseCodeManager = MorseCodeManager(flashlightController)
        morseSenderEngine = MorseSenderEngine(requireContext())
        morseReceiverEngine = MorseReceiverEngine()

        setupModeToggle()
        setupSenderUI()
        setupReceiverUI()
        observeSenderEngine()
        observeReceiverEngine()
        populateEmergencySignalsGrid()
        setupMorsePreview()

        (activity as? MainActivity)?.setToolbarTitle("Morse Communicator")
    }

    private fun checkCameraPermission() {
        hasCameraPermission = ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (!hasCameraPermission) {
            requestCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
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

    private fun observeSenderEngine() {
        viewLifecycleOwner.lifecycleScope.launch {
            morseSenderEngine.isTransmitting.collectLatest { isTransmitting ->
                if (isTransmitting) {
                    binding.tvStatus.text = "Transmitting Optical Morse..."
                    binding.ivStatus.setImageResource(R.drawable.ic_power_on)
                    binding.ivStatus.setColorFilter(ContextCompat.getColor(requireContext(), R.color.accent_green))
                    binding.btnStop.visibility = View.VISIBLE
                    setSenderControlsEnabled(false)
                } else {
                    binding.tvStatus.text = morseSenderEngine.statusText.value
                    binding.ivStatus.setImageResource(R.drawable.ic_power_off)
                    binding.ivStatus.setColorFilter(ContextCompat.getColor(requireContext(), R.color.text_secondary))
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
        binding.btnToggleReceiver.setOnClickListener {
            if (!hasCameraPermission) {
                Toast.makeText(requireContext(), "Camera permission required for Receiver", Toast.LENGTH_SHORT).show()
                checkCameraPermission()
                return@setOnClickListener
            }

            if (isReceiverActive) {
                stopReceiver()
            } else {
                startReceiver()
            }
        }

        binding.btnResetReceiver.setOnClickListener {
            morseReceiverEngine.reset()
        }
    }

    private fun startReceiver() {
        val context = context ?: return
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                bindCameraUseCases()
                isReceiverActive = true
                binding.btnToggleReceiver.text = "STOP RECEIVER"
                binding.btnToggleReceiver.setBackgroundColor(ContextCompat.getColor(context, R.color.accent_red))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start camera provider", e)
                Toast.makeText(context, "Error initializing camera receiver", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun bindCameraUseCases() {
        val provider = cameraProvider ?: return

        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(binding.viewFinderReceiver.surfaceProvider)
        }

        val imageAnalysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build().also {
                it.setAnalyzer(cameraExecutor, morseReceiverEngine)
            }

        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

        try {
            provider.unbindAll()
            provider.bindToLifecycle(
                viewLifecycleOwner,
                cameraSelector,
                preview,
                imageAnalysis
            )
        } catch (e: Exception) {
            Log.e(TAG, "Camera use case binding failed", e)
        }
    }

    private fun stopReceiver() {
        cameraProvider?.unbindAll()
        isReceiverActive = false
        if (_binding != null) {
            binding.btnToggleReceiver.text = "START RECEIVER"
            binding.btnToggleReceiver.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.accent_green))
        }
    }

    private fun observeReceiverEngine() {
        viewLifecycleOwner.lifecycleScope.launch {
            morseReceiverEngine.rawLuminance.collectLatest { luma ->
                val thresh = morseReceiverEngine.threshold.value
                val isLightOn = morseReceiverEngine.isLightOn.value
                val stateText = if (isLightOn) "LIGHT: ON" else "LIGHT: OFF"
                binding.tvLumaMetrics.text = String.format("Luma: %.1f | Thresh: %.1f | %s", luma, thresh, stateText)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            morseReceiverEngine.preambleDetected.collectLatest { synced ->
                if (synced) {
                    binding.tvReceiverSync.text = "Preamble: SYNCED ('...')"
                    binding.tvReceiverSync.setTextColor(ContextCompat.getColor(requireContext(), R.color.accent_green))
                } else {
                    binding.tvReceiverSync.text = "Waiting for preamble ('...')"
                    binding.tvReceiverSync.setTextColor(ContextCompat.getColor(requireContext(), R.color.accent_red))
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            morseReceiverEngine.currentSymbols.collectLatest { symbols ->
                binding.tvDetectedSymbols.text = "Current Symbols: ${symbols.ifEmpty { "-" }}"
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            morseReceiverEngine.decodedText.collectLatest { decoded ->
                binding.tvDecodedMessage.text = decoded.ifEmpty { "[Decoded text will appear here]" }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            morseReceiverEngine.debugLog.collectLatest { debug ->
                binding.tvReceiverDebug.text = "Debug: $debug"
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        morseSenderEngine.release()
        morseCodeManager.stopFlashing()
        stopReceiver()
        cameraExecutor.shutdown()
        _binding = null
    }
}