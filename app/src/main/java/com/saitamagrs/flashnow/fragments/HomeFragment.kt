package com.saitamagrs.flashnow.fragments

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.AnimationUtils
import android.widget.CompoundButton
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.viewModels
import com.google.android.gms.ads.AdView
import com.saitamagrs.flashnow.MainActivity
import com.saitamagrs.flashnow.R
import com.saitamagrs.flashnow.databinding.FragmentHomeBinding
import com.saitamagrs.flashnow.services.NotificationAlertService
import com.saitamagrs.flashnow.utils.AppConstants
import com.saitamagrs.flashnow.utils.ClapDetector
import com.saitamagrs.flashnow.utils.FlashlightManager
import com.saitamagrs.flashnow.utils.PermissionManager
import com.saitamagrs.flashnow.utils.TimerManager
import com.saitamagrs.flashnow.viewmodels.HomeViewModel
import kotlin.apply

class HomeFragment : BaseAdFragment() {

    private var flashlightStateListener: ((Boolean) -> Unit)? = null
    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!
    private val viewModel: HomeViewModel by viewModels()

    override val adView: AdView? get() = binding.adView

    private var mediaPlayer: MediaPlayer? = null
    private var hasFlashlight = true

    private val handler = Handler(Looper.getMainLooper())
    private var strobeRunnable: Runnable? = null
    private var sosRunnable: Runnable? = null

    private var clapDetector: ClapDetector? = null

    private var pendingFlashlightAction: (() -> Unit)? = null

    private var notificationSwitchListener: CompoundButton.OnCheckedChangeListener? = null

    private var isNavigating = false // NAVIGATION GUARD

    private fun setNotificationSwitchSilently(checked: Boolean) {
        val currentBinding = _binding ?: return
        currentBinding.notificationSwitch.setOnCheckedChangeListener(null)
        currentBinding.notificationSwitch.isChecked = checked
        currentBinding.notificationSwitch.setOnCheckedChangeListener(notificationSwitchListener)
    }
    
    // Track the interference warning dialog to prevent multiple instances
    private var timerWarningDialog: AlertDialog? = null
    private var permissionReminderDialog: AlertDialog? = null
    private var notificationPermissionDialog: AlertDialog? = null

