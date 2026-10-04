package com.saitamagrs.flashnow.fragments

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import com.saitamagrs.flashnow.MainActivity
import com.saitamagrs.flashnow.databinding.FragmentSettingsBinding
import com.saitamagrs.flashnow.utils.AppConstants
import kotlin.math.roundToInt

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private var notificationPermissionDialog: AlertDialog? = null
    private var themeSelectionDialog: AlertDialog? = null

    private val notificationSwitchListener = CompoundButton.OnCheckedChangeListener { _, isChecked ->
        val currentContext = context ?: return@OnCheckedChangeListener
        val sharedPreferences = currentContext.getSharedPreferences(AppConstants.PREFS_NAME, Context.MODE_PRIVATE)

        if (isChecked) {
            if (isNotificationServiceEnabled()) {
                sharedPreferences.edit().putBoolean(AppConstants.KEY_NOTIFICATION_SWITCH, true).apply()
                Toast.makeText(currentContext, "Notification alerts enabled", Toast.LENGTH_SHORT).show()
            } else {
                showNotificationPermissionDialog()
                setNotificationSwitchSilently(false)
            }
        } else {
            sharedPreferences.edit().putBoolean(AppConstants.KEY_NOTIFICATION_SWITCH, false).apply()
            Toast.makeText(currentContext, "Notification alerts disabled", Toast.LENGTH_SHORT).show()
        }
    }

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

        // Handle edge-to-edge window insets
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(top = systemBars.top, bottom = systemBars.bottom)
            insets
        }

        val sharedPreferences = requireContext().getSharedPreferences(AppConstants.PREFS_NAME, Context.MODE_PRIVATE)

        // --- Appearance / Theme Section ---
        val currentTheme = sharedPreferences.getString(AppConstants.KEY_THEME, AppConstants.THEME_DARK) ?: AppConstants.THEME_DARK
        updateThemeDisplay(currentTheme)

        binding.cardAppearance.setOnClickListener {
            showThemeSelectionDialog()
        }

        // --- Clap Sensitivity Logic ---
        val savedSensitivity = sharedPreferences.getInt(AppConstants.KEY_SENSITIVITY, 9000).coerceIn(0, 25000)
        binding.seekbarSensitivity.progress = savedSensitivity
        updateSensitivityLabels(savedSensitivity)

        binding.seekbarSensitivity.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                updateSensitivityLabels(progress)
                if (fromUser) {
                    sharedPreferences.edit().putInt(AppConstants.KEY_SENSITIVITY, progress).apply()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // --- Button Sound Switch Logic ---
        val isSoundEnabled = sharedPreferences.getBoolean(AppConstants.KEY_SOUND_ENABLED, true)
        binding.switchSound.isChecked = isSoundEnabled
        binding.switchSound.setOnCheckedChangeListener { _, isChecked ->
            sharedPreferences.edit().putBoolean(AppConstants.KEY_SOUND_ENABLED, isChecked).apply()
        }

        // --- Notification Flash Alert Switch Logic ---
        syncNotificationSwitch()

        // --- About & Support Section ---
        setupAboutAndLegal()
    }

    private fun updateThemeDisplay(theme: String) {
        val isLight = theme == AppConstants.THEME_LIGHT
        binding.tvThemeBadge.text = if (isLight) "Light" else "Dark"
        binding.tvThemeSubtitle.text = if (isLight) "Clean Light (Active)" else "Modern Dark (Active)"
        binding.cardAppearance.contentDescription = "App theme, currently ${if (isLight) "Light" else "Dark"}. Tap to change."
    }

    private fun showThemeSelectionDialog() {
        val currentContext = context ?: return
        if (themeSelectionDialog?.isShowing == true) return

        val sharedPreferences = currentContext.getSharedPreferences(AppConstants.PREFS_NAME, Context.MODE_PRIVATE)
        val currentTheme = sharedPreferences.getString(AppConstants.KEY_THEME, AppConstants.THEME_DARK) ?: AppConstants.THEME_DARK

        val options = arrayOf("Dark Theme", "Light Theme")
        val currentSelection = if (currentTheme == AppConstants.THEME_LIGHT) 1 else 0

        themeSelectionDialog = AlertDialog.Builder(currentContext)
            .setTitle("Choose Theme")
            .setSingleChoiceItems(options, currentSelection) { dialog, which ->
                val chosenTheme = if (which == 1) AppConstants.THEME_LIGHT else AppConstants.THEME_DARK
                dialog.dismiss()
                themeSelectionDialog = null

                if (chosenTheme != currentTheme) {
                    sharedPreferences.edit().putString(AppConstants.KEY_THEME, chosenTheme).apply()
                    updateThemeDisplay(chosenTheme)
                    activity?.recreate()
                }
            }
            .setNegativeButton("Cancel") { _, _ ->
                themeSelectionDialog = null
            }
            .setOnCancelListener {
                themeSelectionDialog = null
            }
            .show()
    }

    private fun updateSensitivityLabels(progress: Int) {
        val clamped = progress.coerceIn(0, 25000)
        // 0 is most sensitive (100%), 25000 is least sensitive (0%)
        val percent = (((25000 - clamped).toFloat() / 25000f) * 100f).roundToInt().coerceIn(0, 100)
        binding.tvSensitivityBadge.text = "$percent%"

        val levelDescription = when {
            percent >= 75 -> "High ($clamped)"
            percent >= 40 -> "Medium ($clamped)"
            else -> "Low ($clamped)"
        }
        binding.tvSensitivityLevel.text = "$levelDescription"
    }

    private fun setNotificationSwitchSilently(checked: Boolean) {
        _binding?.switchNotification?.let {
            it.setOnCheckedChangeListener(null)
            it.isChecked = checked
            it.setOnCheckedChangeListener(notificationSwitchListener)
        }
    }

    private fun syncNotificationSwitch() {
        val sharedPreferences = requireContext().getSharedPreferences(AppConstants.PREFS_NAME, Context.MODE_PRIVATE)
        val hasPermission = isNotificationServiceEnabled()
        val isPrefEnabled = sharedPreferences.getBoolean(AppConstants.KEY_NOTIFICATION_SWITCH, false)

        val shouldBeChecked = hasPermission && isPrefEnabled
        setNotificationSwitchSilently(shouldBeChecked)
    }

    private fun isNotificationServiceEnabled(): Boolean {
        val currentContext = context ?: return false
        val flat = Settings.Secure.getString(
            currentContext.contentResolver,
            "enabled_notification_listeners"
        )
        return flat?.contains(currentContext.packageName) == true
    }

    private fun openNotificationAccessSettings() {
        try {
            val intent = Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
            startActivity(intent)
        } catch (e: Exception) {
            try {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                intent.data = Uri.parse("package:${requireContext().packageName}")
                startActivity(intent)
            } catch (ex: Exception) {
                // Fallback ignored
            }
        }
    }

    private fun showNotificationPermissionDialog() {
        val currentContext = context ?: return
        if (notificationPermissionDialog?.isShowing == true) return

        notificationPermissionDialog = AlertDialog.Builder(currentContext)
            .setTitle("Enable Notification Access")
            .setMessage("To enable notification flash alerts:\n\n" +
                    "1. Tap 'Open Settings'\n" +
                    "2. Find 'FlashNow' in the list of notification access apps\n" +
                    "3. Toggle the switch ON for FlashNow\n" +
                    "4. Return to Settings and toggle Flash on Notification ON\n\n" +
                    "This allows FlashNow to detect incoming alerts and flash the light.")
            .setPositiveButton("Open Settings") { _, _ ->
                notificationPermissionDialog = null
                openNotificationAccessSettings()
            }
            .setNegativeButton("Cancel") { _, _ ->
                notificationPermissionDialog = null
            }
            .setOnCancelListener {
                notificationPermissionDialog = null
            }
            .show()
    }

    private fun setupAboutAndLegal() {
        // App Version
        try {
            val pInfo = requireContext().packageManager.getPackageInfo(requireContext().packageName, 0)
            val versionName = pInfo.versionName ?: "1.0.0"
            binding.tvVersionInfo.text = "Version $versionName"
            binding.rowVersion.setOnClickListener {
                Toast.makeText(requireContext(), "FlashNow v$versionName - Up to date", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            binding.tvVersionInfo.text = "Version 1.0.0"
        }

        // Privacy Policy
        binding.rowPrivacyPolicy.setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(AppConstants.PRIVACY_POLICY_URL))
            try {
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Unable to open privacy policy link", Toast.LENGTH_SHORT).show()
            }
        }

        // Rate App
        binding.rowRateApp.setOnClickListener {
            val packageName = requireContext().packageName
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName")))
            } catch (e: ActivityNotFoundException) {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$packageName")))
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Unable to open store page", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        (activity as? MainActivity)?.setToolbarTitle("SETTINGS")
        val sharedPreferences = requireContext().getSharedPreferences(AppConstants.PREFS_NAME, Context.MODE_PRIVATE)
        val currentTheme = sharedPreferences.getString(AppConstants.KEY_THEME, AppConstants.THEME_DARK) ?: AppConstants.THEME_DARK
        updateThemeDisplay(currentTheme)
        syncNotificationSwitch()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        notificationPermissionDialog?.dismiss()
        notificationPermissionDialog = null
        themeSelectionDialog?.dismiss()
        themeSelectionDialog = null
        _binding = null
    }
}