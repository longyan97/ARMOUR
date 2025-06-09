package com.spqr.armour

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.spqr.armour.utils.FileUtils
import com.spqr.armour.utils.KeyboardUtils
import java.io.File

class ProfilingActivity : AppCompatActivity() {

    companion object {
        // Use the same action string as defined in Constants
        const val ACTION_PROFILING_COMPLETE = Constants.ACTION_PROFILING_COMPLETE
        const val ACTION_RATE_CHANGED = "com.spqr.armour.RATE_CHANGED"
    }

    private lateinit var manualConfigCheckBox: CheckBox
    private lateinit var testNameEditText: EditText
    private lateinit var sampleRatesEditText: EditText
    private lateinit var runTimeEditText: EditText
    private lateinit var startButton: Button
    private lateinit var viewResultsButton: Button
    private lateinit var helpButton: Button
    private lateinit var statusTextView: TextView
    private lateinit var progressBar: ProgressBar

    private var isServiceRunning: Boolean = false
    private var profilingCompleted: Boolean = false
    private var testName: String = "unnamed"
    private var sampleRates: String = "1, 5, 03"
    private var outputDir: String = ""
    private var runTime: Int = 10
    
    // Variables for progress tracking
    private var currentRateIndex: Int = 0
    private var totalRates: Int = 0
    private var secondsRemaining: Int = 0
    private var totalDuration: Int = 0
    private val handler = Handler(Looper.getMainLooper())
    private val updateRunnable = object : Runnable {
        override fun run() {
            if (isServiceRunning && secondsRemaining > 0) {
                secondsRemaining--
                updateStatusAndProgress()
                handler.postDelayed(this, 1000)
            }
        }
    }

