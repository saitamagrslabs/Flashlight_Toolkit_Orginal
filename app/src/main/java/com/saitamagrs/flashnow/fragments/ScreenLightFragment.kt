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
import com.saitamagrs.flashnow.utils.ClapDetector

enum class ScreenPattern { NONE, POLICE, PARTY, STROBE, CANDLE }
enum class PatternSpeed { SLOW, NORMAL, FAST }

class ScreenLightFragment : BaseAdFragment() {
    private var userSelectedColor = Color.WHITE
    private var _binding: FragmentScreenLightBinding? = null
    private val binding get() = _binding!!

    override val adView: AdView? get() = binding.adView

    private lateinit var bottomSheetBehavior: BottomSheetBehavior<View>

    private var currentColor = Color.WHITE
    private var isScreenOn = true
    private var activePattern = ScreenPattern.NONE
    private var currentSpeed = PatternSpeed.NORMAL
    private var selectedColorView: View? = null

    // --- Brightness State Separation ---
    private var screenLightBrightness: Float = 1.0f
    private var originalBrightness: Float = -1.0f

    private val handler = Handler(Looper.getMainLooper())
    private var currentPatternRunnable: Runnable? = null

    // --- Clap Detection Fields ---
    private var clapDetector: ClapDetector? = null

    private val requestAudioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (_binding == null || !isAdded) return@registerForActivityResult
        if (isGranted) {
            startListeningForClaps()
        } else {
            Toast.makeText(requireContext(), "Permission Denied", Toast.LENGTH_SHORT).show()
            binding.clapSwitchScreen.isChecked = false
        }
    }

    private val colors = listOf(
        Color.WHITE, Color.RED, Color.BLUE, Color.GREEN, Color.YELLOW,
        Color.CYAN, Color.MAGENTA, Color.parseColor("#FFA500"), Color.parseColor("#800080")
    )

    private val colorNames = mapOf(
        Color.WHITE to "White color",
        Color.RED to "Red color",
        Color.BLUE to "Blue color",
        Color.GREEN to "Green color",
        Color.YELLOW to "Yellow color",
        Color.CYAN to "Cyan color",
        Color.MAGENTA to "Magenta color",
        Color.parseColor("#FFA500") to "Orange color",
        Color.parseColor("#800080") to "Purple color"
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
        setupSpeedControls()
        setupPatternButtons()
        setupClapSwitch()
        updatePatternButtonsUI()

        binding.btnBackCustom.setOnClickListener {
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }
    }

    private fun setupBottomSheet() {
        bottomSheetBehavior = BottomSheetBehavior.from(binding.bottomSheetLayout)

        val displayMetrics = resources.displayMetrics
        val maxHalfScreenHeight = (displayMetrics.heightPixels * 0.50).toInt()
        bottomSheetBehavior.maxHeight = maxHalfScreenHeight
        bottomSheetBehavior.isFitToContents = true

        bottomSheetBehavior.state = BottomSheetBehavior.STATE_EXPANDED
        binding.touchSurface.setOnClickListener {
            if (bottomSheetBehavior.state == BottomSheetBehavior.STATE_EXPANDED) {
                bottomSheetBehavior.state = BottomSheetBehavior.STATE_HIDDEN
            } else if (bottomSheetBehavior.state == BottomSheetBehavior.STATE_HIDDEN) {
                bottomSheetBehavior.state = BottomSheetBehavior.STATE_EXPANDED
            }
        }
    }

    private fun setupSpeedControls() {
        binding.speedToggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                val newSpeed = when (checkedId) {
                    R.id.btn_speed_slow -> PatternSpeed.SLOW
                    R.id.btn_speed_fast -> PatternSpeed.FAST
                    else -> PatternSpeed.NORMAL
                }
                if (currentSpeed != newSpeed) {
                    currentSpeed = newSpeed
                    updateSpeedButtonsUI()
                    if (activePattern != ScreenPattern.NONE && isScreenOn) {
                        restartActivePattern()
                    }
                }
            }
        }
        updateSpeedButtonsUI()
    }

    private fun updateSpeedButtonsUI() {
        if (_binding == null) return
        binding.btnSpeedSlow.contentDescription = "Slow pattern speed" + (if (currentSpeed == PatternSpeed.SLOW) ", selected" else "")
        binding.btnSpeedNormal.contentDescription = "Normal pattern speed" + (if (currentSpeed == PatternSpeed.NORMAL) ", selected" else "")
        binding.btnSpeedFast.contentDescription = "Fast pattern speed" + (if (currentSpeed == PatternSpeed.FAST) ", selected" else "")
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
                if (_binding != null && isAdded && isResumed) {
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
            stopPatternRunnable()
            if (_binding != null) {
                binding.screenLightRoot.setBackgroundColor(Color.BLACK)
            }
        } else {
            if (activePattern != ScreenPattern.NONE) {
                restartActivePattern()
            } else {
                changeColor(userSelectedColor, fromUser = false)
            }
        }
    }

    private fun setupColorPalette() {
        val colorPalette = binding.colorPalette
        colorPalette.removeAllViews()
        colors.forEachIndexed { index, color ->
            val colorView = createColorView(color, index)
            colorPalette.addView(colorView)
            if (color == currentColor) {
                colorView.isSelected = true
                selectedColorView = colorView
            }
        }
    }

    private fun createColorView(color: Int, index: Int): View {
        val context = requireContext()
        val size = resources.getDimensionPixelSize(R.dimen.color_circle_size)
        val margin = resources.getDimensionPixelSize(R.dimen.color_circle_margin)

        val container = FrameLayout(context)
        val containerParams = FrameLayout.LayoutParams(size, size)
        containerParams.marginEnd = margin
        container.layoutParams = containerParams
        val pad = (size * 0.10f).toInt().coerceAtLeast(3)
        container.setPadding(pad, pad, pad, pad)
        container.foreground = ContextCompat.getDrawable(context, R.drawable.bg_color_circle)

        val colorCircle = ImageView(context)
        val circleDrawable = GradientDrawable().apply { 
            shape = GradientDrawable.OVAL
            setColor(color)
        }
        colorCircle.background = circleDrawable
        container.addView(colorCircle)

        val baseName = colorNames[color] ?: "Color option ${index + 1}"
        val isSel = (color == currentColor && activePattern == ScreenPattern.NONE)
        container.contentDescription = if (isSel) "$baseName, selected" else baseName
        container.isFocusable = true
        container.isClickable = true

        container.setOnClickListener {
            stopAllPatterns()
            changeColor(color, fromUser = true)
            updateColorSelectionUI(container)
        }
        return container
    }

    private fun updateColorSelectionUI(selectedView: View) {
        val colorPalette = _binding?.colorPalette ?: return
        for (i in 0 until colorPalette.childCount) {
            val child = colorPalette.getChildAt(i)
            val isTarget = (child == selectedView)
            child.isSelected = isTarget
            val color = colors.getOrNull(i)
            val baseName = colorNames[color] ?: "Color option ${i + 1}"
            child.contentDescription = if (isTarget) "$baseName, selected" else baseName
        }
        selectedColorView = selectedView
    }

    private fun changeColor(color: Int, fromUser: Boolean = false) {
        currentColor = color
        if (fromUser) {
            userSelectedColor = color
        }
        if (_binding != null) {
            binding.screenLightRoot.setBackgroundColor(if (isScreenOn) currentColor else Color.BLACK)
        }
    }

    override fun onResume() {
        super.onResume()

        // Capture system/window brightness on first entry
        if (originalBrightness < 0) {
            originalBrightness = requireActivity().window.attributes.screenBrightness
        }

        // Restore screen light brightness and sync seekbar and percentage text
        val progressVal = (screenLightBrightness * 100).toInt()
        _binding?.seekbarBrightness?.progress = progressVal
        _binding?.tvBrightnessVal?.text = "${progressVal}%"
        hideSystemUI()
        setScreenBrightness(screenLightBrightness)

        // Safely restart active pattern if screen is ON
        if (activePattern != ScreenPattern.NONE && isScreenOn) {
            restartActivePattern()
        }
    }

    override fun onPause() {
        super.onPause()
        showSystemUI()

        // Stop pattern Runnables so callbacks do not continue in background
        stopPatternRunnable()

        // Safely restore original system/window brightness
        setScreenBrightness(originalBrightness)
        stopListeningForClaps()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        stopListeningForClaps()
        clapDetector?.release()
        clapDetector = null

        stopPatternRunnable()
        handler.removeCallbacksAndMessages(null)

        selectedColorView = null
        _binding = null
    }

    private fun setupScreen() { 
        if (_binding != null) {
            binding.screenLightRoot.setBackgroundColor(if (isScreenOn) currentColor else Color.BLACK)
        }
    }

    private fun setScreenBrightness(brightness: Float) {
        val activity = activity ?: return
        val layoutParams = activity.window.attributes
        if (brightness < 0) {
            layoutParams.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        } else {
            layoutParams.screenBrightness = brightness.coerceIn(0.01f, 1.0f)
        }
        activity.window.attributes = layoutParams
    }

    private fun hideSystemUI() {
        activity?.runOnUiThread {
            val toolbar = activity?.findViewById<Toolbar>(R.id.toolbar)
            toolbar?.visibility = View.GONE

            val window = activity?.window ?: return@runOnUiThread
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun showSystemUI() { 
        activity?.findViewById<Toolbar>(R.id.toolbar)?.visibility = View.VISIBLE
        val window = activity?.window ?: return
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.show(WindowInsetsCompat.Type.systemBars())
    }

    private fun setupBrightnessControl() { 
        binding.seekbarBrightness.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener { 
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) { 
                if (fromUser) {
                    screenLightBrightness = (progress / 100f).coerceIn(0.01f, 1.0f)
                    setScreenBrightness(screenLightBrightness) 
                }
                _binding?.tvBrightnessVal?.text = "${progress}%"
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
        if (activePattern == pattern) {
            stopAllPatterns()
        } else {
            when (pattern) {
                ScreenPattern.POLICE -> startPolicePattern()
                ScreenPattern.PARTY -> startPartyPattern()
                ScreenPattern.STROBE -> startStrobePattern()
                ScreenPattern.CANDLE -> startCandlePattern()
                else -> {}
            }
        } 
    }

    private fun updateModeStatusUI() {
        if (_binding == null) return
        val modeName = when (activePattern) {
            ScreenPattern.NONE -> "Solid"
            ScreenPattern.POLICE -> "Police"
            ScreenPattern.PARTY -> "Party"
            ScreenPattern.STROBE -> "Strobe"
            ScreenPattern.CANDLE -> "Candle"
        }
        binding.tvModeStatus.text = "Mode: $modeName"
    }

    private fun updatePatternButtonsUI() { 
        if (_binding == null) return
        updateModeStatusUI()
        updateSpeedButtonsUI()

        binding.btnPolicePattern.isSelected = activePattern == ScreenPattern.POLICE
        binding.btnPartyPattern.isSelected = activePattern == ScreenPattern.PARTY
        binding.btnStrobePattern.isSelected = activePattern == ScreenPattern.STROBE
        binding.btnCandlePattern.isSelected = activePattern == ScreenPattern.CANDLE

        binding.btnPolicePattern.contentDescription = "Police pattern" + (if (activePattern == ScreenPattern.POLICE) ", selected" else "")
        binding.btnPartyPattern.contentDescription = "Party pattern" + (if (activePattern == ScreenPattern.PARTY) ", selected" else "")
        binding.btnStrobePattern.contentDescription = "Strobe pattern" + (if (activePattern == ScreenPattern.STROBE) ", selected" else "")
        binding.btnCandlePattern.contentDescription = "Candle pattern" + (if (activePattern == ScreenPattern.CANDLE) ", selected" else "")

        binding.btnPolicePattern.clearAnimation()
        binding.btnPartyPattern.clearAnimation()
        binding.btnStrobePattern.clearAnimation()
        binding.btnCandlePattern.clearAnimation()

        val activeView = when (activePattern) { 
            ScreenPattern.POLICE -> binding.btnPolicePattern
            ScreenPattern.PARTY -> binding.btnPartyPattern
            ScreenPattern.STROBE -> binding.btnStrobePattern
            ScreenPattern.CANDLE -> binding.btnCandlePattern
            else -> null 
        }
        activeView?.let { startPulseAnimation(it) } 
    }

    private fun startPulseAnimation(view: View) { 
        val context = context ?: return
        val animation = AnimationUtils.loadAnimation(context, R.anim.glow_pulse)
        view.startAnimation(animation) 
    }

    private fun stopPatternRunnable() {
        currentPatternRunnable?.let { handler.removeCallbacks(it) }
        currentPatternRunnable = null
    }

    private fun stopAllPatterns() { 
        activePattern = ScreenPattern.NONE
        stopPatternRunnable()
        currentColor = userSelectedColor
        if (_binding != null) {
            binding.screenLightRoot.setBackgroundColor(if (isScreenOn) userSelectedColor else Color.BLACK)
        }
        updatePatternButtonsUI() 
    }

    private fun restartActivePattern() {
        stopPatternRunnable()
        when (activePattern) {
            ScreenPattern.POLICE -> startPolicePattern()
            ScreenPattern.PARTY -> startPartyPattern()
            ScreenPattern.STROBE -> startStrobePattern()
            ScreenPattern.CANDLE -> startCandlePattern()
            else -> {}
        }
    }

    private fun getPoliceInterval(): Long = when (currentSpeed) {
        PatternSpeed.SLOW -> 500L
        PatternSpeed.NORMAL -> 300L
        PatternSpeed.FAST -> 150L
    }

    private fun getPartyInterval(): Long = when (currentSpeed) {
        PatternSpeed.SLOW -> 400L
        PatternSpeed.NORMAL -> 200L
        PatternSpeed.FAST -> 100L
    }

    private fun getStrobeInterval(): Long = when (currentSpeed) {
        PatternSpeed.SLOW -> 200L
        PatternSpeed.NORMAL -> 100L
        PatternSpeed.FAST -> 50L
    }

    private fun getCandleInterval(): Long = when (currentSpeed) {
        PatternSpeed.SLOW -> (150 + Math.random() * 200).toLong()
        PatternSpeed.NORMAL -> (100 + Math.random() * 100).toLong()
        PatternSpeed.FAST -> (50 + Math.random() * 50).toLong()
    }

    private fun startPolicePattern() { 
        stopPatternRunnable()
        activePattern = ScreenPattern.POLICE
        var isRed = true
        currentPatternRunnable = object : Runnable { 
            override fun run() {
                if (_binding == null || !isAdded || !isResumed || activePattern != ScreenPattern.POLICE) return
                if (isScreenOn) {
                    changeColor(if (isRed) Color.RED else Color.BLUE, fromUser = false)
                }
                isRed = !isRed
                handler.postDelayed(this, getPoliceInterval()) 
            } 
        }
        handler.post(currentPatternRunnable!!) 
        updatePatternButtonsUI() 
    }

    private fun startPartyPattern() { 
        stopPatternRunnable()
        activePattern = ScreenPattern.PARTY
        var colorIndex = 0
        val partyColors = colors.filter { it != Color.WHITE && it != Color.BLACK }
        currentPatternRunnable = object : Runnable { 
            override fun run() {
                if (_binding == null || !isAdded || !isResumed || activePattern != ScreenPattern.PARTY) return
                if (isScreenOn) {
                    changeColor(partyColors[colorIndex], fromUser = false)
                }
                colorIndex = (colorIndex + 1) % partyColors.size
                handler.postDelayed(this, getPartyInterval()) 
            } 
        }
        handler.post(currentPatternRunnable!!) 
        updatePatternButtonsUI() 
    }

    private fun startStrobePattern() { 
        stopPatternRunnable()
        activePattern = ScreenPattern.STROBE
        var isWhite = true
        currentPatternRunnable = object : Runnable { 
            override fun run() {
                if (_binding == null || !isAdded || !isResumed || activePattern != ScreenPattern.STROBE) return
                if (isScreenOn && _binding != null) {
                    binding.screenLightRoot.setBackgroundColor(if (isWhite) Color.WHITE else Color.BLACK)
                }
                isWhite = !isWhite
                handler.postDelayed(this, getStrobeInterval()) 
            } 
        }
        handler.post(currentPatternRunnable!!) 
        updatePatternButtonsUI() 
    }

    private fun startCandlePattern() { 
        stopPatternRunnable()
        activePattern = ScreenPattern.CANDLE
        val baseColor = Color.parseColor("#FF8C00")
        currentPatternRunnable = object : Runnable { 
            override fun run() { 
                if (_binding == null || !isAdded || !isResumed || activePattern != ScreenPattern.CANDLE) return
                val flicker = (Math.random() * 50 - 25).toInt()
                val red = (Color.red(baseColor) + flicker).coerceIn(150, 255)
                val green = (Color.green(baseColor) + flicker).coerceIn(80, 180)
                val blue = (Color.blue(baseColor) + flicker / 2).coerceIn(0, 50)
                if (isScreenOn) {
                    changeColor(Color.rgb(red, green, blue), fromUser = false)
                }
                handler.postDelayed(this, getCandleInterval()) 
            } 
        }
        handler.post(currentPatternRunnable!!)
        updatePatternButtonsUI() 
    }
}