    private val requestCameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            pendingFlashlightAction?.invoke()
        } else {
            if (!shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
                PermissionManager.showGoToSettingsDialog(requireContext())
            } else {
                Toast.makeText(requireContext(), "Camera permission is required for flashlight features.", Toast.LENGTH_SHORT).show()
            }
        }
        pendingFlashlightAction = null
    }

    private val requestAudioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            startListeningForClaps()
        } else {
            binding.clapSwitch.isChecked = false
            if (!shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
                showGoToSettingsForAudioDialog()
            } else {
                Toast.makeText(requireContext(), "Clap detection requires microphone permission.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setHasOptionsMenu(true)
        // Register broadcast receiver
        val filter = IntentFilter(NotificationAlertService.ACTION_NOTIFICATION_ACCESS_CHANGED)

        val receiverContext = context?.applicationContext ?: requireContext().applicationContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            receiverContext.registerReceiver(notificationPermissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            // Older Android versions
            ContextCompat.registerReceiver(
                receiverContext,
                notificationPermissionReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED)
        }
        


        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, 0, systemBars.right, systemBars.bottom)
            insets
        }

        // Initialize flashlight
        val flashlightController = FlashlightManager.getInstance(requireContext())

        flashlightStateListener = { _ ->
            if (isAdded) {
                handler.post {
                    if (isAdded && _binding != null) {
                        updateUI()
                    }
                }
            }
        }

        //flashlightController.addOnFlashlightStateChangeListener(flashlightStateListener)
        // Pass the listener (using the safe-call or snon-null assertion)
        flashlightStateListener?.let {
            flashlightController.addOnFlashlightStateChangeListener(it)
        }

        if (!requireActivity().packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH)) {
            hasFlashlight = false
            handleNoFlashlight()
            return
        }

        try {
            mediaPlayer = MediaPlayer.create(requireContext(), R.raw.button_sound)
        } catch (e: Exception) {
            Log.e("HomeFragment", "Error creating MediaPlayer: ${e.message}")
            mediaPlayer = null
        }

        setupClickListeners()
        observeViewModel()

        // Initial UI update
        updateFlashlightUI()

    }

    private fun observeViewModel() {
        viewModel.sosActive.observe(viewLifecycleOwner) { isActive ->
            updatePatternButtons()
        }

        viewModel.strobeActive.observe(viewLifecycleOwner) { isActive ->
            updatePatternButtons()
        }
    }


    private fun handleNoFlashlight() {
        binding.tvNoFlashlightWarning.visibility = View.VISIBLE
        binding.cardFlashToggle.visibility = View.GONE
        binding.tvStatusIndicator.visibility = View.GONE
        binding.tvFlashStatus.visibility = View.GONE

        binding.btnSos.isEnabled = false
        binding.btnStrobe.isEnabled = false
        binding.btnMorseCode.isEnabled = false
        binding.btnTimer.isEnabled = false
        binding.clapSwitch.isEnabled = false

        binding.btnSos.alpha = 0.5f
        binding.btnStrobe.alpha = 0.5f
        binding.btnMorseCode.alpha = 0.5f
        binding.btnTimer.alpha = 0.5f
    }

    private fun runFlashlightAction(action: () -> Unit) {
        val timerManager = TimerManager(requireContext())
        if (timerManager.isTimerRunning()) {
            showTimerInterferenceWarning()
            return
        }
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            action()
        } else {
            pendingFlashlightAction = action
            requestCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }    }

    private fun playButtonSound() {
        val sharedPrefs = requireContext().getSharedPreferences(AppConstants.PREFS_NAME, Context.MODE_PRIVATE)
        val isSoundEnabled = sharedPrefs.getBoolean(AppConstants.KEY_SOUND_ENABLED, true)

        if (isSoundEnabled) {
            mediaPlayer?.apply {
                if (isPlaying) {
                    seekTo(0)
                }
                start()
            }
        }
    }

    private fun setupClickListeners() {
        binding.cardFlashToggle.setOnClickListener {
            runFlashlightAction {
                playButtonSound()
                if (isFlashlightOn()) {
                    stopAllPatterns()
                } else {
                    FlashlightManager.turnOnFlashlight(requireContext())

                }
                updateUI()
            }
        }

        binding.btnSos.setOnClickListener {
            runFlashlightAction {
                playButtonSound()
                if (viewModel.sosActive.value == true) {
                    stopAllPatterns()
                } else {
                    startSosPattern()

                }
            }
        }
        binding.btnStrobe.setOnClickListener {
            runFlashlightAction {
                playButtonSound()
                if (viewModel.strobeActive.value == true) {
                    stopAllPatterns()
                } else {
                    startStrobePattern()

                }
            }
        }
        binding.btnScreenLight.setOnClickListener { openScreenLight() }
        binding.btnLightBulb.setOnClickListener { openLightBulbScreen() }
        binding.btnMorseCode.setOnClickListener {
            runFlashlightAction { openMorseCodeFragment() }
        }
        binding.btnTimer.setOnClickListener {
            runFlashlightAction { openTimerFragment() }
        }


        binding.clapSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                requestRecordAudioPermission()
            } else {
                stopListeningForClaps()
            }
        }

        notificationSwitchListener = CompoundButton.OnCheckedChangeListener { _, isChecked ->
            val currentContext = context ?: return@OnCheckedChangeListener
            val sharedPrefs = currentContext.getSharedPreferences(AppConstants.PREFS_NAME, Context.MODE_PRIVATE)

            if (isChecked) {
                // User wants to enable notification alerts
                if (isNotificationServiceEnabled()) {
                    // Permission already granted, save preference
                    with(sharedPrefs.edit()) {
                        putBoolean(AppConstants.KEY_NOTIFICATION_SWITCH, true)
                        apply()
                    }
                    Toast.makeText(currentContext, "Notification alerts activated", Toast.LENGTH_SHORT).show()
                } else {
                    // Need to request permission
                    showNotificationPermissionDialog()
                    // Don't save preference yet - wait until permission is granted
                    setNotificationSwitchSilently(false)
                }
            } else {
                // User wants to disable notification alerts
                with(sharedPrefs.edit()) {
                    putBoolean(AppConstants.KEY_NOTIFICATION_SWITCH, false)
                    apply()
                }
                Toast.makeText(currentContext, "Notification alerts deactivated", Toast.LENGTH_SHORT).show()
            }
        }
        binding.notificationSwitch.setOnCheckedChangeListener(notificationSwitchListener)
    }

    private fun requestRecordAudioPermission() {
        when {
            ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED -> {
                startListeningForClaps()
            }
            shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) -> {
                AlertDialog.Builder(requireContext())
                    .setTitle("Permission Needed")
                    .setMessage("To detect claps, this app needs access to your microphone. No audio is ever recorded or stored.")
                    .setPositiveButton("OK") { _, _ ->
                        requestAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                    .setNegativeButton("Cancel") { _, _ -> binding.clapSwitch.isChecked = false }
                    .show()
            }
            else -> {
                requestAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    private fun showGoToSettingsForAudioDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle("Permission Required")
            .setMessage("Microphone permission has been permanently denied. You must enable it in the app settings to use the clap detection feature.")
            .setPositiveButton("Go to Settings") { _, _ ->
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                val uri = Uri.fromParts("package", requireActivity().packageName, null)
                intent.data = uri
                startActivity(intent)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun updateUI() {
        updateMainButtonUI()
        updatePatternButtons()
    }

    private fun updateMainButtonUI() {
        val isAnyLightOn = isFlashlightOn() ||
                (viewModel.sosActive.value == true) ||
                (viewModel.strobeActive.value == true)
        binding.cardFlashToggle.isSelected = isAnyLightOn

        if (isAnyLightOn) {
            binding.ivFlashIcon.setImageResource(R.drawable.ic_power_on)
            binding.tvFlashState.text = "ON"
            if(isAdded) {
                binding.tvStatusIndicator.setTextColor(ContextCompat.getColor(requireContext(), R.color.accent_green))
                binding.tvFlashStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.accent_green))
            }

            when {
                viewModel.sosActive.value == true -> {
                    binding.tvStatusIndicator.text = "SOS ACTIVE"
                    binding.tvFlashStatus.text = "SOS Pattern Running"
                }
                viewModel.strobeActive.value == true -> {
                    binding.tvStatusIndicator.text = "STROBE ACTIVE"
                    binding.tvFlashStatus.text = "Strobe Pattern Running"
                }
                else -> {
                    binding.tvStatusIndicator.text = "ACTIVE"
                    binding.tvFlashStatus.text = "Flashlight ON"
                }
            }
        } else {
            binding.ivFlashIcon.setImageResource(R.drawable.ic_power_off)
            binding.tvFlashState.text = "OFF"
            binding.tvFlashStatus.text = "Tap to Turn On"
            if(isAdded) {
                binding.tvStatusIndicator.setTextColor(ContextCompat.getColor(requireContext(), R.color.accent_blue))
                binding.tvFlashStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
            }
            binding.tvStatusIndicator.text = "READY"
        }
    }

    private fun isFlashlightOn(): Boolean {
        val currentContext = context ?: return false
        return FlashlightManager.isFlashlightOn(currentContext)
    }

    private fun updatePatternButtons() {
        binding.btnSos.isSelected = viewModel.sosActive.value == true
        if (viewModel.sosActive.value == true) startPulseAnimation(binding.btnSos) else binding.btnSos.clearAnimation()

        binding.btnStrobe.isSelected = viewModel.strobeActive.value == true
        if (viewModel.strobeActive.value == true) startStrobeAnimation(binding.btnStrobe) else binding.btnStrobe.clearAnimation()
    }

    private fun startPulseAnimation(view: View) {
        val animation = AnimationUtils.loadAnimation(requireContext(), R.anim.glow_pulse)
        view.startAnimation(animation)
    }

    private fun startStrobeAnimation(view: View) {
        val animation = AnimationUtils.loadAnimation(requireContext(), R.anim.strobe_effect)
        view.startAnimation(animation)
    }

    private fun openScreenLight() {
        if (isNavigating) return // GUARD
        isNavigating = true
        stopAllPatterns(true)
        parentFragmentManager.beginTransaction()
            .setCustomAnimations(R.anim.slide_in_left, R.anim.slide_out_left, R.anim.slide_in_left, R.anim.slide_out_left)
            .replace(R.id.fragment_container, ScreenLightFragment())
            .addToBackStack(null)
            .commit()
    }

    private fun openLightBulbScreen() {
        if (isNavigating) return // GUARD
        isNavigating = true
        stopAllPatterns(true)
        parentFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, LightBulbFragment())
            .addToBackStack(null)
            .commit()
    }

    private fun openMorseCodeFragment() {
        if (isNavigating) return // GUARD
        isNavigating = true
        stopAllPatterns(true)
        parentFragmentManager.beginTransaction()
            .setCustomAnimations(R.anim.slide_in_left, R.anim.slide_out_left, R.anim.slide_in_left, R.anim.slide_out_left)
            .replace(R.id.fragment_container, MorseCodeFragment())
            .addToBackStack(null)
            .commit()
    }

    private fun openTimerFragment() {
        if (isNavigating) return // GUARD
        isNavigating = true
        stopAllPatterns(false)
        parentFragmentManager.beginTransaction()
            .setCustomAnimations(R.anim.slide_in_left, R.anim.slide_out_left, R.anim.slide_in_left, R.anim.slide_out_left)
            .replace(R.id.fragment_container, TimerFragment())
            .addToBackStack(null)
            .commit()
    }


    private fun stopAllPatterns(turnOffFlash: Boolean = true) {
        val wasPatternActive = (viewModel.sosActive.value == true) ||
                (viewModel.strobeActive.value == true) ||
                (sosRunnable != null) ||
                (strobeRunnable != null)

        strobeRunnable?.let { handler.removeCallbacks(it) }
        sosRunnable?.let { handler.removeCallbacks(it) }
        strobeRunnable = null
        sosRunnable = null

        viewModel.setSosActive(false)
        viewModel.setStrobeActive(false)

        val shouldTurnOff = turnOffFlash || wasPatternActive

        if (shouldTurnOff && FlashlightManager.isInitialized()) {
            context?.let { ctx ->
                FlashlightManager.turnOffFlashlight(ctx)
            }
        }
        updateUI()
    }

    private fun startStrobePattern() {
        stopAllPatterns(true)
        viewModel.setStrobeActive(true)

        strobeRunnable = object : Runnable {
            var strobeState = true
            override fun run() {
                if (viewModel.strobeActive.value != true) return

                if (strobeState) FlashlightManager.turnOnFlashlight(requireContext())
                else FlashlightManager.turnOffFlashlight(requireContext())
                strobeState = !strobeState
                handler.postDelayed(this, 100)
            }
        }
        handler.post(strobeRunnable!!)
        updateUI()
    }

    private fun startSosPattern() {
        stopAllPatterns(true)
        viewModel.setSosActive(true)
        // ✅ Already calling it here, but let's be safe

        
        val sosPattern = longArrayOf(150, 100, 150, 100, 150, 250, 400, 100, 400, 100, 400, 250, 150, 100, 150, 100, 150, 500)
        sosRunnable = object : Runnable {
            var index = 0
            var isFlashing = true
            override fun run() {
                if (viewModel.sosActive.value != true) return

                if (isFlashing) FlashlightManager.turnOnFlashlight(requireContext())
                else FlashlightManager.turnOffFlashlight(requireContext())
                isFlashing = !isFlashing
                val delay = sosPattern[index]
                index = (index + 1) % sosPattern.size
                handler.postDelayed(this, delay)
            }
        }
        handler.post(sosRunnable!!)
        updateUI()
    }

    private fun startListeningForClaps() {
        if (clapDetector?.isListening == true) return
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.w("HomeFragment", "Cannot start clap detection: Permission not granted")
            binding.clapSwitch.isChecked = false
            return
        }

        if (clapDetector == null) {
            clapDetector = ClapDetector(requireContext().applicationContext) {
                if (isAdded) {
                    runFlashlightAction {
                        if (isFlashlightOn()) {
                            stopAllPatterns()
                        } else {
                            FlashlightManager.turnOnFlashlight(requireContext())
                            updateUI()
                        }
                    }
                }
            }
        }
        clapDetector?.startListening()
    }

    private fun stopListeningForClaps() {
        clapDetector?.stopListening()
    }

    private fun stopRunningTimer() {
        // Stop the running timer
        val timerManager = TimerManager(requireContext())
        timerManager.stopTimer()

        // Ensure flashlight is off
        try {
            FlashlightManager.turnOffFlashlight(requireContext())
            // ✅ Stop the status notification service

        } catch (e: Exception) {
            Log.e("HomeFragment", "Error turning off flashlight", e)
        }

        // Update UI immediately
        updateFlashlightUI()
        // Show message
        Toast.makeText(requireContext(), "Timer stopped", Toast.LENGTH_SHORT).show()
    }
    private fun navigateToTimerFragment() {
        val timerFragment = TimerFragment()
        requireActivity().supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, timerFragment)
            .addToBackStack("timer")
            .commit()
    }
    private fun showTimerInterferenceWarning() {
        // Check if dialog is already showing to prevent duplicates
        if (timerWarningDialog?.isShowing == true) return

        timerWarningDialog = AlertDialog.Builder(requireContext())
            .setTitle("⚠️ Timer Running")
            .setMessage("A flashlight timer is currently running. Using other flashlight features will stop the timer.")
            .setPositiveButton("Continue") { _, _ ->
                // Stop the timer and proceed
                stopRunningTimer()
                updateFlashlightUI() // Update UI after stopping
                timerWarningDialog = null
            }
            .setNegativeButton("Go to Timer") { _, _ ->
                // Go back to timer fragment
                navigateToTimerFragment()
                timerWarningDialog = null
            }
            .setCancelable(false)
            .show()
    }
     fun updateFlashlightUI() {
        // Check actual flashlight state and update UI
        try {
            val isFlashOn = FlashlightManager.isFlashlightOn(requireContext())

            // Update your flashlight toggle button UI here
            updateMainButtonUI()
            // Example:
          //  binding.cardFlashToggle.isChecked = isFlashOn
         //   binding.cardFlashToggle.text = if (isFlashOn) "FLASH ON" else "FLASH OFF"

            Log.d("HomeFragment", "Flashlight UI updated: ${if (isFlashOn) "ON" else "OFF"}")
        } catch (e: Exception) {
            Log.e("HomeFragment", "Error updating flashlight UI", e)
        }
    }
    override fun onResume() {
        super.onResume()
        isNavigating = false // RESET GUARD
        (activity as? MainActivity)?.setToolbarTitle(getString(R.string.app_name))
        // Check notification permission status
        checkNotificationPermission()

        // Check if timer is running from other features
        val timerManager = TimerManager(requireContext())
        if (timerManager.isTimerRunning()) {
            showTimerInterferenceWarning()
        }

        // Update flashlight UI based on actual state
        updateFlashlightUI()

        if(hasFlashlight) {
            updateUI()
        }
        val window = requireActivity().window
        val layoutParams = window.attributes
        layoutParams.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        window.attributes = layoutParams
    }
    private fun showPermissionReminder() {
        if (!isAdded || !isResumed || isDetached) return
        val currentContext = context ?: return
        if (permissionReminderDialog?.isShowing == true) return

        val sharedPrefs = currentContext.getSharedPreferences(AppConstants.PREFS_NAME, Context.MODE_PRIVATE)
        val isFirstTimeAsking = sharedPrefs.getBoolean("first_time_notification_ask", true)

        val title = if (isFirstTimeAsking) "Flash Alerts on Notifications" else "Permission Required"
        val message = if (isFirstTimeAsking) {
            "Would you like to get flashlight alerts when you receive notifications? This requires Notification Access permission."
        } else {
            "Notification alerts are enabled in settings, but permission is missing. Would you like to grant it now?"
        }

        permissionReminderDialog = AlertDialog.Builder(currentContext)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(if (isFirstTimeAsking) "Enable" else "Open Settings") { _, _ ->
                permissionReminderDialog = null
                sharedPrefs.edit().putBoolean("first_time_notification_ask", false).apply()
                openNotificationAccessSettings()
            }
            .setNegativeButton(if (isFirstTimeAsking) "Later" else "Turn Off") { _, _ ->
                permissionReminderDialog = null
                val prefs = context?.getSharedPreferences(
                    AppConstants.PREFS_NAME,
                    Context.MODE_PRIVATE
                )
                if (isFirstTimeAsking) {
                    prefs?.edit()?.putBoolean("first_time_notification_ask", false)?.apply()
                } else {
                    prefs?.edit()?.putBoolean(AppConstants.KEY_NOTIFICATION_SWITCH, false)?.apply()
                    setNotificationSwitchSilently(false)
                }
            }
            .setOnCancelListener { permissionReminderDialog = null }
            .show()
    }

    private fun checkNotificationPermission() {
        if (!isAdded || _binding == null || !isResumed || isDetached) return
        val currentContext = context ?: return
        val isEnabled = isNotificationServiceEnabled()
        val sharedPrefs = currentContext.getSharedPreferences(AppConstants.PREFS_NAME, Context.MODE_PRIVATE)
        val prefEnabled = sharedPrefs.getBoolean(AppConstants.KEY_NOTIFICATION_SWITCH, false)

        // Update switch state based on actual permission silently
        setNotificationSwitchSilently(isEnabled && prefEnabled)

        if (prefEnabled && !isEnabled && isResumed) {
            // User wants it enabled but permission not granted
            showPermissionReminder()
        }
    }

    // Add broadcast receiver for notification permission changes
    private val notificationPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == NotificationAlertService.ACTION_NOTIFICATION_ACCESS_CHANGED) {
                if (isAdded && _binding != null && isResumed && !isDetached) {
                    checkNotificationPermission()
                }
            }
        }
    }
    private fun openNotificationAccessSettings() {
        try {
            // Open notification listener settings
            val intent = Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
            startActivity(intent)

            // Show toast with additional guidance
            Toast.makeText(requireContext(),
                "Please toggle ON 'FlashNow' in the list",
                Toast.LENGTH_LONG).show()

        } catch (e: Exception) {
            Log.e("HomeFragment", "Error opening notification settings", e)
            // Fallback to app info
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            val uri = Uri.fromParts("package", requireActivity().packageName, null)
            intent.data = uri
            startActivity(intent)
        }
    }

    private fun showNotificationPermissionDialog() {
        if (!isAdded || isDetached) return
        val currentContext = context ?: return
        if (notificationPermissionDialog?.isShowing == true) return

        notificationPermissionDialog = AlertDialog.Builder(currentContext)
            .setTitle("Enable Notification Access")
            .setMessage("To enable notification alerts:\n\n" +
                    "1. Tap 'Open Settings'\n" +
                    "2. Find 'FlashNow' in the list of notification access apps\n" +
                    "3. Toggle the switch ON for FlashNow\n" +
                    "4. Return to this app and toggle the notification switch ON again\n\n" +
                    "This feature allows FlashNow to detect when you receive notifications and flash the flashlight as an alert.")
            .setPositiveButton("Open Settings") { _, _ ->
                notificationPermissionDialog = null
                openNotificationAccessSettings()
            }
            .setNegativeButton("Cancel") { _, _ ->
                notificationPermissionDialog = null
            }
            .setOnCancelListener { notificationPermissionDialog = null }
            .show()
    }


    override fun onPause() {
        super.onPause()
        handler.postDelayed({ isNavigating = false }, 500)
        stopAllPatterns(false)
        stopListeningForClaps()
        
        // Dismiss dialogs if visible to prevent leaks and multiple instances on resume
        timerWarningDialog?.dismiss()
        timerWarningDialog = null

        permissionReminderDialog?.dismiss()
        permissionReminderDialog = null

        notificationPermissionDialog?.dismiss()
        notificationPermissionDialog = null
    }

    override fun onDestroyView() {
        pendingFlashlightAction = null
        clapDetector?.release()
        clapDetector = null

        super.onDestroyView()

        // Remove the listener when the view is destroyed
        flashlightStateListener?.let {
            context?.let { ctx ->
                FlashlightManager.getInstance(ctx).removeOnFlashlightStateChangeListener(it)
            }
        }
        // Don't release FlashlightManager here as it's used by other components
        handler.removeCallbacksAndMessages(null)
        mediaPlayer?.release()
        mediaPlayer = null
        _binding = null

        // Unregister broadcast receiver safely
        try {
            context?.applicationContext?.unregisterReceiver(notificationPermissionReceiver)
        } catch (e: Exception) {
            // Receiver wasn't registered or context is null
        }

    }

    private fun isNotificationServiceEnabled(): Boolean {
        val currentContext = context ?: return false
        val packageName = currentContext.packageName
        val serviceName = NotificationAlertService::class.java.canonicalName ?: ""
        val flat = Settings.Secure.getString(
            currentContext.contentResolver,
            "enabled_notification_listeners"
        )

        return flat?.contains("$packageName/$serviceName") == true
    }
}