package com.saitamagrs.flashnow.fragments

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.RelativeLayout
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.Fragment
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.saitamagrs.flashnow.R
import com.saitamagrs.flashnow.databinding.DialogSelectBulbTypeBinding
import com.saitamagrs.flashnow.databinding.FragmentLightBulbBinding
import com.saitamagrs.flashnow.lightbulb.BulbType
import com.saitamagrs.flashnow.lightbulb.LightBulbState
import com.saitamagrs.flashnow.utils.AppConstants
import kotlin.math.abs
import kotlin.math.pow

class LightBulbFragment : Fragment() {

    private enum class GestureMode {
        NONE,
        VERTICAL,
        HORIZONTAL
    }

    private var _binding: FragmentLightBulbBinding? = null
    private val binding get() = _binding!!

    private var state = LightBulbState()
    private var originalBrightness: Float = -1.0f
    private var originalStatusBarColor: Int = 0
    private var originalNavigationBarColor: Int = 0
    private var originalCutoutMode: Int = 0

    private val hideHudHandler = Handler(Looper.getMainLooper())
    private val hideHudRunnable = Runnable {
        _binding?.tvBrightnessHud?.visibility = View.GONE
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLightBulbBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupFullscreenPresentation()
        setupUI()
        setupDualGestureListener()
        setupWindowInsets()
        updateRenderState()
    }

