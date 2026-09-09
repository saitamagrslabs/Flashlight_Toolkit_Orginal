package com.saitamagrs.flashnow

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import com.google.android.material.appbar.MaterialToolbar
import com.saitamagrs.flashnow.databinding.ActivityMainBinding
import com.saitamagrs.flashnow.fragments.AboutFragment
import com.saitamagrs.flashnow.fragments.HomeFragment
import com.saitamagrs.flashnow.fragments.LightBulbFragment
import com.saitamagrs.flashnow.fragments.MorseCodeFragment
import com.saitamagrs.flashnow.fragments.PrivacyPolicyFragment
import com.saitamagrs.flashnow.fragments.ScreenLightFragment
import com.saitamagrs.flashnow.fragments.SettingsFragment
import com.saitamagrs.flashnow.fragments.TimerFragment
import com.saitamagrs.flashnow.utils.AppConstants
import com.saitamagrs.flashnow.utils.FlashlightManager
import com.saitamagrs.flashnow.utils.PermissionManager

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var permissionLauncher: ActivityResultLauncher<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        setSupportActionBar(binding.toolbar)

        updateToolbarVisibility()


        // Ensure lock mode is reset when app starts
       /* val prefs = getSharedPreferences("timer_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("lock_mode_enabled", false).apply()
        TimerFragment.isLockModeActive = false
*/
        //permission laucher
        permissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { isGranted ->
            if (!isGranted) {
                val lastPermission = PermissionManager.lastRequestedPermission
                if (lastPermission != null && !shouldShowRequestPermissionRationale(lastPermission)) {
                    PermissionManager.showGoToSettingsDialog(this)
                }
            }
            PermissionManager.processNextPermission(this, permissionLauncher)
        }

        //system bars insets
        ViewCompat.setOnApplyWindowInsetsListener(binding.toolbar) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(top = systemBars.top)
            insets
        }

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, HomeFragment())
                .commit()
        }


        supportFragmentManager.addOnBackStackChangedListener {
            updateToolbarVisibility()
            invalidateOptionsMenu()
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val prefs = getSharedPreferences("timer_prefs", Context.MODE_PRIVATE)
                val isLocked = prefs.getBoolean("lock_mode_enabled", false)
                val isTimerRunning = prefs.getBoolean("timer_running", false)

                if (isLocked && isTimerRunning) {
                    Toast.makeText(this@MainActivity, "Cannot navigate away in Lock Mode. Disable it first.", Toast.LENGTH_SHORT).show()
                } else if (supportFragmentManager.backStackEntryCount > 0) {
                    supportFragmentManager.popBackStack()
                } else {
                    finish()
                }
          /*      if (TimerFragment.isLockModeActive) {
                    Toast.makeText(this@MainActivity,
                        "Cannot navigate away in Lock Mode. Disable lock mode first.",
                        Toast.LENGTH_SHORT).show()
                    return
                }
                if (supportFragmentManager.backStackEntryCount > 0) {
                    supportFragmentManager.popBackStack()
                } else {
                    finish()
                }*/
            }
        })

        handleFirstRunPermissions()



    }
    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        // Only show menu items on the HomeFragment (when the back stack is empty)
        val isHomeFragment = supportFragmentManager.backStackEntryCount == 0
        menu.findItem(R.id.menu_settings)?.isVisible = isHomeFragment
        menu.findItem(R.id.menu_share)?.isVisible = isHomeFragment
        menu.findItem(R.id.menu_about)?.isVisible = isHomeFragment
        menu.findItem(R.id.menu_privacy_policy)?.isVisible = isHomeFragment
        menu.findItem(R.id.menu_rate_us)?.isVisible = isHomeFragment
        menu.findItem(R.id.more_apps)?.isVisible = isHomeFragment
        return super.onPrepareOptionsMenu(menu)
    }

    fun setToolbarTitle(title: String) {
        supportActionBar?.title = title
    }



    fun updateHomeFragmentFlashlightUI() {
        val homeFragment = supportFragmentManager.findFragmentById(R.id.fragment_container) as? HomeFragment
        homeFragment?.updateFlashlightUI()
    }

   /* private fun updateToolbarVisibility() {
        val currentFragment = supportFragmentManager.findFragmentById(R.id.fragment_container)

        if (currentFragment is HomeFragment) {
            binding.toolbar.visibility = View.VISIBLE
            hideBackButton()
        } else {
            binding.toolbar.visibility = View.VISIBLE
            showBackButtonIfNeeded()
        }

        if (currentFragment is ScreenLightFragment|| currentFragment is LightBulbFragment || currentFragment is TimerFragment|| currentFragment is MorseCodeFragment) {
            binding.toolbar.visibility = View.GONE
            hideBackButton()
        } else {
            binding.toolbar.visibility = View.VISIBLE
            showBackButtonIfNeeded()
        }
    }*/
   private fun updateToolbarVisibility() {
       val currentFragment = supportFragmentManager.findFragmentById(R.id.fragment_container)
       binding.toolbar.visibility = when (currentFragment) {
           is ScreenLightFragment, is LightBulbFragment, is TimerFragment, is MorseCodeFragment -> View.GONE
           else -> View.VISIBLE
       }
       if (binding.toolbar.visibility == View.VISIBLE) {
           showBackButtonIfNeeded()
       } else {
           hideBackButton()
       }
   }


    // Add these methods to your MainActivity class

    fun setToolbarNavigationEnabled(enabled: Boolean) {
       // val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)


        // If you have menu items in toolbar, disable them
        binding.toolbar.menu?.let { menu ->
            for (i in 0 until menu.size()) {
                menu.getItem(i).isEnabled = enabled
            }
        }

// If you have click listeners on toolbar, handle them
        binding.toolbar.isClickable = enabled

    }

    fun hideBackButton() {

        supportActionBar?.setDisplayHomeAsUpEnabled(false)
        supportActionBar?.setHomeButtonEnabled(false)
        binding.toolbar.navigationIcon = null
        binding.toolbar.setNavigationOnClickListener(null)
    }

    fun showBackButtonIfNeeded() {
        // Don't show back button on HomeFragment
        val currentFragment = supportFragmentManager.findFragmentById(R.id.fragment_container)
        if (currentFragment is HomeFragment) {
            hideBackButton()
            return
        }

        // Show back button only if there are fragments in back stack
        if (supportFragmentManager.backStackEntryCount > 0) {
            supportActionBar?.setDisplayHomeAsUpEnabled(true)
            supportActionBar?.setHomeButtonEnabled(true)

            // Set up back navigation
            supportActionBar?.setDisplayShowHomeEnabled(true)

            // Set back button click listener
            binding.toolbar.setNavigationOnClickListener {
                onBackPressedDispatcher.onBackPressed()
            }
        } else {
            hideBackButton()
        }
    }



    // Override onBackPressed to handle lock mode
   /* override fun onBackPressed() {
        if (TimerFragment.isLockModeActive) {
            // Show message that navigation is locked
            Toast.makeText(this, "Cannot navigate away in Lock Mode. Disable lock mode first.", Toast.LENGTH_SHORT).show()
        } else {
            super.onBackPressed()
        }
    }*/

    // Add method to navigate to timer fragment (for other fragments to use)
    fun navigateToTimerFragment() {
        val timerFragment = TimerFragment()
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, timerFragment)
            .addToBackStack("timer")
            .commit()
    }



    private fun handleFirstRunPermissions() {
        val prefs = getSharedPreferences(AppConstants.PREFS_NAME, Context.MODE_PRIVATE)
        val isFirstRun = prefs.getBoolean(AppConstants.KEY_FIRST_RUN, true)

        if (isFirstRun) {
            PermissionManager.addPermissionToQueue(this, Manifest.permission.CAMERA)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                PermissionManager.addPermissionToQueue(this, Manifest.permission.POST_NOTIFICATIONS)
            }

            if (!PermissionManager.isQueueEmpty()) {
                PermissionManager.processNextPermission(this, permissionLauncher)
            }

            prefs.edit().putBoolean(AppConstants.KEY_FIRST_RUN, false).apply()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_settings -> navigateTo(SettingsFragment())
            R.id.menu_share -> shareApp()
            R.id.menu_about -> navigateTo(AboutFragment())
            R.id.menu_privacy_policy -> navigateTo(PrivacyPolicyFragment())
            R.id.menu_rate_us -> openUrl("https://play.google.com/store/apps/details?id=$packageName")
            R.id.more_apps -> openUrl("https://play.google.com/store/apps/developer?id=SaitamagrsLABS")
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    private fun navigateTo(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, fragment)
            .addToBackStack(null)
            .commit()
    }



    private fun shareApp() {
        val appName = getString(R.string.app_name)
        val shareText = "Check out this amazing flashlight app: $appName\n " +"Ultimate flashlight with smart timer, clap control & alerts. SOS, strobe & more!\n"+
                "Download it from the Play Store:\n" +
                "https://play.google.com/store/apps/details?id=$packageName"

        val sendIntent = Intent().apply {
            action = Intent.ACTION_SEND
            putExtra(Intent.EXTRA_TEXT, shareText)
            type = "text/plain"
        }

        val shareIntent = Intent.createChooser(sendIntent, "Share $appName via")
        startActivity(shareIntent)
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: android.content.ActivityNotFoundException) {
            // Handle case where Play Store is not installed
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Clean up flashlight resources when app is completely closed
        FlashlightManager.release()
    }



}