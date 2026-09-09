package com.saitamagrs.flashnow.fragments

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.gridlayout.widget.GridLayout
import com.google.android.gms.ads.AdView
import com.saitamagrs.flashnow.MainActivity
import com.saitamagrs.flashnow.R
import com.saitamagrs.flashnow.databinding.FragmentTimerBinding
import com.saitamagrs.flashnow.services.FlashlightTimerService
import com.saitamagrs.flashnow.utils.FlashlightManager
import com.saitamagrs.flashnow.utils.PermissionManager
import com.saitamagrs.flashnow.utils.TimerManager

class TimerFragment : BaseAdFragment() {

    override val adView: AdView? get() = binding.adView
    private var _binding: FragmentTimerBinding? = null
    private val binding get() = _binding!!

    private lateinit var timerManager: TimerManager
    private var isLockModeEnabled = false
    private var currentRemainingTime: Long = 0
    private var currentTotalDuration: Long = 0
    private var isTimerRunning = false
    private var pendingTimerMinutes: Int? = null

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            pendingTimerMinutes?.let { minutes ->
                actuallyStartTimer(minutes)
            }
        } else {
            val permission = Manifest.permission.POST_NOTIFICATIONS
            // ✅ Check if the permission is permanently denied
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                !shouldShowRequestPermissionRationale(permission)) {

                // Show dialog directing user to manual settings
                PermissionManager.showGoToSettingsDialog(requireContext())
            } else {
                // Normal denial, show toast
                showMessage("Permission denied. Timer will run without a notification.")
            }

            // Start timer anyway as per current requirement
            pendingTimerMinutes?.let { actuallyStartTimer(it) }
        }
        pendingTimerMinutes = null
    }

    // CRITICAL FIX: Store the callback reference
    private var backPressCallback: OnBackPressedCallback? = null

    companion object {
        var isTimerRunningInBackground = false
        var isLockModeActive = false
    }



    private val timerUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!isAdded) return
            Log.d("TimerFragment", "Broadcast received: ${intent.action}")
            when (intent.action) {
                FlashlightTimerService.ACTION_TIMER_TICK -> {
                    val remainingTime = intent.getLongExtra("remaining_time", 0)
                    val totalDuration = intent.getLongExtra("total_duration", 0)
                    Log.d("TimerFragment", "Timer tick: ${formatTime(remainingTime)}")

                    currentRemainingTime = remainingTime
                    currentTotalDuration = totalDuration
                    isTimerRunning = true
                    isTimerRunningInBackground = true

                    //  updateTimerUI(remainingTime, totalDuration)
                    // Use requireActivity() only after isAdded check
                    requireActivity().runOnUiThread {
                        if (isAdded) { // <-- ADD INSIDE runOnUiThread
                            updateTimerUI(remainingTime, totalDuration)
                        }
                    }
                }

                FlashlightTimerService.ACTION_TIMER_FINISHED -> {
                    Log.d("TimerFragment", "Timer finished broadcast")
                    isTimerRunning = false
                    isTimerRunningInBackground = false
                    handleTimerFinished()
                }

                FlashlightTimerService.ACTION_CLOSE_APP -> {
                    Log.d("TimerFragment", "Close app broadcast")
                    if (isLockModeEnabled) {
                        requireActivity().finishAffinity()
                    }
                }
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentTimerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        timerManager = TimerManager(requireContext())

        // Load lock mode state from SharedPreferences
        val prefs = requireContext().getSharedPreferences("timer_prefs", Context.MODE_PRIVATE)
        isLockModeEnabled = prefs.getBoolean("lock_mode_enabled", false)
        isTimerRunning = prefs.getBoolean("timer_running", false)
        isTimerRunningInBackground = isTimerRunning
        isLockModeActive = isLockModeEnabled

        // Check if timer should actually be running
        validateTimerState()

        setupUI()
        setupPresetsGrid()
        setupCustomTimer()


        if (isTimerRunning) {
            currentRemainingTime = prefs.getLong("remaining_time", 0)
            currentTotalDuration = prefs.getLong("total_duration", 0)

            // ⏳ Recalculate remaining time if it was minimized
            if (currentRemainingTime > 0) {
                updateTimerUI(currentRemainingTime, currentTotalDuration)
                updateLockModeUI() // Restores UI (dimmed screen, button text)

                // ✅ CRITICAL: Re-apply the back-press blocking
                if (isLockModeEnabled) {
                    lockNavigation()
                }
            } else {
                resetAllStates()
            }
        }

        checkFlashlightStatus()

        // Only lock navigation if both timer is running AND lock mode is enabled

    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLong("remaining_time", currentRemainingTime)
        outState.putLong("total_duration", currentTotalDuration)
        outState.putBoolean("lock_mode", isLockModeEnabled)
        outState.putBoolean("timer_running", isTimerRunning)
    }

    private fun navigateToHomeFragment() {
        // Check if we're already on HomeFragment
        val currentFragment = requireActivity().supportFragmentManager.findFragmentById(R.id.fragment_container)
        if (currentFragment is HomeFragment) {
            // Already on HomeFragment, just update UI
            (currentFragment as HomeFragment).updateFlashlightUI()
            return
        }

        // Navigate to HomeFragment
        val homeFragment = HomeFragment()
        requireActivity().supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, homeFragment)
            .commit()

        // Hide back button on HomeFragment
        (requireActivity() as? MainActivity)?.hideBackButton()
    }

    private fun resetAllStatesAndNavigate() {
        Log.d("TimerFragment", "Resetting all states and navigating")

        // Reset UI
        binding.cardActiveTimer.visibility = View.GONE
        setTimerControlsEnabled(true)

        // Reset variables
        currentRemainingTime = 0
        currentTotalDuration = 0
        isTimerRunning = false
        isTimerRunningInBackground = false
        isLockModeEnabled = false
        isLockModeActive = false

        // Reset SharedPreferences
        val prefs = requireContext().getSharedPreferences("timer_prefs", Context.MODE_PRIVATE)
        prefs.edit().apply {
            putBoolean("lock_mode_enabled", false)
            putBoolean("timer_running", false)
            putLong("remaining_time", 0)
            putLong("total_duration", 0)
            apply()
        }

        // Unlock navigation
        unlockNavigation()
        updateLockModeUI()

        // Navigate to HomeFragment

        // navigateToHomeFragment()

        Log.d("TimerFragment", "All states reset and navigation complete")
    }


    private fun validateTimerState() {
        val prefs = requireContext().getSharedPreferences("timer_prefs", Context.MODE_PRIVATE)
        val isPaused = prefs.getBoolean("is_paused", false)

        try {
            val isFlashActuallyOn = FlashlightManager.isFlashlightOn(requireContext())

            // ✅ Only reset if the timer should be running AND flash is off AND we are NOT intentionally paused
            if (isTimerRunning && !isFlashActuallyOn && !isPaused) {
                Log.d("TimerFragment", "Unexpected flash off detected - resetting")
                resetAllStates()
            }
        } catch (e: Exception) {
            // Only reset if we aren't in a paused state
            if (isTimerRunning && !isPaused) resetAllStates()
        }
    }

    private fun resetAllStates() {
        Log.d("TimerFragment", "Resetting all timer states")

        // Reset UI
        binding.cardActiveTimer.visibility = View.GONE
        setTimerControlsEnabled(true)

        // Reset variables
        currentRemainingTime = 0
        currentTotalDuration = 0
        isTimerRunning = false
        isTimerRunningInBackground = false
        isLockModeEnabled = false
        isLockModeActive = false

        // Reset SharedPreferences
        val prefs = requireContext().getSharedPreferences("timer_prefs", Context.MODE_PRIVATE)
        prefs.edit().apply {
            putBoolean("lock_mode_enabled", false)
            putBoolean("timer_running", false)
            putLong("remaining_time", 0)
            putLong("total_duration", 0)
            apply()
        }
        // Ensure flashlight is off
        try {
            FlashlightManager.turnOffFlashlight(requireContext())
        } catch (e: Exception) {
            Log.e("TimerFragment", "Error turning off flashlight", e)
        }

        // Unlock navigation
        unlockNavigation()
        updateLockModeUI()

        Log.d("TimerFragment", "All states reset complete")
    }

    private fun showLockModeStopWarning() {
        AlertDialog.Builder(requireContext())
            .setTitle("🔒 Lock Mode Active")
            .setMessage("Cannot stop timer while Lock Mode is enabled.\n\nPlease disable Lock Mode first to stop the timer.")
            .setPositiveButton("Disable Lock Mode") { _, _ ->
                resetLockModeState()
                showMessage("Lock mode disabled - You can now stop the timer")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun setupUI() {
        binding.btnStopTimer.setOnClickListener {
            if (isLockModeEnabled) {
                showLockModeStopWarning()
            } else {
                stopTimer()
            }
        }

        binding.btnLockMode.setOnClickListener {
            if (binding.cardActiveTimer.visibility == View.VISIBLE) {
                val newLockModeState = !isLockModeEnabled

                if (newLockModeState) {
                    // Turning lock mode ON
                    isLockModeEnabled = true
                    isLockModeActive = true

                    val prefs = requireContext().getSharedPreferences("timer_prefs", Context.MODE_PRIVATE)
                    prefs.edit().putBoolean("lock_mode_enabled", true).apply()

                    updateLockModeUI()
                    lockNavigation()
                    showMessage("🔒 Lock mode enabled")

                } else {
                    // Turning lock mode OFF - IMPORTANT: Remove callback first
                    unlockNavigation() // This removes the callback

                    isLockModeEnabled = false
                    isLockModeActive = false

                    val prefs = requireContext().getSharedPreferences("timer_prefs", Context.MODE_PRIVATE)
                    prefs.edit().putBoolean("lock_mode_enabled", false).apply()

                    updateLockModeUI()
                    showMessage("🔓 Lock mode disabled")
                }
            } else {
                showMessage("Start a timer first to enable lock mode")
            }
        }
    }

    // FIXED: Properly store and remove back press callback
    private fun lockNavigation() {
        // Remove any existing callback first
        backPressCallback?.remove()

        // Create new callback
        backPressCallback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                showMessage("Cannot navigate away in Lock Mode. Disable lock mode first.")
            }
        }

        // Add the callback to the dispatcher
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backPressCallback!!)

        // Disable toolbar navigation
        (requireActivity() as? MainActivity)?.setToolbarNavigationEnabled(false)

        // Hide back button in toolbar
        (requireActivity() as? MainActivity)?.hideBackButton()

        Log.d("TimerFragment", "Navigation locked")
    }

    // FIXED: Properly remove the callback
    private fun unlockNavigation() {
        // IMPORTANT: Remove the callback to restore normal back navigation
        backPressCallback?.remove()
        backPressCallback = null

        // Re-enable toolbar navigation
        (requireActivity() as? MainActivity)?.setToolbarNavigationEnabled(true)

        // Show back button if needed
        (requireActivity() as? MainActivity)?.showBackButtonIfNeeded()

        Log.d("TimerFragment", "Navigation unlocked")
    }

    private fun updateLockModeUI() {
        if (!isAdded) return
        requireActivity().runOnUiThread {
            if (!isAdded) return@runOnUiThread
            if (isLockModeEnabled) {
                binding.btnLockMode.text = "🔒 Lock Mode ON"
                binding.btnLockMode.setBackgroundColor(
                    ContextCompat.getColor(requireContext(), R.color.accent_green)
                )

                // Disable stop button visually
                binding.btnStopTimer.isEnabled = false
                binding.btnStopTimer.alpha = 0.5f
                binding.btnStopTimer.text = "⛔ Stop Timer"

                // Dim the screen and keep it on
                requireActivity().window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                val layoutParams = requireActivity().window.attributes
                layoutParams.screenBrightness = 0.1f
                requireActivity().window.attributes = layoutParams

                binding.lockModeInfo.visibility = View.VISIBLE
            } else {
                binding.btnLockMode.text = "🔓 Lock Mode OFF"
                binding.btnLockMode.setBackgroundColor(
                    ContextCompat.getColor(requireContext(), R.color.accent_blue)
                )

                // Enable stop button
                binding.btnStopTimer.isEnabled = true
                binding.btnStopTimer.alpha = 1.0f
                binding.btnStopTimer.text = "STOP TIMER"

                // Restore normal screen
                requireActivity().window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                val layoutParams = requireActivity().window.attributes
                layoutParams.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                requireActivity().window.attributes = layoutParams

                binding.lockModeInfo.visibility = View.GONE
            }
        }
    }

    private fun updateTimerUI(remainingTime: Long, totalDuration: Long) {
        requireActivity().runOnUiThread {
            binding.cardActiveTimer.visibility = View.VISIBLE
            binding.tvTimeRemaining.text = formatTime(remainingTime)
            binding.progressTimer.progress = if (totalDuration > 0) {
                ((totalDuration - remainingTime) * 100 / totalDuration).toInt()
            } else 0

            setTimerControlsEnabled(false)
            Log.d("TimerFragment", "UI Updated: ${formatTime(remainingTime)}")
        }
    }

    private fun setTimerControlsEnabled(enabled: Boolean) {
        binding.btnStartCustom.isEnabled = enabled
        for (i in 0 until binding.gridPresets.childCount) {
            binding.gridPresets.getChildAt(i).isEnabled = enabled
        }
        binding.etMinutes.isEnabled = enabled
        binding.seekbarTimer.isEnabled = enabled

        if (enabled) {
            binding.btnStartCustom.alpha = 1.0f
            binding.gridPresets.alpha = 1.0f
        } else {
            binding.btnStartCustom.alpha = 0.5f
            binding.gridPresets.alpha = 0.5f
        }
    }

    private fun setupPresetsGrid() {
        binding.gridPresets.removeAllViews()
        val inflater = LayoutInflater.from(requireContext())
        for (preset in TimerManager.presetDurations) {
            val itemView = inflater.inflate(R.layout.item_timer_preset, binding.gridPresets, false)
            val titleTextView = itemView.findViewById<TextView>(R.id.tv_preset_title)
            val descTextView = itemView.findViewById<TextView>(R.id.tv_preset_description)
            titleTextView.text = preset.title
            descTextView.text = preset.description
            if (preset.minutes == 0) {
                titleTextView.setTextColor(
                    ContextCompat.getColor(requireContext(), R.color.accent_yellow)
                )
            }
            itemView.setOnClickListener {
                if (preset.minutes == 0) {
                    binding.etMinutes.requestFocus()
                } else {
                    startTimer(preset.minutes)
                }
            }
            val params = GridLayout.LayoutParams().apply {
                width = 0
                height = GridLayout.LayoutParams.WRAP_CONTENT
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                rowSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
            }
            binding.gridPresets.addView(itemView, params)
        }
    }

    private fun setupCustomTimer() {
        binding.seekbarTimer.setOnSeekBarChangeListener(object :
            android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(
                seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean
            ) {
                val minutes = if (progress == 0) 1 else progress
                if (fromUser) {
                    binding.etMinutes.setText(minutes.toString())
                    updateSelectedTimeText(minutes)
                }
            }

            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        })

        binding.etMinutes.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) validateAndUpdateInput()
        }

        binding.btnStartCustom.setOnClickListener {
            validateAndUpdateInput()
            val minutes = binding.etMinutes.text.toString().toIntOrNull() ?: 30
            startTimer(minutes)
        }
        updateSelectedTimeText(30)
    }

    private fun validateAndUpdateInput() {
        var minutes = binding.etMinutes.text.toString().toIntOrNull() ?: 1
        minutes = minutes.coerceIn(1, 240)
        binding.etMinutes.setText(minutes.toString())
        binding.seekbarTimer.progress = minutes
        updateSelectedTimeText(minutes)
    }

    private fun updateSelectedTimeText(minutes: Int) {
        val timeText = when {
            minutes < 60 -> "$minutes minutes"
            minutes == 60 -> "1 hour"
            minutes < 120 -> "${minutes / 60} hour ${minutes % 60} min"
            else -> "${minutes / 60} hours"
        }
        binding.tvSelectedTime.text = timeText
    }

    private fun startTimer(minutes: Int) {
        val permission = Manifest.permission.POST_NOTIFICATIONS

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !PermissionManager.isNotificationPermissionGranted(requireContext())) {
            pendingTimerMinutes = minutes

            // ✅ Decide whether to show rationale or request directly
            if (shouldShowRequestPermissionRationale(permission)) {
                // Show the explanation dialog from PermissionManager
                PermissionManager.checkAndRequestNotificationPermission(requireActivity(), requestPermissionLauncher)
            } else {
                // First time or already permanently denied.
                // System will show prompt if it's the first time, otherwise callback handles it.
                requestPermissionLauncher.launch(permission)
            }
        } else {
            actuallyStartTimer(minutes)
        }
    }

    private fun actuallyStartTimer(minutes: Int) {
        if (minutes <= 0) {
            showMessage("Please set a valid time")
            return
        }

        timerManager.startTimer(minutes)
        showMessage("Timer started for $minutes minutes")

        // Update states
        currentRemainingTime = minutes * 60 * 1000L
        currentTotalDuration = minutes * 60 * 1000L
        isTimerRunning = true
        isTimerRunningInBackground = true

        // Save to SharedPreferences
        val prefs = requireContext().getSharedPreferences("timer_prefs", Context.MODE_PRIVATE)
        prefs.edit().apply {
            putBoolean("timer_running", true)
            putLong("remaining_time", currentRemainingTime)
            putLong("total_duration", currentTotalDuration)
            apply()
        }

        updateTimerUI(currentRemainingTime, currentTotalDuration)
    }

    private fun resetLockModeState() {
        isLockModeEnabled = false
        isLockModeActive = false

        val prefs = requireContext().getSharedPreferences("timer_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("lock_mode_enabled", false).apply()

        updateLockModeUI()
        unlockNavigation() // Ensure navigation is unlocked
    }

    private fun resetTimerState() {
        binding.cardActiveTimer.visibility = View.GONE
        setTimerControlsEnabled(true)
        currentRemainingTime = 0
        currentTotalDuration = 0
        isTimerRunning = false
        isTimerRunningInBackground = false

        // Make sure to unlock navigation when timer stops
        unlockNavigation()

        // Reset lock mode
        resetLockModeState()
    }

    /* private fun stopTimer() {
         if (!isLockModeEnabled) {
             timerManager.stopTimer()
             resetTimerState()
             showMessage("Timer stopped")
         } else {
             showLockModeStopWarning()
         }
     }*/
    private fun resetAllTimerStates() {
        // Reset variables
        isTimerRunning = false
        isTimerRunningInBackground = false
        isLockModeEnabled = false
        isLockModeActive = false

        // Reset SharedPreferences
        val prefs = requireContext().getSharedPreferences("timer_prefs", Context.MODE_PRIVATE)
        prefs.edit().apply {
            putBoolean("lock_mode_enabled", false)
            putBoolean("timer_running", false)
            putLong("remaining_time", 0)
            putLong("total_duration", 0)
            apply()
        }

        // Ensure flashlight is off
        try {
            FlashlightManager.turnOffFlashlight(requireContext())
        } catch (e: Exception) {
            Log.e("TimerFragment", "Error turning off flashlight", e)
        }

        // Unlock navigation
        unlockNavigation()
        (requireActivity() as? MainActivity)?.updateHomeFragmentFlashlightUI()
    }
    private fun performStopTimer() {
        // Stop the timer service
        timerManager.stopTimer()

        // Reset all states
        resetAllTimerStates()

        // Reset UI
        resetTimerUI()

        // Show message
        showMessage("Timer stopped")

        // Stay in TimerFragment - user can set new timer
    }

    private fun resetTimerUI() {
        if (!isAdded) return
        requireActivity().runOnUiThread {
            if (!isAdded) return@runOnUiThread
            // Hide active timer card
            binding.cardActiveTimer.visibility = View.GONE

            // Enable all controls for setting new timer
            setTimerControlsEnabled(true)

            // Reset lock mode UI
            updateLockModeUI()

            // Reset progress and time display
            binding.tvTimeRemaining.text = "00:00"
            binding.progressTimer.progress = 0

            // Reset seekbar and edittext
            binding.seekbarTimer.progress = 30
            binding.etMinutes.setText("30")
            updateSelectedTimeText(30)

            // Ensure flashlight is off (just in case)
            try {
                FlashlightManager.turnOffFlashlight(requireContext())
            } catch (e: Exception) {
                Log.e("TimerFragment", "Error turning off flashlight", e)
            }

            Log.d("TimerFragment", "Timer UI reset complete - staying in TimerFragment")
        }
    }

    private fun stopTimer() {
        if (!isLockModeEnabled) {
            // Show confirmation dialog
            AlertDialog.Builder(requireContext())
                .setTitle("Stop Timer")
                .setMessage("Are you sure you want to stop the timer?")
                .setPositiveButton("Yes") { _, _ ->
                    performStopTimer()
                }
                .setNegativeButton("No", null)
                .show()
        } else {
            showLockModeStopWarning()
        }
    }

    private fun handleTimerFinished() {
        if (isLockModeEnabled) {
            Log.d("TimerFragment", "Lock mode: Auto-closing app")
            showMessage("Timer finished - Flashlight turned off")

            // Unlock navigation before closing
            unlockNavigation()

            Handler(Looper.getMainLooper()).postDelayed({
                requireActivity().finishAffinity()
            }, 1000)
        } else {
            Log.d("TimerFragment", "Non-lock mode: Timer finished")
            // Just reset the timer state, don't navigate
            resetTimerUI()
            resetAllTimerStates()

            if (isAdded && isVisible) {
                showTimerFinishedDialog()
            } else {
                showMessage("Timer finished - Flashlight turned off")
            }
        }
    }

    private fun showTimerFinishedDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle("⏰ Timer Finished")
            .setMessage("Flashlight has been turned off.")
            .setPositiveButton("OK") { _, _ -> }
            .setCancelable(false)
            .show()
    }

    private fun checkFlashlightStatus() {
        try {
            val isFlashOn = FlashlightManager.isFlashlightOn(requireContext())
            if (!isFlashOn && isTimerRunning) {
                Log.d("TimerFragment", "Flashlight turned off manually - stopping timer")
             //   stopTimer()
            }
        } catch (e: Exception) {
            Log.e("TimerFragment", "Error checking flashlight status", e)
        }
    }

    private fun formatTime(milliseconds: Long): String {
        if (milliseconds <= 0) return "00:00"
        val totalSeconds = milliseconds / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format("%02d:%02d", minutes, seconds)
    }

    private fun showMessage(message: String) {
        if (isAdded) {
            android.widget.Toast.makeText(
                requireContext(), message, android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }
    private fun restoreTimerState() {
        val prefs = requireContext().getSharedPreferences("timer_prefs", Context.MODE_PRIVATE)
        // ✅ Load the saved states
        isLockModeEnabled = prefs.getBoolean("lock_mode_enabled", false)
        isTimerRunning = prefs.getBoolean("timer_running", false)

        // Update the static flag for MainActivity
        isLockModeActive = isLockModeEnabled

        if (isTimerRunning) {
            updateLockModeUI() // This will dim the screen if Lock Mode is enabled
            if (isLockModeEnabled) lockNavigation()
        }
    }
    //Replace the existing reset methods with one unified method
    private fun resetToIdleState() {
        Log.d("TimerFragment", "Resetting to idle state")

        // Reset UI
        binding.cardActiveTimer.visibility = View.GONE
        setTimerControlsEnabled(true)
        binding.tvTimeRemaining.text = "00:00"
        binding.progressTimer.progress = 0
        binding.seekbarTimer.progress = 30
        binding.etMinutes.setText("30")
        updateSelectedTimeText(30)

        // Reset variables
        currentRemainingTime = 0
        currentTotalDuration = 0
        isTimerRunning = false
        isLockModeEnabled = false
        isLockModeActive = false

        // Clear SharedPreferences
        val prefs = requireContext().getSharedPreferences("timer_prefs", Context.MODE_PRIVATE)
        prefs.edit().apply {
            putBoolean("lock_mode_enabled", false)
            putBoolean("timer_running", false)
            putLong("remaining_time", 0)
            putLong("total_duration", 0)
            apply()
        }

        // Turn off flashlight safely
        try {
            FlashlightManager.turnOffFlashlight(requireContext())
        } catch (e: Exception) {
            Log.e("TimerFragment", "Error turning off flashlight", e)
        }

        // Unlock navigation and update lock mode UI
        unlockNavigation()
        updateLockModeUI()

        Log.d("TimerFragment", "Reset to idle state complete")
    }



    override fun onResume() {
        super.onResume()
        val filter = IntentFilter().apply {
            addAction(FlashlightTimerService.ACTION_TIMER_TICK)
            addAction(FlashlightTimerService.ACTION_TIMER_FINISHED)
            addAction(FlashlightTimerService.ACTION_CLOSE_APP)
        }
        ContextCompat.registerReceiver(
            requireContext(), timerUpdateReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED
        )

        // Re-validate timer state every time fragment resumes
        validateTimerState()
        checkFlashlightStatus()
        Log.d("TimerFragment", "Fragment resumed - timer running: $isTimerRunning")
    }

    override fun onPause() {
        super.onPause()
        try {
            requireContext().unregisterReceiver(timerUpdateReceiver)
        } catch (e: IllegalArgumentException) {
            Log.d("TimerFragment", "Receiver not registered")
        }
    }





    override fun onDestroyView() {
        super.onDestroyView()

        // IMPORTANT: Remove callback when fragment view is destroyed
        backPressCallback?.remove()
        backPressCallback = null

        requireActivity().window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val layoutParams = requireActivity().window.attributes
        layoutParams.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        requireActivity().window.attributes = layoutParams

        _binding = null
    }

    override fun onDestroy() {
        super.onDestroy()
        backPressCallback?.remove()
        backPressCallback = null
    }


}