    private fun setupWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.headerBar) { targetView, insets ->
            val statusBars = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            val displayCutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            val topInset = maxOf(statusBars.top, displayCutout.top)
            val density = resources.displayMetrics.density
            val extraSpacing = (14 * density).toInt()
            val minPadding = (36 * density).toInt()
            val calculatedTopPadding = if (topInset > 0) topInset + extraSpacing else minPadding

            targetView.setPadding(
                (16 * density).toInt(),
                calculatedTopPadding,
                (16 * density).toInt(),
                0
            )
            insets
        }

        ViewCompat.setOnApplyWindowInsetsListener(binding.bottomInfoPill) { targetView, insets ->
            val navBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            val density = resources.displayMetrics.density
            val baseMargin = (18 * density).toInt()
            val layoutParams = targetView.layoutParams as? RelativeLayout.LayoutParams
            layoutParams?.bottomMargin = navBars.bottom + baseMargin
            targetView.layoutParams = layoutParams
            insets
        }

        ViewCompat.requestApplyInsets(binding.headerBar)
        ViewCompat.requestApplyInsets(binding.bottomInfoPill)
    }

    private fun setupUI() {
        binding.btnBackLightBulb.setOnClickListener {
            activity?.onBackPressedDispatcher?.onBackPressed()
        }

        binding.btnSelectBulbType.setOnClickListener {
            showBulbTypeSelectionDialog()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupDualGestureListener() {
        var startY = 0f
        var startX = 0f
        var initialBrightness = 100
        var gestureMode = GestureMode.NONE

        binding.containerLightDisplay.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startY = event.rawY
                    startX = event.rawX
                    initialBrightness = state.brightnessPercent
                    gestureMode = GestureMode.NONE
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaX = event.rawX - startX
                    val deltaY = startY - event.rawY // positive when dragging UP

                    // Determine gesture orientation lock mode if not locked yet
                    if (gestureMode == GestureMode.NONE) {
                        val absX = abs(deltaX)
                        val absY = abs(deltaY)
                        if (absX > 30f || absY > 30f) {
                            gestureMode = if (absX > absY * 1.25f) {
                                GestureMode.HORIZONTAL
                            } else {
                                GestureMode.VERTICAL
                            }
                        }
                    }

                    when (gestureMode) {
                        GestureMode.VERTICAL -> {
                            // Vertical Drag: Adjust Brightness (0% -> 100%)
                            val viewHeight = view.height.coerceAtLeast(1)
                            val brightnessChange = ((deltaY / viewHeight) * 100).toInt()
                            val newBrightness = (initialBrightness + brightnessChange).coerceIn(0, 100)

                            if (newBrightness != state.brightnessPercent) {
                                state = state.copyWithBrightness(newBrightness)
                                updateRenderState()
                            }

                            // Show Brightness HUD
                            hideHudHandler.removeCallbacks(hideHudRunnable)
                            binding.tvBrightnessHud.text = "${state.brightnessPercent}%"
                            binding.tvBrightnessHud.visibility = View.VISIBLE
                        }
                        GestureMode.HORIZONTAL -> {
                            // Horizontal Drag: Visual swipe hint indicator
                            hideHudHandler.removeCallbacks(hideHudRunnable)
                            binding.tvBrightnessHud.text = if (deltaX < 0) "Next Bulb →" else "← Prev Bulb"
                            binding.tvBrightnessHud.visibility = View.VISIBLE
                        }
                        GestureMode.NONE -> {}
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val totalDeltaX = event.rawX - startX

                    when (gestureMode) {
                        GestureMode.HORIZONTAL -> {
                            // Execute Bulb Switch on Horizontal Swipe
                            if (totalDeltaX < -100f) {
                                // Swipe LEFT -> Next Bulb
                                state = state.getNextBulb()
                                updateRenderState()
                                showTemporaryHud("‹  ${state.selectedBulb.displayName}  ›")
                            } else if (totalDeltaX > 100f) {
                                // Swipe RIGHT -> Previous Bulb
                                state = state.getPreviousBulb()
                                updateRenderState()
                                showTemporaryHud("‹  ${state.selectedBulb.displayName}  ›")
                            } else {
                                hideHudHandler.postDelayed(hideHudRunnable, 300)
                            }
                        }
                        GestureMode.VERTICAL -> {
                            hideHudHandler.postDelayed(hideHudRunnable, 800)
                        }
                        GestureMode.NONE -> {
                            // Single tap toggles light power ON/OFF
                            state = state.copyWithPower(!state.isLit)
                            updateRenderState()
                        }
                    }
                    gestureMode = GestureMode.NONE
                    true
                }
                else -> false
            }
        }
    }

    private fun showTemporaryHud(text: String) {
        hideHudHandler.removeCallbacks(hideHudRunnable)
        binding.tvBrightnessHud.text = text
        binding.tvBrightnessHud.visibility = View.VISIBLE
        hideHudHandler.postDelayed(hideHudRunnable, 1000)
    }

    private fun showBulbTypeSelectionDialog() {
        val context = context ?: return
        val dialog = BottomSheetDialog(context)
        val dialogBinding = DialogSelectBulbTypeBinding.inflate(layoutInflater)
        dialog.setContentView(dialogBinding.root)

        // Highlight currently selected checkmark
        dialogBinding.checkBulbStandard.visibility = if (state.selectedBulb.type == BulbType.STANDARD) View.VISIBLE else View.GONE
        dialogBinding.checkBulbTube.visibility = if (state.selectedBulb.type == BulbType.TUBE_LIGHT) View.VISIBLE else View.GONE
        dialogBinding.checkBulbSpotlight.visibility = if (state.selectedBulb.type == BulbType.SPOTLIGHT) View.VISIBLE else View.GONE
        dialogBinding.checkBulbTorch.visibility = if (state.selectedBulb.type == BulbType.TORCH) View.VISIBLE else View.GONE
        dialogBinding.checkBulbEdison.visibility = if (state.selectedBulb.type == BulbType.EDISON) View.VISIBLE else View.GONE
        dialogBinding.checkBulbSmart.visibility = if (state.selectedBulb.type == BulbType.SMART) View.VISIBLE else View.GONE
        dialogBinding.checkBulbCandle.visibility = if (state.selectedBulb.type == BulbType.CANDLE) View.VISIBLE else View.GONE

        val selectType: (BulbType) -> Unit = { selectedType ->
            state = state.copyWithBulb(selectedType)
            updateRenderState()
            dialog.dismiss()
        }

        dialogBinding.itemBulbStandard.setOnClickListener { selectType(BulbType.STANDARD) }
        dialogBinding.itemBulbTube.setOnClickListener { selectType(BulbType.TUBE_LIGHT) }
        dialogBinding.itemBulbSpotlight.setOnClickListener { selectType(BulbType.SPOTLIGHT) }
        dialogBinding.itemBulbTorch.setOnClickListener { selectType(BulbType.TORCH) }
        dialogBinding.itemBulbEdison.setOnClickListener { selectType(BulbType.EDISON) }
        dialogBinding.itemBulbSmart.setOnClickListener { selectType(BulbType.SMART) }
        dialogBinding.itemBulbCandle.setOnClickListener { selectType(BulbType.CANDLE) }

        dialog.show()
    }

    private fun updateRenderState() {
        if (_binding == null) return

        val bulb = state.selectedBulb
        val isLit = state.isLit
        val brightness = state.brightnessPercent

        // 1. Load Transparent Light Source Illustration Body Asset
        binding.ivBulbIllustration.setImageResource(bulb.illustrationResId)
        binding.tvOverlayBulbName.text = bulb.displayName

        // 2. Hide ALL Type-Specific Environmental & Internal Emitter Layer Views
        hideAllIlluminationGlows()

        // 3. Render Multi-Layer Hybrid Asset Simulation when LIT
        if (isLit) {
            // Nonlinear natural perception brightness curve: 0-10% dim, 25% low, 50% medium, 75% bright, 100% full
            val rawRatio = (brightness / 100f).coerceIn(0.0f, 1.0f)
            val effectiveIntensity = rawRatio.toDouble().pow(1.4).toFloat()

            // Active Type-Specific Environmental Illumination Layer
            val activeEnvView = when (bulb.type) {
                BulbType.STANDARD -> binding.viewEnvStandard
                BulbType.TUBE_LIGHT -> binding.viewEnvTube
                BulbType.SPOTLIGHT -> binding.viewEnvSpotlight
                BulbType.TORCH -> binding.viewEnvTorch
                BulbType.EDISON -> binding.viewEnvEdison
                BulbType.SMART -> binding.viewEnvSmart
                BulbType.CANDLE -> binding.viewEnvCandle
            }

            // Active Type-Specific Internal Emitter Core Layer
            val activeEmitterView = when (bulb.type) {
                BulbType.STANDARD -> binding.viewEmitterStandard
                BulbType.TUBE_LIGHT -> binding.viewEmitterTube
                BulbType.SPOTLIGHT -> binding.viewEmitterSpotlight
                BulbType.TORCH -> binding.viewEmitterTorch
                BulbType.EDISON -> binding.viewEmitterEdison
                BulbType.SMART -> binding.viewEmitterSmart
                BulbType.CANDLE -> binding.viewEmitterCandle
            }

            // 3.1 Broad Full-Screen Display Illumination (Entire screen glows with bulb light color)
            val darkR = 15
            val darkG = 15
            val darkB = 20

            val targetColor = bulb.glowColor
            val targetR = Color.red(targetColor)
            val targetG = Color.green(targetColor)
            val targetB = Color.blue(targetColor)

            val currentR = (darkR + (targetR - darkR) * effectiveIntensity).toInt().coerceIn(0, 255)
            val currentG = (darkG + (targetG - darkG) * effectiveIntensity).toInt().coerceIn(0, 255)
            val currentB = (darkB + (targetB - darkB) * effectiveIntensity).toInt().coerceIn(0, 255)

            val currentColor = Color.rgb(currentR, currentG, currentB)
            binding.lightBulbRoot.setBackgroundColor(currentColor)
            applyBulbColorToSystemBars(currentColor)

            activeEnvView.visibility = if (brightness > 0) View.VISIBLE else View.INVISIBLE
            activeEnvView.alpha = effectiveIntensity

            activeEmitterView.visibility = if (brightness > 0) View.VISIBLE else View.INVISIBLE
            activeEmitterView.alpha = (effectiveIntensity * 1.15f).coerceIn(0.0f, 1.0f)

            // Display transparent light source body asset in full detail with luminous emission
            binding.ivBulbIllustration.clearColorFilter()
            binding.ivBulbIllustration.alpha = 0.85f + (effectiveIntensity * 0.15f)

            binding.tvOverlayHint.text = if (brightness > 0) "Swipe ↕ brightness ($brightness%) • ↔ change bulb" else "Swipe up to increase brightness"
        } else {
            // Power OFF State: Visible unlit physical body in dark environment, no environmental illumination
            val offColor = Color.parseColor("#0F0F14")
            binding.lightBulbRoot.setBackgroundColor(offColor)
            applyBulbColorToSystemBars(offColor)

            binding.ivBulbIllustration.setColorFilter(Color.parseColor("#80151520"), PorterDuff.Mode.MULTIPLY)
            binding.ivBulbIllustration.alpha = 0.35f
            binding.tvOverlayHint.text = "Tap screen to turn ON"
        }

        // 4. Update Window Screen Brightness
        applyScreenBrightness()
    }

    private fun applyBulbColorToSystemBars(color: Int) {
        val window = activity?.window ?: return
        window.statusBarColor = color
        window.navigationBarColor = color
        window.setBackgroundDrawable(ColorDrawable(color))

        val luminance = (0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)) / 255.0
        val isBright = luminance > 0.5

        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.isAppearanceLightStatusBars = isBright
        insetsController.isAppearanceLightNavigationBars = isBright
    }

    private fun hideAllIlluminationGlows() {
        binding.viewEnvStandard.visibility = View.GONE
        binding.viewEnvTube.visibility = View.GONE
        binding.viewEnvSpotlight.visibility = View.GONE
        binding.viewEnvTorch.visibility = View.GONE
        binding.viewEnvEdison.visibility = View.GONE
        binding.viewEnvSmart.visibility = View.GONE
        binding.viewEnvCandle.visibility = View.GONE

        binding.viewEmitterStandard.visibility = View.GONE
        binding.viewEmitterTube.visibility = View.GONE
        binding.viewEmitterSpotlight.visibility = View.GONE
        binding.viewEmitterTorch.visibility = View.GONE
        binding.viewEmitterEdison.visibility = View.GONE
        binding.viewEmitterSmart.visibility = View.GONE
        binding.viewEmitterCandle.visibility = View.GONE
    }

    private fun applyScreenBrightness() {
        val activity = activity ?: return
        val layoutParams = activity.window.attributes

        if (state.isLit) {
            val brightnessFloat = (state.brightnessPercent / 100f).coerceIn(0.05f, 1.0f)
            layoutParams.screenBrightness = brightnessFloat
        } else {
            layoutParams.screenBrightness = 0.01f
        }

        activity.window.attributes = layoutParams
    }

    override fun onResume() {
        super.onResume()
        setupFullscreenPresentation()

        // Capture original screen brightness
        activity?.window?.attributes?.let {
            originalBrightness = it.screenBrightness
        }

        // Keep screen on while fragment active
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        applyScreenBrightness()
        updateRenderState()
    }

    override fun onPause() {
        super.onPause()
        restoreSystemUI()

        // Clear KEEP_SCREEN_ON flag
        activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Restore original screen brightness safely
        activity?.window?.attributes?.let { layoutParams ->
            if (originalBrightness < 0) {
                layoutParams.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            } else {
                layoutParams.screenBrightness = originalBrightness
            }
            activity?.window?.attributes = layoutParams
        }
    }

    private fun setupFullscreenPresentation() {
        (activity as? androidx.appcompat.app.AppCompatActivity)?.supportActionBar?.hide()
        activity?.findViewById<View>(R.id.toolbar)?.visibility = View.GONE

        activity?.window?.let { window ->
            // 1. Capture original system bar colors once (guard against capturing 0/transparent)
            if (window.statusBarColor != Color.TRANSPARENT && window.statusBarColor != 0) {
                originalStatusBarColor = window.statusBarColor
            }
            if (window.navigationBarColor != Color.TRANSPARENT && window.navigationBarColor != 0) {
                originalNavigationBarColor = window.navigationBarColor
            }

            // 2. Clear window flags that could draw translucent or default system bar backgrounds
            window.clearFlags(
                WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS or
                WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION
            )
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)

            // 3. Ensure decor view lays out behind system bars
            WindowCompat.setDecorFitsSystemWindows(window, false)

            // 4. Extend layout into display cutout / notch / status bar short edge areas (API 28+)
            val layoutParams = window.attributes
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                originalCutoutMode = layoutParams.layoutInDisplayCutoutMode
                layoutParams.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                window.attributes = layoutParams
            }

            // 5. Hide system bars with transient swipe behavior
            val insetsController = WindowCompat.getInsetsController(window, window.decorView)
            insetsController.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            insetsController.hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun restoreSystemUI() {
        (activity as? androidx.appcompat.app.AppCompatActivity)?.supportActionBar?.show()
        activity?.findViewById<View>(R.id.toolbar)?.visibility = View.VISIBLE

        activity?.window?.let { window ->
            val currentContext = context ?: activity

            // 1. Determine active app theme
            val isLightTheme = currentContext?.getSharedPreferences(AppConstants.PREFS_NAME, Context.MODE_PRIVATE)
                ?.getString(AppConstants.KEY_THEME, AppConstants.THEME_DARK) == AppConstants.THEME_LIGHT

            // 2. Restore window background according to current theme
            val bgRes = if (isLightTheme) R.color.fn_bg_light else R.color.fn_bg_dark
            window.setBackgroundDrawableResource(bgRes)

            // 3. Restore cutout mode on API 28+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val layoutParams = window.attributes
                layoutParams.layoutInDisplayCutoutMode = originalCutoutMode
                window.attributes = layoutParams
            }

            // 4. Restore system bar colors with theme fallback
            val fallbackColor = if (currentContext != null) ContextCompat.getColor(currentContext, bgRes) else Color.BLACK
            window.statusBarColor = if (originalStatusBarColor != 0 && originalStatusBarColor != Color.TRANSPARENT) {
                originalStatusBarColor
            } else {
                fallbackColor
            }
            window.navigationBarColor = if (originalNavigationBarColor != 0 && originalNavigationBarColor != Color.TRANSPARENT) {
                originalNavigationBarColor
            } else {
                fallbackColor
            }

            // 5. Restore system bars and theme icon appearance
            val insetsController = WindowCompat.getInsetsController(window, window.decorView)
            insetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
            insetsController.show(WindowInsetsCompat.Type.systemBars())
            insetsController.isAppearanceLightStatusBars = isLightTheme
            insetsController.isAppearanceLightNavigationBars = isLightTheme
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        restoreSystemUI()
        hideHudHandler.removeCallbacksAndMessages(null)
        _binding = null
    }
}