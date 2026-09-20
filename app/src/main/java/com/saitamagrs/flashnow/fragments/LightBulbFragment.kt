package com.saitamagrs.flashnow.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.appcompat.widget.Toolbar
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.Fragment
import com.saitamagrs.flashnow.R
import com.saitamagrs.flashnow.databinding.FragmentLightBulbBinding

class LightBulbFragment : Fragment() {

    private var _binding: FragmentLightBulbBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLightBulbBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onResume() {
        super.onResume()
        // Hide the system UI for an immersive experience
        hideSystemUI()
        
        // Set screen brightness to full for the light bulb effect
        val layoutParams = requireActivity().window.attributes
        layoutParams.screenBrightness = 1.0f
        requireActivity().window.attributes = layoutParams
    }

    override fun onPause() {
        super.onPause()
        // Restore the system UI when leaving the fragment
        showSystemUI()
        
        // Reset screen brightness to system default when leaving
        val layoutParams = requireActivity().window.attributes
        layoutParams.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        requireActivity().window.attributes = layoutParams
    }

    private fun hideSystemUI() {
        // Hide the toolbar in the main activity
        activity?.findViewById<Toolbar>(R.id.toolbar)?.visibility = View.GONE

        // Hide the status and navigation bars for a truly immersive experience
        val window = requireActivity().window
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        insetsController.hide(WindowInsetsCompat.Type.systemBars()) // This hides both status and navigation bars
    }

    private fun showSystemUI() {
        // Show the toolbar in the main activity
        activity?.findViewById<Toolbar>(R.id.toolbar)?.visibility = View.VISIBLE

        // Show the status and navigation bars
        val window = requireActivity().window
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.show(WindowInsetsCompat.Type.systemBars()) // This shows both status and navigation bars
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}