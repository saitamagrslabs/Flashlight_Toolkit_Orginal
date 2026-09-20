package com.saitamagrs.flashnow.fragments

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.setMargins
import com.google.android.gms.ads.AdView
import com.google.android.material.card.MaterialCardView
import com.saitamagrs.flashnow.MainActivity
import com.saitamagrs.flashnow.R
import com.saitamagrs.flashnow.databinding.FragmentMorseCodeBinding
import com.saitamagrs.flashnow.utils.FlashlightController
import com.saitamagrs.flashnow.utils.MorseCodeManager

class MorseCodeFragment : BaseAdFragment() {

    private var _binding: FragmentMorseCodeBinding? = null
    private val binding get() = _binding!!
    override val adView: AdView? get() = binding.adView

    private lateinit var morseCodeManager: MorseCodeManager
    private var hasCameraPermission = false

    private val requestCameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasCameraPermission = isGranted
        if (!isGranted) {
            Toast.makeText(requireContext(), "Camera permission needed for flashlight", Toast.LENGTH_SHORT).show()
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

        setupUI()
        populateEmergencySignalsGrid()
        setupStatusListener()
        setupMorsePreview()

        // Initially enable all controls
        setControlsEnabled(true)
    }

    private fun checkCameraPermission() {
        hasCameraPermission = ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (!hasCameraPermission) {
            requestCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun setupUI() {
        binding.btnSendCustom.setOnClickListener {
            if (!hasCameraPermission) {
                Toast.makeText(requireContext(), "Camera permission required", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (morseCodeManager.isFlashing()) return@setOnClickListener // extra safety
            val message = binding.etCustomMessage.text.toString().trim()
            if (message.isNotEmpty()) {
                morseCodeManager.flashCustomMessage(message)
            } else {
                Toast.makeText(requireContext(), "Enter a message", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnStop.setOnClickListener {
            morseCodeManager.stopFlashing()
        }

        (activity as? MainActivity)?.setToolbarTitle("Morse Code")
    }

    private fun setupStatusListener() {
        morseCodeManager.setOnStatusChangeListener { isFlashing ->
            requireActivity().runOnUiThread {
                if (isFlashing) {
                    binding.tvStatus.text = "Flashing..."
                    binding.ivStatus.setImageResource(R.drawable.ic_power_on)
                    binding.ivStatus.setColorFilter(ContextCompat.getColor(requireContext(), R.color.accent_green))
                    binding.btnStop.visibility = View.VISIBLE
                    setControlsEnabled(false) // disable start buttons
                } else {
                    binding.tvStatus.text = "Ready to transmit"
                    binding.ivStatus.setImageResource(R.drawable.ic_power_off)
                    binding.ivStatus.setColorFilter(ContextCompat.getColor(requireContext(), R.color.text_secondary))
                    binding.btnStop.visibility = View.GONE
                    setControlsEnabled(true) // enable start buttons
                }
            }
        }
    }

    /**
     * Enable or disable all buttons that start a new transmission:
     * - emergency signal cards (inside grid)
     * - custom "FLASH MESSAGE" button
     */
    private fun setControlsEnabled(enabled: Boolean) {
        // Emergency signal cards
        for (i in 0 until binding.gridEmergencySignals.childCount) {
            binding.gridEmergencySignals.getChildAt(i).isEnabled = enabled
        }
        // Custom message button
        binding.btnSendCustom.isEnabled = enabled
        // Visual feedback
        binding.btnSendCustom.alpha = if (enabled) 1.0f else 0.5f
    }

    private fun setupMorsePreview() {
        binding.etCustomMessage.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val text = s?.toString()?.trim()
                if (!text.isNullOrEmpty()) {
                    val morse = morseCodeManager.convertToMorse(text.uppercase())
                    binding.tvMorsePreview.text = morse
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
                if (morseCodeManager.isFlashing()) return@setOnClickListener // extra safety
                flashSignal(signalName)
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

    private fun flashSignal(signalName: String) {
        morseCodeManager.flashPredefinedSignal(signalName)
        Toast.makeText(requireContext(), "Flashing: $signalName", Toast.LENGTH_SHORT).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        morseCodeManager.stopFlashing()
        _binding = null
    }
}