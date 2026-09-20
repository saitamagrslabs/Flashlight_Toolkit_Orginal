package com.saitamagrs.flashnow.fragments

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import com.saitamagrs.flashnow.MainActivity
import com.saitamagrs.flashnow.databinding.FragmentSettingsBinding
import com.saitamagrs.flashnow.utils.AppConstants

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // --- CRITICAL FIX for Edge-to-Edge UI ---
        // This is the modern and correct way to handle window insets.
        // It applies the system bar insets (like the status bar) as padding to the view.
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(top = systemBars.top, bottom = systemBars.bottom)
            insets
        }

        val sharedPreferences = requireContext().getSharedPreferences(AppConstants.PREFS_NAME, Context.MODE_PRIVATE)
        
        // --- Clap Sensitivity Logic ---
        val savedSensitivity = sharedPreferences.getInt(AppConstants.KEY_SENSITIVITY, 9000)
        binding.seekbarSensitivity.progress = savedSensitivity

        binding.seekbarSensitivity.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    sharedPreferences.edit().putInt(AppConstants.KEY_SENSITIVITY, progress).apply()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) { }
            override fun onStopTrackingTouch(seekBar: SeekBar?) { }
        })

        // --- Sound Switch Logic ---
        val isSoundEnabled = sharedPreferences.getBoolean(AppConstants.KEY_SOUND_ENABLED, true) // Default to true
        binding.switchSound.isChecked = isSoundEnabled

        binding.switchSound.setOnCheckedChangeListener { _, isChecked ->
            sharedPreferences.edit().putBoolean(AppConstants.KEY_SOUND_ENABLED, isChecked).apply()
        }
    }

    override fun onResume() {
        super.onResume()
        (activity as? MainActivity)?.setToolbarTitle("SETTINGS")
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}