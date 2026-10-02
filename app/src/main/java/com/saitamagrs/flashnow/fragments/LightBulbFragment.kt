package com.saitamagrs.flashnow.fragments

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.PorterDuff
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

        ViewCompat.setOnApplyWindowInsetsListener(view) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val density = resources.displayMetrics.density

            binding.headerBar.setPadding(
                (16 * density).toInt(),
                systemBars.top + (8 * density).toInt(),
                (16 * density).toInt(),
                (8 * density).toInt()
            )

            val layoutParams = binding.bottomInfoPill.layoutParams as? RelativeLayout.LayoutParams
            layoutParams?.setMargins(
                0, 0, 0,
                systemBars.bottom + (20 * density).toInt()
            )
            binding.bottomInfoPill.layoutParams = layoutParams
            insets
        }

        setupUI()
        setupDualGestureListener()
        updateRenderState()
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

            binding.lightBulbRoot.setBackgroundColor(Color.rgb(currentR, currentG, currentB))

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
            binding.lightBulbRoot.setBackgroundColor(Color.parseColor("#0F0F14"))
            binding.ivBulbIllustration.setColorFilter(Color.parseColor("#80151520"), PorterDuff.Mode.MULTIPLY)
            binding.ivBulbIllustration.alpha = 0.35f
            binding.tvOverlayHint.text = "Tap screen to turn ON"
        }

        // 4. Update Window Screen Brightness
        applyScreenBrightness()
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
        hideSystemUI()

        // Capture original screen brightness
        activity?.window?.attributes?.let {
            originalBrightness = it.screenBrightness
        }

        // Keep screen on while fragment active
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        applyScreenBrightness()
    }

    override fun onPause() {
        super.onPause()
        showSystemUI()

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

    private fun hideSystemUI() {
        activity?.findViewById<Toolbar>(R.id.toolbar)?.visibility = View.GONE

        val window = activity?.window ?: return
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        insetsController.hide(WindowInsetsCompat.Type.systemBars())
    }

    private fun showSystemUI() {
        activity?.findViewById<Toolbar>(R.id.toolbar)?.visibility = View.VISIBLE

        val window = activity?.window ?: return
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.show(WindowInsetsCompat.Type.systemBars())
    }

    override fun onDestroyView() {
        super.onDestroyView()
        hideHudHandler.removeCallbacksAndMessages(null)
        _binding = null
    }
}