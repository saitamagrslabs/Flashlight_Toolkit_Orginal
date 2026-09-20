package com.saitamagrs.flashnow.fragments

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import com.google.android.material.chip.Chip
import com.saitamagrs.flashnow.MainActivity
import com.saitamagrs.flashnow.R
import com.saitamagrs.flashnow.databinding.FeatureItemBinding
import com.saitamagrs.flashnow.databinding.FragmentAboutBinding

class AboutFragment : Fragment() {

    private var _binding: FragmentAboutBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAboutBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // --- CRITICAL FIX for Edge-to-Edge UI ---
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(top = systemBars.top, bottom = systemBars.bottom)
            insets
        }

        // Dynamically set the app version
        try {
            val versionName = requireContext().packageManager
                .getPackageInfo(requireContext().packageName, 0).versionName
            binding.tvVersion.text = "Version $versionName"
        } catch (e: Exception) {
            e.printStackTrace()
            binding.tvVersion.text = "Version 1.0"
        }

        // Setup all UI components
        setupFeatures()
        //setupPatternChips()
        setupButtonListeners()

        // Handle Rate App button click
        binding.btnRateApp.setOnClickListener {
            val packageName = requireContext().packageName
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName")))
            } catch (e: android.content.ActivityNotFoundException) {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$packageName")))
            }
        }

        // Handle Share App button click
        binding.btnShareApp.setOnClickListener {
            val shareIntent = Intent(Intent.ACTION_SEND)
            shareIntent.type = "text/plain"
            val appLink = "https://play.google.com/store/apps/details?id=${requireContext().packageName}"
            shareIntent.putExtra(Intent.EXTRA_TEXT, "Check out this awesome flashlight app: $appLink")
            startActivity(Intent.createChooser(shareIntent, "Share via"))
        }
    }

 /*   private fun setupPatternChips() {
        // Setup chip texts directly
        val patterns = listOf(
            binding.chipPolice to "🚓 Police Pattern",
            binding.chipParty to "🎉 Party Colors",
            binding.chipStrobe to "⚡ Screen Strobe",
            binding.chipCandle to "🕯️ Candle Flicker",
            binding.chipColors to "🎨 9-Color Palette",
            binding.chipBrightness to "🔆 Brightness Control"
        )

        patterns.forEach { (chip, text) ->
            chip?.text = text
        }
    }*/

    private fun setupFeatures() {
        // Setup feature items - access them through the main binding
        setupFeatureItem(binding.featureMain, R.drawable.ic_power_on, "Main Flashlight", "Instant on/off with single tap")
        setupFeatureItem(binding.featureSos, R.drawable.ic_sos, "SOS Emergency Signal", "International distress pattern")
        setupFeatureItem(binding.featureStrobe, R.drawable.ic_strobe, "Strobe Light", "Adjustable frequency strobe effect")
        setupFeatureItem(binding.featureMorse, R.drawable.ic_morse_code, "Morse Code Communicator", "Send messages & emergency signals")
    }

    private fun setupFeatureItem(binding: FeatureItemBinding?, iconRes: Int, title: String, desc: String) {
        binding?.let {
            it.featureIcon.setImageResource(iconRes)
            it.featureIcon.setColorFilter(ContextCompat.getColor(requireContext(), R.color.accent_blue))
            it.featureTitle.text = title
            it.featureDesc.text = desc
        }
    }

    private fun setupButtonListeners() {
        // Button listeners are already set in onViewCreated
        // This method is kept for future expansion
    }

    override fun onResume() {
        super.onResume()
        (activity as? MainActivity)?.setToolbarTitle("About")
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}