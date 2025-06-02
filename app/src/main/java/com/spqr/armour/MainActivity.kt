package com.spqr.armour

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import com.spqr.armour.utils.KeyboardUtils

class MainActivity : AppCompatActivity() {

    private lateinit var startServiceEasyButton: Button
    private lateinit var startServiceV2Button: Button
    private lateinit var startServiceButton: Button
    private lateinit var profilingMenuButton: Button
    private lateinit var configLogButton: Button
    
    private lateinit var sensorConfigManager: SensorConfigManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Force light mode
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        
        setContentView(R.layout.activity_main)
        
        // Initialize sensor config manager
        sensorConfigManager = SensorConfigManager.getInstance(this)
        
        // Initialize UI elements
        startServiceEasyButton = findViewById(R.id.btnStartServiceEasy)
        startServiceV2Button = findViewById(R.id.btnStartServiceV2)
        startServiceButton = findViewById(R.id.btnStartService)
        profilingMenuButton = findViewById(R.id.btnProfilingMenu)
        configLogButton = findViewById(R.id.btnLogsOptions)
        
        // Set up click listeners
        startServiceEasyButton.setOnClickListener {
            // Hide keyboard
//            KeyboardUtils.hideKeyboard(this)
//            // Launch monitoring activity
//            val intent = Intent(this, MonitoringEasyActivity::class.java)
//            startActivity(intent)
        }

        startServiceV2Button.setOnClickListener {
            if (checkValidGlobalSettings()) {
                // Hide keyboard
                KeyboardUtils.hideKeyboard(this)
                // Launch monitoring activity
                val intent = Intent(this, MonitoringActivity::class.java)
                startActivity(intent)
            }
        }

        startServiceButton.setOnClickListener {
            // Hide keyboard
//            KeyboardUtils.hideKeyboard(this)
//            // Launch monitoring activity
//            val intent = Intent(this, MonitoringActivity::class.java)
//            startActivity(intent)
        }
        
        profilingMenuButton.setOnClickListener {
            // Hide keyboard
            KeyboardUtils.hideKeyboard(this)
            // Launch profiling activity
            val intent = Intent(this, ProfilingActivity::class.java)
            startActivity(intent)
        }
        
        configLogButton.setOnClickListener {
            if (checkValidGlobalSettings()) {
                // Hide keyboard
                KeyboardUtils.hideKeyboard(this)
                // Launch config activity
                val intent = Intent(this, ConfigActivity::class.java)
                startActivity(intent)
            }
        }
        
        // Perform self-check for global settings on first startup
        performGlobalSettingsCheck()
    }
    
    /**
     * Check if global minimum sampling rates are valid
     * If not, show dialog prompting user to run profiling
     */
    private fun performGlobalSettingsCheck() {
        if (!sensorConfigManager.hasValidMinimumRates()) {
            showProfilingRequiredDialog()
        }
    }
    
    /**
     * Check if valid global settings exist before allowing access to features that depend on them
     * @return true if settings are valid, false otherwise
     */
    private fun checkValidGlobalSettings(): Boolean {
        if (!sensorConfigManager.hasValidMinimumRates()) {
            showProfilingRequiredDialog()
            return false
        }
        return true
    }
    
    /**
     * Show dialog explaining that profiling is required
     */
    private fun showProfilingRequiredDialog() {
        AlertDialog.Builder(this)
            .setTitle("Profiling Required")
            .setMessage("This app needs to determine your device's minimum supported sensor sampling rates before you can use monitoring features.\n\nWould you like to run the profiling now? This will take about 30 seconds.")
            .setPositiveButton("Run Profiling") { _, _ ->
                // Navigate to profiling activity
                val intent = Intent(this, ProfilingActivity::class.java)
                startActivity(intent)
            }
            .setNegativeButton("Later", null)
            .setCancelable(false)
            .show()
    }
    
    override fun onResume() {
        super.onResume()
        // Check again when returning to main activity in case profiling was completed
        // but don't show the dialog again if already shown
    }
}