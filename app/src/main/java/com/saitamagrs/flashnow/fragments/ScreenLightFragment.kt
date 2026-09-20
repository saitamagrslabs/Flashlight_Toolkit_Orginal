package com.saitamagrs.flashnow.fragments

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.AnimationUtils
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.gms.ads.AdView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.saitamagrs.flashnow.R
import com.saitamagrs.flashnow.databinding.FragmentScreenLightBinding
import com.saitamagrs.flashnow.utils.AppConstants
import com.saitamagrs.flashnow.utils.ClapDetector

enum class ScreenPattern { NONE, POLICE, PARTY, STROBE, CANDLE }

class ScreenLightFragment : BaseAdFragment() {
    private var userSelectedColor = Color.WHITE
    private var _binding: FragmentScreenLightBinding? = null
    private val binding get() = _binding!!

    override val adView: AdView? get() = binding.adView

    private lateinit var bottomSheetBehavior: BottomSheetBehavior<View>

    private var currentColor = Color.WHITE
    private var isScreenOn = true
    private var activePattern = ScreenPattern.NONE
    private var selectedColorView: View? = null
    private var originalBrightness: Float = -1.0f

    private val handler = Handler(Looper.getMainLooper())
    private var currentPatternRunnable: Runnable? = null

    // --- Clap Detection Fields ---
    private var clapDetector: ClapDetector? = null