    // Broadcast receiver for profiling completion
    private val profilingCompleteReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_PROFILING_COMPLETE) {
                Log.d(Constants.mainLogTag, "Received profiling complete broadcast in ProfilingActivity")
                
                // Update UI state
                isServiceRunning = false
                profilingCompleted = true
                startButton.text = "Start Profiling"
                statusTextView.text = "Profiling completed"
                progressBar.progress = 100
                
                // Stop the timer
                handler.removeCallbacks(updateRunnable)
                
                // Get the test name and sample rates from the intent
                intent.getStringExtra("testName")?.let { testName = it }
                intent.getStringExtra("sampleRates")?.let { sampleRates = it }
                
                // Check for results directories and enable the view results button if found
                checkForExistingResults()
                
                // Show a toast notification
                Toast.makeText(context, "Profiling completed for all rates", Toast.LENGTH_SHORT).show()
            } else if (intent?.action == ACTION_RATE_CHANGED) {
                // Get current rate information
                currentRateIndex = intent.getIntExtra("currentRateIndex", 0)
                val currentRate = intent.getStringExtra("currentRate") ?: ""
                
                Log.d(Constants.mainLogTag, "Received rate changed broadcast: index=$currentRateIndex, rate='$currentRate'")
                
                // Reset seconds remaining for this rate
                secondsRemaining = runTime
                
                // Update status and progress
                updateStatusAndProgress(currentRate)
                
                // Restart the timer
                handler.removeCallbacks(updateRunnable)
                handler.post(updateRunnable)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_profiling)
        
        Log.d(Constants.mainLogTag, "ProfilingActivity: onCreate")
        
        // Set up action bar
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            title = "Sensor Rate Profiling"
        }
        
        // Initialize UI elements
        manualConfigCheckBox = findViewById(R.id.checkBoxManualConfig)
        testNameEditText = findViewById(R.id.editTextTestName)
        sampleRatesEditText = findViewById(R.id.editTextSampleRates)
        runTimeEditText = findViewById(R.id.editTextRunTime)
        startButton = findViewById(R.id.btnStartProfiling)
        viewResultsButton = findViewById(R.id.btnViewResults)
        helpButton = findViewById(R.id.btnHelp)
        statusTextView = findViewById(R.id.textViewStatus)
        progressBar = findViewById(R.id.progressBarProfiling)
        
        // Set default values
        // Leave testNameEditText blank (default will be "Profiling" if empty when starting)
        sampleRatesEditText.setText("")
        runTimeEditText.setText("")
        progressBar.visibility = View.INVISIBLE
        
        // Check if there are existing results folders containing "Profiling"
        checkForExistingResults()
        
        // Initially disable the input fields
        setInputFieldsEnabled(false)
        
        // Set up checkbox listener
        manualConfigCheckBox.setOnCheckedChangeListener { _, isChecked ->
            setInputFieldsEnabled(isChecked)
            
            // If manual mode is disabled, reset fields to default values
            if (!isChecked) {
                testNameEditText.setText("")
                sampleRatesEditText.setText("")
                runTimeEditText.setText("")
            }
        }
        
        // Set up click listeners
        startButton.setOnClickListener { 
            KeyboardUtils.hideKeyboard(this)
            onStartProfilingClicked() 
        }
        viewResultsButton.setOnClickListener { 
            KeyboardUtils.hideKeyboard(this)
            onViewResultsClicked() 
        }
        helpButton.setOnClickListener {
            KeyboardUtils.hideKeyboard(this)
            showHelpDialog()
        }
        
        // Set up output directory
        outputDir = getExternalFilesDir(Environment.getDataDirectory().absolutePath)?.absolutePath + 
                File.separator + testName
        
        // Register broadcast receiver
        val intentFilter = IntentFilter().apply {
            addAction(ACTION_PROFILING_COMPLETE)
            addAction(ACTION_RATE_CHANGED)
        }
        registerReceiver(profilingCompleteReceiver, intentFilter, RECEIVER_NOT_EXPORTED)
        Log.d(Constants.mainLogTag, "ProfilingActivity: Registered broadcast receiver for actions")
    }
    
    /**
     * Check if there are existing result folders containing "Profiling"
     * and enable the View Results button if found
     */
    private fun checkForExistingResults() {
        try {
            val externalFilesDir = getExternalFilesDir(Environment.getDataDirectory().absolutePath)?.absolutePath
            if (externalFilesDir != null) {
                val allDirs = File(externalFilesDir).listFiles()?.filter { it.isDirectory } ?: emptyList()
                
                // Look for any directory containing "Profiling" in the name with actual data
                val profilingDirs = allDirs.filter { dir -> 
                    dir.name.contains("Profiling", ignoreCase = true)
                }
                
                // Use the more thorough check to see if any directories actually contain data
                var hasValidData = false
                for (dir in profilingDirs) {
                    val subDirs = dir.listFiles()?.filter { it.isDirectory } ?: emptyList()
                    
                    // Check if any subdirectory contains sensor data files
                    for (subDir in subDirs) {
                        val sensorFiles = listOf(
                            Constants.AccelerometerOutputName,
                            Constants.GyroscopeOutputName,
                            Constants.MagnetometerOutputName
                        ).map { File(subDir, "$it.csv") }
                        
                        // If any sensor data file exists and has content, we have valid data
                        if (sensorFiles.any { it.exists() && it.length() > 0 }) {
                            hasValidData = true
                            Log.d(Constants.mainLogTag, "Found valid profiling data in: ${dir.name}")
                            
                            // Set the testName to the name of this directory
                            val dirName = dir.name
                            if (dirName.matches(Regex("\\d{4}_\\d{2}_\\d{2}_\\d{2}_\\d{2}_\\d{2}_Profiling_.*"))) {
                                // Format is YYYY_MM_DD_HH_mm_ss_Profiling_name
                                val profilingIndex = dirName.indexOf("Profiling_")
                                if (profilingIndex > 0) {
                                    testName = dirName.substring(profilingIndex + "Profiling_".length)
                                } else {
                                    testName = dirName
                                }
                            } else if (dirName.matches(Regex("\\d{4}_\\d{2}_\\d{2}_\\d{2}_\\d{2}_\\d{2}_.*"))) {
                                // Handle legacy format YYYY_MM_DD_HH_mm_ss_name (for backward compatibility)
                                testName = dirName.substring(dirName.indexOf("_", 17) + 1)
                            } else {
                                testName = dirName
                            }
                            Log.d(Constants.mainLogTag, "Set testName to: $testName")
                            break
                        }
                    }
                    if (hasValidData) break
                }
                
                viewResultsButton.isEnabled = hasValidData
                
                if (!hasValidData) {
                    Log.d(Constants.mainLogTag, "No valid profiling data found in any directories")
                }
            } else {
                Log.e(Constants.mainLogTag, "External files directory is null")
                viewResultsButton.isEnabled = false
            }
        } catch (e: Exception) {
            Log.e(Constants.mainLogTag, "Error checking for existing results", e)
            viewResultsButton.isEnabled = false
        }
    }
    
    /**
     * Enable or disable the input fields based on manual configuration mode
     */
    private fun setInputFieldsEnabled(enabled: Boolean) {
        testNameEditText.isEnabled = enabled
        sampleRatesEditText.isEnabled = enabled
        runTimeEditText.isEnabled = enabled
    }
    
    override fun onDestroy() {
        super.onDestroy()
        
        // Unregister broadcast receiver
        try {
            unregisterReceiver(profilingCompleteReceiver)
        } catch (e: Exception) {
            Log.e(Constants.mainLogTag, "Error unregistering receiver", e)
        }
        
        // Stop service if running
        if (isServiceRunning) {
            stopProfilingService()
        }
        
        // Remove any pending callbacks
        handler.removeCallbacks(updateRunnable)
    }
    
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
    
    private fun onStartProfilingClicked() {
        if (isServiceRunning) {
            stopProfilingService()
            return
        }
        
        // Get user inputs
        if (manualConfigCheckBox.isChecked) {
            // Use user-provided inputs in manual mode
            testName = testNameEditText.text.toString().trim()
            if (testName.isEmpty()) {
                testName = "unnamed"
                Log.d(Constants.mainLogTag, "Using default test name: unnamed")
            }
            
            sampleRates = sampleRatesEditText.text.toString().trim()
            if (sampleRates.isEmpty()) {
                sampleRates = "1, 5, 03"
            }
            
            runTime = runTimeEditText.text.toString().toIntOrNull() ?: 10
        } else {
            // Use default values in automatic mode
            testName = "unnamed"
            sampleRates = "1, 5, 03"
            runTime = 10
            Log.d(Constants.mainLogTag, "Using default configuration values")
        }
        
        // Validate sample rates
        if (!validateSampleRates(sampleRates)) {
            Toast.makeText(this, "Invalid sample rates. Please use comma-separated numbers.", Toast.LENGTH_SHORT).show()
            return
        }
        
        // Calculate total rates and duration
        val ratesList = sampleRates.split(",").map { it.trim() }
        totalRates = ratesList.size
        totalDuration = totalRates * runTime
        currentRateIndex = 0
        secondsRemaining = runTime
        
        // Update output directory
        outputDir = resetOutputDir()
        
        // Start profiling service
        val serviceIntent = Intent(this, ArmourService::class.java)
        serviceIntent.putExtra("inputExtra", testName)
        serviceIntent.putExtra("sampleRate", sampleRates)
        serviceIntent.putExtra("outputDir", outputDir)
        serviceIntent.putExtra("profiling", true)
        serviceIntent.putExtra("profRunDelay", runTime)
        
        ContextCompat.startForegroundService(this, serviceIntent)
        isServiceRunning = true
        profilingCompleted = false
        startButton.text = "Stop Profiling"
        viewResultsButton.isEnabled = false
        
        // Show progress bar and update status
        progressBar.visibility = View.VISIBLE
        progressBar.max = 100
        progressBar.progress = 0
        
        // Get the first rate to display
        val firstRate = if (ratesList.isNotEmpty()) ratesList[0] else "00"
        Log.d(Constants.mainLogTag, "Starting profiling with first rate: '$firstRate'")
        updateStatusAndProgress(firstRate)
        
        // Start the timer
        handler.post(updateRunnable)
        
        Toast.makeText(this, "Profiling started", Toast.LENGTH_SHORT).show()
    }
    
    private fun updateStatusAndProgress(currentRate: String = "") {
        if (!isServiceRunning) return
        
        // Calculate overall progress and total remaining time
        val elapsedTime = (currentRateIndex * runTime) + (runTime - secondsRemaining)
        val totalRemainingTime = totalDuration - elapsedTime
        val progressPercentage = (elapsedTime * 100) / totalDuration
        
        // Get the current rate list
        val ratesList = sampleRates.split(",").map { it.trim() }
        
        // Get the current rate (either from parameter or from the list)
        val rateToDisplay = if (currentRate.isNotEmpty()) {
            currentRate
        } else if (currentRateIndex < ratesList.size) {
            ratesList[currentRateIndex]
        } else {
            "Unknown"
        }
        
        // Format the rate display with correct labels for special values
        val displayRate = when (rateToDisplay) {
            "00" -> "DELAY_FASTEST"
            "01" -> "DELAY_GAME"
            "02" -> "DELAY_UI"
            "03" -> "DELAY_NORMAL"
            else -> {
                if (rateToDisplay.startsWith("0") && rateToDisplay.length > 1) {
                    rateToDisplay.substring(1).toInt().toString() + " Hz"
                } else {
                    "$rateToDisplay Hz"
                }
            }
        }
        
        Log.d(Constants.mainLogTag, "Updating status with rateToDisplay='$rateToDisplay', displayRate='$displayRate'")
        
        // Update status text with guaranteed display of rate and total remaining time
        statusTextView.text = "Testing rate: $displayRate\n" +
                              "Rate ${currentRateIndex + 1} of $totalRates\n" +
                              "Total time remaining: $totalRemainingTime seconds"
        
        // Update progress bar
        progressBar.progress = progressPercentage
    }
    
    private fun stopProfilingService() {
        // Clear notifications before stopping service (Samsung S9 fix)
        clearServiceNotifications()
        
        val serviceIntent = Intent(this, ArmourService::class.java)
        stopService(serviceIntent)
        isServiceRunning = false
        startButton.text = "Start Profiling"
        statusTextView.text = if (profilingCompleted) "Profiling completed" else "Profiling stopped"
        
        // Stop the timer
        handler.removeCallbacks(updateRunnable)
        
        // Hide progress bar if profiling was stopped before completion
        if (!profilingCompleted) {
            progressBar.visibility = View.INVISIBLE
            Toast.makeText(this, "Profiling stopped before completion", Toast.LENGTH_SHORT).show()
        }
    }
    
    /**
     * Clear service notifications before stopping - Samsung device compatibility fix
     */
    private fun clearServiceNotifications() {
        try {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            
            // Clear the specific notification ID used by ArmourService
            notificationManager.cancel(1)
            Log.d(Constants.mainLogTag, "ProfilingActivity: Cleared service notification ID 1")
            
            // Additional cleanup for Samsung devices
            if (Build.MANUFACTURER.equals("samsung", ignoreCase = true) || 
                Build.BRAND.equals("samsung", ignoreCase = true)) {
                // Give a small delay to ensure the cancel operation is processed
                Handler(Looper.getMainLooper()).postDelayed({
                    try {
                        // Double-check cleanup for Samsung devices
                        notificationManager.cancel(1)
                        Log.d(Constants.mainLogTag, "ProfilingActivity: Samsung device - performed additional notification cleanup")
                    } catch (e: Exception) {
                        Log.w(Constants.mainLogTag, "ProfilingActivity: Samsung additional cleanup failed", e)
                    }
                }, 100) // 100ms delay
            }
            
        } catch (e: Exception) {
            Log.e(Constants.mainLogTag, "ProfilingActivity: Failed to clear service notifications", e)
        }
    }
    
    private fun onViewResultsClicked() {
        Log.d(Constants.mainLogTag, "View Results button clicked")
        
        // Check if profiling data actually exists before launching results activity
        if (!hasExistingProfilingData()) {
            Log.d(Constants.mainLogTag, "No profiling data found, showing dialog")
            
            // Show dialog similar to monitoring activity when no data exists
            AlertDialog.Builder(this)
                .setTitle("No Profiling Data")
                .setMessage("No profiling data found. Please run profiling first to generate results.")
                .setPositiveButton("Start Profiling") { _, _ ->
                    Log.d(Constants.mainLogTag, "User clicked Start Profiling from no data dialog")
                    // Focus on the start profiling button
                    startButton.requestFocus()
                }
                .setNegativeButton("Cancel") { _, _ ->
                    Log.d(Constants.mainLogTag, "User cancelled no data dialog")
                }
                .setIcon(android.R.drawable.ic_dialog_info)
                .show()
            return
        }
        
        Log.d(Constants.mainLogTag, "Valid profiling data found, launching results activity")
        val intent = Intent(this, ProfilingResultsActivity::class.java)
        intent.putExtra("testName", testName)
        intent.putExtra("sampleRates", sampleRates)
        startActivity(intent)
    }
    
    /**
     * Check if profiling data actually exists (similar to monitoring activity logic)
     */
    private fun hasExistingProfilingData(): Boolean {
        try {
            val baseDir = getExternalFilesDir(Environment.getDataDirectory().absolutePath)
            if (baseDir == null || !baseDir.exists() || !baseDir.isDirectory) {
                Log.e(Constants.mainLogTag, "Base directory does not exist for profiling data check")
                return false
            }
            
            val allDirs = baseDir.listFiles()?.filter { it.isDirectory } ?: emptyList()
            Log.d(Constants.mainLogTag, "Checking ${allDirs.size} directories for profiling data")
            
            // Look for directories containing "Profiling" in the name
            val profilingDirs = allDirs.filter { dir -> 
                dir.name.contains("Profiling", ignoreCase = true)
            }.sortedByDescending { it.lastModified() }
            
            Log.d(Constants.mainLogTag, "Found ${profilingDirs.size} profiling directories")
            
            // Check if any profiling directories contain actual sensor data files
            for (dir in profilingDirs) {
                Log.d(Constants.mainLogTag, "Checking directory: ${dir.name}")
                val subDirs = dir.listFiles()?.filter { it.isDirectory } ?: emptyList()
                
                // Check if any subdirectory contains sensor data files
                for (subDir in subDirs) {
                    val sensorFiles = listOf(
                        Constants.AccelerometerOutputName,
                        Constants.GyroscopeOutputName,
                        Constants.MagnetometerOutputName
                    ).map { File(subDir, "$it.csv") }
                    
                    // If any sensor data file exists and has content, we have valid data
                    if (sensorFiles.any { it.exists() && it.length() > 0 }) {
                        Log.d(Constants.mainLogTag, "Found valid profiling data in: ${dir.name}")
                        return true
                    }
                }
            }
            
            Log.d(Constants.mainLogTag, "No valid profiling data found in any directory")
            return false
        } catch (e: Exception) {
            Log.e(Constants.mainLogTag, "Error checking for existing profiling data", e)
            return false
        }
    }
    
    private fun resetOutputDir(): String {
        return FileUtils.createUniqueProfilingDir(this, testName)
    }
    
    private fun validateSampleRates(rates: String): Boolean {
        return rates.split(",").all { rate ->
            rate.trim().matches(Regex("\\d+"))
        }
    }

    private fun showHelpDialog() {
        val helpMessage = """

            🎯 About:
            • This tool determines the minimum sampling rates your device can actually support for each sensor type
            • Results automatically configure global app settings for best performance
            • Device-specific profiling ensures reliable sensor usage detection
            
            ⚙️ How Profiling Works:
            
            1️⃣ Test Configuration:
            • Default mode: Tests common rates (1 Hz, 5 Hz, and system default)
            • Manual mode: Customize test parameters if needed
            • Each rate is tested for 10 seconds by default
                        
            2️⃣ Testing Process:
            • App requests each rate from all three sensors simultaneously  
            • Measures actual achieved sampling frequencies
            • Analyzes stability and calculates minimum supported rates
            
            3️⃣ Automatic Results Integration:
            • Profiling results can be used update global app settings
            • These settings enable monitoring and configuration features
            • Results are used for detection threshold calculations (more in config menu)
            
        """.trimIndent()
        
        AlertDialog.Builder(this)
            .setTitle("Profiling Guide")
            .setMessage(helpMessage)
            .setPositiveButton("Got it!") { dialog, _ -> 
                dialog.dismiss()
            }
            .setIcon(android.R.drawable.ic_dialog_info)
            .show()
    }
} 