package com.spqr.armour

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import com.spqr.armour.configuration.DetectionThresholds
import com.spqr.armour.utils.KeyboardUtils
import java.io.File

class ConfigActivity : AppCompatActivity() {

    private lateinit var profilingMenuButton: Button
    private lateinit var clearStorageButton: Button
    private lateinit var setThresholdsButton: Button

    private lateinit var sensorConfigManager: SensorConfigManager
    private val thresholdBias: Double = 0.5

    private val TAG = "ConfigActivity"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Force light mode
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        
        setContentView(R.layout.activity_config)
        
        profilingMenuButton = findViewById(R.id.btnProfilingMenu)
        clearStorageButton = findViewById(R.id.btnClearStorage)
        setThresholdsButton = findViewById(R.id.btnSetThresholds)

        // Initialize sensor config manager
        sensorConfigManager = SensorConfigManager.getInstance(this)
        
        // Check if global minimum rates are valid
        updateThresholdButtonState()

        // Set up button click listeners
        clearStorageButton.setOnClickListener {
            KeyboardUtils.hideKeyboard(this)
            showClearStorageConfirmation()
        }

        setThresholdsButton.setOnClickListener {
            KeyboardUtils.hideKeyboard(this)
            if (sensorConfigManager.hasValidMinimumRates()) {
                val intent = Intent(this, DetectionThresholds::class.java)
                startActivity(intent)
            } else {
                showProfilingRequiredDialog()
            }
        }
        
        profilingMenuButton.setOnClickListener {
            KeyboardUtils.hideKeyboard(this)
            showProfilingMenu()
        }
    }
    
    override fun onResume() {
        super.onResume()
        // Update button state when returning from other activities
        updateThresholdButtonState()
    }
    
    /**
     * Update the threshold button state based on whether valid global rates exist
     */
    private fun updateThresholdButtonState() {
        if (sensorConfigManager.hasValidMinimumRates()) {
            setThresholdsButton.isEnabled = true
            setThresholdsButton.text = "Set Detection Thresholds"
        } else {
            setThresholdsButton.isEnabled = false
            setThresholdsButton.text = "Set Detection Thresholds (Profiling Required)"
        }
    }
    
    /**
     * Show dialog explaining that profiling is required for threshold settings
     */
    private fun showProfilingRequiredDialog() {
        AlertDialog.Builder(this)
            .setTitle("Profiling Required")
            .setMessage("Detection thresholds are based on your device's minimum supported sensor sampling rates.\n\nPlease run the profiling feature first to determine these rates.")
            .setPositiveButton("Go to Profiling") { _, _ ->
                val intent = Intent(this, ProfilingActivity::class.java)
                startActivity(intent)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showClearStorageConfirmation() {
        AlertDialog.Builder(this)
            .setTitle("Clear Storage")
            .setMessage("Are you sure you want to delete all data files? This action cannot be undone.")
            .setPositiveButton("Clear") { _, _ ->
                clearAllStoredData()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun clearAllStoredData() {
        try {
            // Get the same target directory as used in FileUtils
            val targetDir = getExternalFilesDir(
                Environment.getDataDirectory().absolutePath
            )
            
            if (targetDir != null && targetDir.exists() && targetDir.isDirectory) {
                val deletedCount = deleteDirectoryContents(targetDir)
                if (deletedCount > 0) {
                    Toast.makeText(this, "Cleared $deletedCount files and folders", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "No files to delete", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, "Data directory not found", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing storage", e)
            Toast.makeText(this, "Error clearing storage: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
    
    private fun deleteDirectoryContents(directory: File): Int {
        var deletedCount = 0
        
        directory.listFiles()?.forEach { file ->
            if (file.isFile) {
                if (file.delete()) {
                    deletedCount++
                }
            } else if (file.isDirectory) {
                // Delete all files in the subdirectory
                deletedCount += deleteFilesInDirectory(file)
                // Then delete the directory itself
                if (file.delete()) {
                    deletedCount++
                }
            }
        }
        
        return deletedCount
    }
    
    private fun deleteFilesInDirectory(directory: File): Int {
        var deletedCount = 0
        
        directory.listFiles()?.forEach { file ->
            if (file.isFile && file.delete()) {
                deletedCount++
            } else if (file.isDirectory) {
                deletedCount += deleteFilesInDirectory(file)
                if (file.delete()) {
                    deletedCount++
                }
            }
        }
        
        return deletedCount
    }

    /**
     * Show profiling menu - navigate to profiling activity
     */
    private fun showProfilingMenu() {
        val intent = Intent(this, ProfilingActivity::class.java)
        startActivity(intent)
    }
} 