    private val requestAudioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            startListeningForClaps()
        } else {
            Toast.makeText(requireContext(), "Permission Denied", Toast.LENGTH_SHORT).show()
            if(isAdded) binding.clapSwitchScreen.isChecked = false
        }
    }

    private val colors = listOf(
        Color.WHITE, Color.RED, Color.BLUE, Color.GREEN, Color.YELLOW,
        Color.CYAN, Color.MAGENTA, Color.parseColor("#FFA500"), Color.parseColor("#800080")
    )

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentScreenLightBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupBottomSheet()
        setupScreen()
        setupColorPalette()
        setupBrightnessControl()
        setupPatternButtons()
        setupClapSwitch()
        updatePatternButtonsUI()

        binding.btnBackCustom.setOnClickListener {
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }
    }

    private fun setupBottomSheet() {
        bottomSheetBehavior = BottomSheetBehavior.from(binding.bottomSheetLayout)
        bottomSheetBehavior.state = BottomSheetBehavior.STATE_EXPANDED
        binding.touchSurface.setOnClickListener {
            if (bottomSheetBehavior.state == BottomSheetBehavior.STATE_EXPANDED) {
                bottomSheetBehavior.state = BottomSheetBehavior.STATE_HIDDEN
            } else if (bottomSheetBehavior.state == BottomSheetBehavior.STATE_HIDDEN) {
                bottomSheetBehavior.state = BottomSheetBehavior.STATE_EXPANDED
            }
        }
    }

    private fun setupClapSwitch() {
        binding.clapSwitchScreen.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    startListeningForClaps()
                } else {
                    requestAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            } else {
                stopListeningForClaps()
            }
        }
    }

    private fun startListeningForClaps() {
        if (clapDetector == null) {
            clapDetector = ClapDetector(requireContext().applicationContext) {
                if (isAdded) {
                    toggleScreenLight()
                }
            }
        }
        clapDetector?.startListening()
    }

    private fun stopListeningForClaps() {
        clapDetector?.stopListening()
    }

    private fun toggleScreenLight() {
        isScreenOn = !isScreenOn
        if (!isScreenOn) {
            stopAllPatterns() // Stop patterns when off
        }
        binding.screenLightRoot.setBackgroundColor(if (isScreenOn) currentColor else Color.BLACK)
    }

    private fun setupColorPalette() {
        val colorPalette = binding.colorPalette
        colorPalette.removeAllViews()
        colors.forEach { color ->
            val colorView = createColorView(color)
            colorPalette.addView(colorView)
            if (color == currentColor) {
                colorView.isSelected = true
                selectedColorView = colorView
            }
        }
    }

    private fun createColorView(color: Int): View {
        val context = requireContext()
        val size = resources.getDimensionPixelSize(R.dimen.color_circle_size)
        val margin = resources.getDimensionPixelSize(R.dimen.color_circle_margin)

        val container = FrameLayout(context)
        val containerParams = FrameLayout.LayoutParams(size, size)
        containerParams.marginEnd = margin
        container.layoutParams = containerParams
        container.foreground = ContextCompat.getDrawable(context, R.drawable.bg_color_circle)

        val colorCircle = ImageView(context)
        val circleDrawable = GradientDrawable().apply { 
            shape = GradientDrawable.OVAL
            setColor(color)
        }
        colorCircle.background = circleDrawable
        container.addView(colorCircle)

        container.setOnClickListener {
            stopAllPatterns()
            changeColor(color)
            selectedColorView?.isSelected = false
            it.isSelected = true
            selectedColorView = it
        }
        return container
    }

    private fun changeColor(color: Int,fromUser: Boolean = false) {
        currentColor = color
        binding.screenLightRoot.setBackgroundColor(color)
        if (fromUser) {
            userSelectedColor = color
        }
    }

    override fun onResume() {
        super.onResume()
        originalBrightness = requireActivity().window.attributes.screenBrightness
      //  if brightness is < 0 (system default), treat as 1.0 for seekbar
        val brightnessValue = if (originalBrightness < 0) 1.0f else originalBrightness
        binding.seekbarBrightness.progress = (brightnessValue * 100).toInt()
        hideSystemUI()
        setScreenBrightness(1.0f)
    }

    override fun onPause() {
        super.onPause()
        showSystemUI()
        setScreenBrightness(originalBrightness)
        stopListeningForClaps()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        clapDetector?.release()
        clapDetector = null
        stopAllPatterns()
        handler.removeCallbacksAndMessages(null)
        selectedColorView = null
        _binding = null
    }

    private fun setupScreen() { 
        binding.screenLightRoot.setBackgroundColor(currentColor) 
    }

    private fun setScreenBrightness(brightness: Float) {
        val layoutParams = requireActivity().window.attributes
        // A brightness < 0 is the system default. Otherwise, it's a manual value.
        if (brightness < 0) {
            layoutParams.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        } else {
            layoutParams.screenBrightness = brightness.coerceIn(0.01f, 1.0f)
        }
        requireActivity().window.attributes = layoutParams
    }

    /*private fun hideSystemUI() {
        requireActivity().runOnUiThread {
            // Force hide the toolbar
            val toolbar = requireActivity().findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)
            toolbar?.visibility = View.GONE

            // Hide system bars
            val window = requireActivity().window
            val decorView = window.decorView

            decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            or View.SYSTEM_UI_FLAG_FULLSCREEN
                    )
        }


        val window = requireActivity().window
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }*/

    private fun hideSystemUI() {
        requireActivity().runOnUiThread {
            val toolbar = requireActivity().findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)
            toolbar?.visibility = View.GONE

            val window = requireActivity().window
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun showSystemUI() { 
        activity?.findViewById<Toolbar>(R.id.toolbar)?.visibility = View.VISIBLE
        val window = requireActivity().window
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.show(WindowInsetsCompat.Type.systemBars())
    }

    private fun setupBrightnessControl() { 
        binding.seekbarBrightness.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener { 
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) { 
                setScreenBrightness(progress / 100f) 
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        }) 
    }

    private fun setupPatternButtons() { 
        binding.btnPolicePattern.setOnClickListener { togglePattern(ScreenPattern.POLICE) }
        binding.btnPartyPattern.setOnClickListener { togglePattern(ScreenPattern.PARTY) }
        binding.btnStrobePattern.setOnClickListener { togglePattern(ScreenPattern.STROBE) }
        binding.btnCandlePattern.setOnClickListener { togglePattern(ScreenPattern.CANDLE) } 
    }

    private fun togglePattern(pattern: ScreenPattern) { 
        if (activePattern == pattern) stopAllPatterns() else when (pattern) {
            ScreenPattern.POLICE -> startPolicePattern()
            ScreenPattern.PARTY -> startPartyPattern()
            ScreenPattern.STROBE -> startStrobePattern()
            ScreenPattern.CANDLE -> startCandlePattern()
            else -> {}
        } 
    }

    private fun updatePatternButtonsUI() { 
        binding.btnPolicePattern.isSelected = activePattern == ScreenPattern.POLICE
        binding.btnPartyPattern.isSelected = activePattern == ScreenPattern.PARTY
        binding.btnStrobePattern.isSelected = activePattern == ScreenPattern.STROBE
        binding.btnCandlePattern.isSelected = activePattern == ScreenPattern.CANDLE
        binding.btnPolicePattern.clearAnimation()
        binding.btnPartyPattern.clearAnimation()
        binding.btnStrobePattern.clearAnimation()
        binding.btnCandlePattern.clearAnimation()
        val activeView = when(activePattern) { 
            ScreenPattern.POLICE -> binding.btnPolicePattern
            ScreenPattern.PARTY -> binding.btnPartyPattern
            ScreenPattern.STROBE -> binding.btnStrobePattern
            ScreenPattern.CANDLE -> binding.btnCandlePattern
            else -> null 
        }
        activeView?.let { startPulseAnimation(it) } 
    }

    private fun startPulseAnimation(view: View) { 
        val animation = AnimationUtils.loadAnimation(requireContext(), R.anim.glow_pulse)
        view.startAnimation(animation) 
    }

    private fun stopAllPatterns() { 
        activePattern = ScreenPattern.NONE
        currentPatternRunnable?.let { handler.removeCallbacks(it) }
        currentPatternRunnable = null
        changeColor(currentColor,fromUser = true)
        updatePatternButtonsUI() 
    }

    private fun startPolicePattern() { 
        stopAllPatterns()
        activePattern = ScreenPattern.POLICE
        var isRed = true
        currentPatternRunnable = object : Runnable { 
            override fun run() {
                if (isScreenOn) {
                    changeColor(if (isRed) Color.RED else Color.BLUE)
                }
                isRed = !isRed
                handler.postDelayed(this, 300) 
            } 
        }
        handler.post(currentPatternRunnable!!) 
        updatePatternButtonsUI() 
    }

    private fun startPartyPattern() { 
        stopAllPatterns()
        activePattern = ScreenPattern.PARTY
        var colorIndex = 0
        val partyColors = colors.filter { it != Color.WHITE && it != Color.BLACK }
        currentPatternRunnable = object : Runnable { 
            override fun run() {
                if (isScreenOn) {
                    changeColor(partyColors[colorIndex])
                }
                colorIndex = (colorIndex + 1) % partyColors.size
                handler.postDelayed(this, 200) 
            } 
        }
        handler.post(currentPatternRunnable!!) 
        updatePatternButtonsUI() 
    }

    private fun startStrobePattern() { 
        stopAllPatterns()
        activePattern = ScreenPattern.STROBE
        var isWhite = true
        currentPatternRunnable = object : Runnable { 
            override fun run() {
                if (isScreenOn) {
                binding.screenLightRoot.setBackgroundColor(if (isWhite) Color.WHITE else Color.BLACK) }
                isWhite = !isWhite
                handler.postDelayed(this, 100) 
            } 
        }
        handler.post(currentPatternRunnable!!) 
        updatePatternButtonsUI() 
    }

    private fun startCandlePattern() { 
        stopAllPatterns()
        activePattern = ScreenPattern.CANDLE
        val baseColor = Color.parseColor("#FF8C00")
        currentPatternRunnable = object : Runnable { 
            override fun run() { 
                val flicker = (Math.random() * 50 - 25).toInt()
                val red = (Color.red(baseColor) + flicker).coerceIn(150, 255)
                val green = (Color.green(baseColor) + flicker).coerceIn(80, 180)
                val blue = (Color.blue(baseColor) + flicker / 2).coerceIn(0, 50)
                if (isScreenOn) {
                changeColor(Color.rgb(red, green, blue)) }
                handler.postDelayed(this, (100 + Math.random() * 200).toLong()) 
            } 
        }
        handler.post(currentPatternRunnable!!); 
        updatePatternButtonsUI() 
    }
}