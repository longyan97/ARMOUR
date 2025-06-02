package com.spqr.armour.configuration

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.spqr.armour.Constants
import com.spqr.armour.R
import com.spqr.armour.SensorConfigManager
import com.spqr.armour.utils.KeyboardUtils

class DetectionThresholds : AppCompatActivity() {
    private lateinit var resetThresholdsButton: Button
    
    // Minimum rate display TextViews (read-only)
    private lateinit var acceMinRateText: TextView
    private lateinit var gyroMinRateText: TextView
    private lateinit var magnMinRateText: TextView
    
    // Bias margin EditTexts (editable)
    private lateinit var acceBiasEdit: EditText
    private lateinit var gyroBiasEdit: EditText
    private lateinit var magnBiasEdit: EditText
    
    // Final threshold display TextViews (calculated)
    private lateinit var acceThresholdText: TextView
    private lateinit var gyroThresholdText: TextView
    private lateinit var magnThresholdText: TextView
    
    private lateinit var sensorConfigManager: SensorConfigManager

    private val TAG = "DetectionThresholds"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_detection_thresholds)

        // Initialize UI elements
        resetThresholdsButton = findViewById(R.id.btnResetThresholds)
        
        // Minimum rate displays
        acceMinRateText = findViewById(R.id.textAcceMinRate)
        gyroMinRateText = findViewById(R.id.textGyroMinRate)
        magnMinRateText = findViewById(R.id.textMagnMinRate)
        
        // Bias margin inputs
        acceBiasEdit = findViewById(R.id.editAcceBias)
        gyroBiasEdit = findViewById(R.id.editGyroBias)
        magnBiasEdit = findViewById(R.id.editMagnBias)
        
        // Final threshold displays
        acceThresholdText = findViewById(R.id.textAcceThreshold)
        gyroThresholdText = findViewById(R.id.textGyroThreshold)
        magnThresholdText = findViewById(R.id.textMagnThreshold)

        // Initialize sensor config manager
        sensorConfigManager = SensorConfigManager.getInstance(this)

        // Check if valid global minimum rates exist
        if (!sensorConfigManager.hasValidMinimumRates()) {
            // Disable all inputs and show message
            setInputsEnabled(false)
            showInvalidRatesMessage()
            return
        }

        // Set up reset button
        resetThresholdsButton.setOnClickListener {
            KeyboardUtils.hideKeyboard(this)
            resetBiasMargins()
        }

        // Load and display data
        loadMinimumRates()
        loadBiasMargins()
        calculateAndDisplayThresholds()

        // Set up text watchers for bias margin inputs
        setupBiasTextWatchers()
    }
    
    /**
     * Load and display minimum rates from profiling results
     */
    private fun loadMinimumRates() {
        val allMinRates = sensorConfigManager.getAllMinRates()
        
        acceMinRateText.text = allMinRates[Constants.AccelerometerOutputName]?.let { String.format("%.1f", it) } ?: "N/A"
        gyroMinRateText.text = allMinRates[Constants.GyroscopeOutputName]?.let { String.format("%.1f", it) } ?: "N/A"
        magnMinRateText.text = allMinRates[Constants.MagnetometerOutputName]?.let { String.format("%.1f", it) } ?: "N/A"
    }
    
    /**
     * Load bias margins from saved settings or use defaults
     */
    private fun loadBiasMargins() {
        // Check if we have saved bias margins, otherwise use default
        val savedThresholds = sensorConfigManager.getAllThresholds()
        val savedMinRates = sensorConfigManager.getAllMinRates()
        
        if (sensorConfigManager.getThresholdCachedStatus()) {
            // Calculate bias from saved thresholds
            val acceBias = (savedThresholds[Constants.AccelerometerOutputName] ?: Constants.THRESHOLD_BIAS.toDouble()) - 
                          (savedMinRates[Constants.AccelerometerOutputName] ?: 0.0)
            val gyroBias = (savedThresholds[Constants.GyroscopeOutputName] ?: Constants.THRESHOLD_BIAS.toDouble()) - 
                          (savedMinRates[Constants.GyroscopeOutputName] ?: 0.0)
            val magnBias = (savedThresholds[Constants.MagnetometerOutputName] ?: Constants.THRESHOLD_BIAS.toDouble()) - 
                          (savedMinRates[Constants.MagnetometerOutputName] ?: 0.0)
            
            acceBiasEdit.setText(String.format("%.1f", maxOf(0.0, acceBias)))
            gyroBiasEdit.setText(String.format("%.1f", maxOf(0.0, gyroBias)))
            magnBiasEdit.setText(String.format("%.1f", maxOf(0.0, magnBias)))
        } else {
            // Use default bias
            acceBiasEdit.setText(Constants.THRESHOLD_BIAS.toString())
            gyroBiasEdit.setText(Constants.THRESHOLD_BIAS.toString())
            magnBiasEdit.setText(Constants.THRESHOLD_BIAS.toString())
        }
    }
    
    /**
     * Calculate and display final thresholds
     */
    private fun calculateAndDisplayThresholds() {
        val allMinRates = sensorConfigManager.getAllMinRates()
        
        val acceMinRate = allMinRates[Constants.AccelerometerOutputName] ?: 0.0
        val gyroMinRate = allMinRates[Constants.GyroscopeOutputName] ?: 0.0
        val magnMinRate = allMinRates[Constants.MagnetometerOutputName] ?: 0.0
        
        val acceBias = acceBiasEdit.text.toString().toDoubleOrNull() ?: Constants.THRESHOLD_BIAS
        val gyroBias = gyroBiasEdit.text.toString().toDoubleOrNull() ?: Constants.THRESHOLD_BIAS
        val magnBias = magnBiasEdit.text.toString().toDoubleOrNull() ?: Constants.THRESHOLD_BIAS
        
        val acceThreshold = acceMinRate + acceBias
        val gyroThreshold = gyroMinRate + gyroBias
        val magnThreshold = magnMinRate + magnBias
        
        acceThresholdText.text = String.format("%.1f", acceThreshold)
        gyroThresholdText.text = String.format("%.1f", gyroThreshold)
        magnThresholdText.text = String.format("%.1f", magnThreshold)
        
        // Save the calculated thresholds
        sensorConfigManager.saveDetectionThreshold(acceThreshold, Constants.AccelerometerOutputName)
        sensorConfigManager.saveDetectionThreshold(gyroThreshold, Constants.GyroscopeOutputName)
        sensorConfigManager.saveDetectionThreshold(magnThreshold, Constants.MagnetometerOutputName)
    }
    
    /**
     * Set up text watchers for bias margin inputs
     */
    private fun setupBiasTextWatchers() {
        val biasEdits = listOf(acceBiasEdit, gyroBiasEdit, magnBiasEdit)
        
        biasEdits.forEach { editText ->
            editText.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    calculateAndDisplayThresholds()
                }
                
                override fun afterTextChanged(s: Editable?) {
                    val thresholds = sensorConfigManager.getAllThresholds()
                    Log.d(TAG, "Updated thresholds:\n" + thresholds.entries.joinToString("\n") { (key, value) -> "$key: $value" })
                }
            })
        }
    }
    
    /**
     * Reset bias margins to default value
     */
    private fun resetBiasMargins() {
        acceBiasEdit.setText(Constants.THRESHOLD_BIAS.toString())
        gyroBiasEdit.setText(Constants.THRESHOLD_BIAS.toString())
        magnBiasEdit.setText(Constants.THRESHOLD_BIAS.toString())
        calculateAndDisplayThresholds()
    }

    /**
     * Enable or disable input fields and button
     */
    private fun setInputsEnabled(enabled: Boolean) {
        acceBiasEdit.isEnabled = enabled
        gyroBiasEdit.isEnabled = enabled
        magnBiasEdit.isEnabled = enabled
        resetThresholdsButton.isEnabled = enabled
    }

    /**
     * Show message about invalid minimum rates and disable functionality
     */
    private fun showInvalidRatesMessage() {
        acceMinRateText.text = "N/A"
        gyroMinRateText.text = "N/A"
        magnMinRateText.text = "N/A"
        
        acceBiasEdit.setText("N/A")
        gyroBiasEdit.setText("N/A")
        magnBiasEdit.setText("N/A")
        
        acceThresholdText.text = "N/A"
        gyroThresholdText.text = "N/A"
        magnThresholdText.text = "N/A"
        
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Invalid Global Settings")
            .setMessage("Detection thresholds cannot be set because no valid minimum sampling rates have been configured.\n\nPlease run the profiling feature first to determine your device's minimum supported sensor sampling rates.")
            .setPositiveButton("Go to Profiling") { _, _ ->
                val intent = android.content.Intent(this, com.spqr.armour.ProfilingActivity::class.java)
                startActivity(intent)
                finish()
            }
            .setNegativeButton("Cancel") { _, _ ->
                finish()
            }
            .setCancelable(false)
            .show()
    }
}