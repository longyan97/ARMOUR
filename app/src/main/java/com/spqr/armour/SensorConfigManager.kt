package com.spqr.armour

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

/**
 * Manager class for handling sensor configuration settings
 * that persist across app sessions.
 */
class SensorConfigManager(context: Context) {
    
    companion object {
        private const val PREFS_NAME = "sensor_config_prefs"
        private const val KEY_ACCE_MIN_RATE = "accel_min_rate"
        private const val KEY_GYRO_MIN_RATE = "gyro_min_rate"
        private const val KEY_MAGN_MIN_RATE = "mag_min_rate"
        private const val KEY_ACCE_THRESHOLD = "acce_threshold"
        private const val KEY_GYRO_THRESHOLD = "gyro_threshold"
        private const val KEY_MAGN_THRESHOLD = "magn_threshold"
        
        // Default values if no configuration is set - use invalid values
        private const val INVALID_RATE = -1.0 // Invalid rate indicating no profiling has been done
        
        // Singleton instance
        @Volatile
        private var INSTANCE: SensorConfigManager? = null
        
        fun getInstance(context: Context): SensorConfigManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SensorConfigManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
    
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    
    // Cache for the current values
    private var cachedAcceRate: Float? = null
    private var cachedGyroRate: Float? = null
    private var cachedMagnRate: Float? = null
    private var cachedAcceThreshold: Float? = null
    private var cachedGyroThreshold: Float? = null
    private var cachedMagnThreshold: Float? = null
    
    /**
     * Save the minimum supported sampling rates for all sensors
     */
    fun saveMinimumSamplingRates(accelRate: Double, gyroRate: Double, magRate: Double) {
        val acceRateFloat = accelRate.toFloat()
        val gyroRateFloat = gyroRate.toFloat()
        val magnRateFloat = magRate.toFloat()
        val acceThreshold = acceRateFloat + Constants.THRESHOLD_BIAS.toFloat()
        val gyroThreshold = gyroRateFloat + Constants.THRESHOLD_BIAS.toFloat()
        val magnThreshold = magnRateFloat + Constants.THRESHOLD_BIAS.toFloat()

        
        prefs.edit().apply {
            putFloat(KEY_ACCE_MIN_RATE, acceRateFloat)
            putFloat(KEY_GYRO_MIN_RATE, gyroRateFloat)
            putFloat(KEY_MAGN_MIN_RATE, magnRateFloat)
            putFloat(KEY_ACCE_THRESHOLD, acceThreshold)
            putFloat(KEY_GYRO_THRESHOLD, gyroThreshold)
            putFloat(KEY_MAGN_THRESHOLD, magnThreshold)
            apply()
        }
        
        // Update the cache
        cachedAcceRate = acceRateFloat
        cachedGyroRate = gyroRateFloat
        cachedMagnRate = magnRateFloat
        cachedAcceThreshold = acceThreshold
        cachedGyroThreshold = gyroThreshold
        cachedMagnThreshold = magnThreshold

        
        Log.d(Constants.mainLogTag, "SensorConfigManager: Saved minimum sampling rates - " +
                "Accel: $accelRate Hz, Gyro: $gyroRate Hz, Mag: $magRate Hz")
    }

    fun saveDetectionThreshold(threshold: Double, sensorName: String) {

        when (sensorName) {
            Constants.AccelerometerOutputName -> {
                prefs.edit().putFloat(KEY_ACCE_THRESHOLD, threshold.toFloat()).apply()
                cachedAcceThreshold = threshold.toFloat()
            }
            Constants.GyroscopeOutputName -> {
                prefs.edit().putFloat(KEY_GYRO_THRESHOLD, threshold.toFloat()).apply()
                cachedGyroThreshold = threshold.toFloat()
            }
            Constants.MagnetometerOutputName -> {
                prefs.edit().putFloat(KEY_MAGN_THRESHOLD, threshold.toFloat()).apply()
                cachedMagnThreshold = threshold.toFloat()
            }
        }

        Log.d(Constants.mainLogTag, "SensorConfigManager: Saved detection threshold for $sensorName: $threshold")
    }
    
    /**
     * Force refresh all cached values from SharedPreferences
     */
    fun refreshValues() {
        cachedAcceRate = null
        cachedGyroRate = null
        cachedMagnRate = null

        cachedAcceThreshold = null
        cachedGyroThreshold = null
        cachedMagnThreshold = null
        
        Log.d(Constants.mainLogTag, "SensorConfigManager: Refreshed all cached values")
    }
    
    /**
     * Get the minimum supported sampling rate for accelerometer
     */
    fun getAcceMinRate(): Double {
        val rate = cachedAcceRate ?: prefs.getFloat(KEY_ACCE_MIN_RATE, INVALID_RATE.toFloat()).also {
            cachedAcceRate = it
        }
        return rate.toDouble()
    }
    
    /**
     * Get the minimum supported sampling rate for gyroscope
     */
    fun getGyroMinRate(): Double {
        val rate = cachedGyroRate ?: prefs.getFloat(KEY_GYRO_MIN_RATE, INVALID_RATE.toFloat()).also {
            cachedGyroRate = it
        }
        return rate.toDouble()
    }
    
    /**
     * Get the minimum supported sampling rate for magnetometer
     */
    fun getMagnMinRate(): Double {
        val rate = cachedMagnRate ?: prefs.getFloat(KEY_MAGN_MIN_RATE, INVALID_RATE.toFloat()).also {
            cachedMagnRate = it
        }
        return rate.toDouble()
    }
    
    /**
     * Get all minimum supported sampling rates as a map
     */
    fun getAllMinRates(): Map<String, Double> {
        return mapOf(
            Constants.AccelerometerOutputName to getAcceMinRate(),
            Constants.GyroscopeOutputName to getGyroMinRate(),
            Constants.MagnetometerOutputName to getMagnMinRate()
        )
    }

    fun getRateCachedStatus(): Boolean {
        // If all rates are cached, return true
        if (cachedAcceRate != null && cachedGyroRate != null && cachedMagnRate != null) {
            return true
        }

        // Try to load from SharedPreferences if not cached
        val acce = prefs.getFloat(KEY_ACCE_MIN_RATE, Float.NaN)
        val gyro = prefs.getFloat(KEY_GYRO_MIN_RATE, Float.NaN)
        val magn = prefs.getFloat(KEY_MAGN_MIN_RATE, Float.NaN)

        val hasAll = !acce.isNaN() && !gyro.isNaN() && !magn.isNaN()
        if (hasAll) {
            cachedAcceRate = acce
            cachedGyroRate = gyro
            cachedMagnRate = magn

            return true
        }

        return false
    }

    fun getAcceThreshold(): Double {
        val threshold = cachedAcceThreshold ?: prefs.getFloat(KEY_ACCE_THRESHOLD, 0.0f).also {
            cachedAcceThreshold = it
        }
        return threshold.toDouble()
    }

    fun getGyroThreshold(): Double {
        val threshold = cachedGyroThreshold ?: prefs.getFloat(KEY_GYRO_THRESHOLD, 0.0f).also {
            cachedGyroThreshold = it
        }
        return threshold.toDouble()
    }

    fun getMagnThreshold(): Double {
        val threshold = cachedMagnThreshold ?: prefs.getFloat(KEY_MAGN_THRESHOLD, 0.0f).also {
            cachedMagnThreshold = it
        }
        return threshold.toDouble()
    }

    fun getAllThresholds(): Map<String, Double> {
        return mapOf(
            Constants.AccelerometerOutputName to getAcceThreshold(),
            Constants.GyroscopeOutputName to getGyroThreshold(),
            Constants.MagnetometerOutputName to getMagnThreshold()
        )
    }

    fun getThresholdCachedStatus(): Boolean {
        // If all thresholds are cached, return true
        if (cachedAcceThreshold != null && cachedGyroThreshold != null && cachedMagnThreshold != null) {
            return true
        }

        // Try to load from SharedPreferences if not cached
        val acce = prefs.getFloat(KEY_ACCE_THRESHOLD, Float.NaN)
        val gyro = prefs.getFloat(KEY_GYRO_THRESHOLD, Float.NaN)
        val magn = prefs.getFloat(KEY_MAGN_THRESHOLD, Float.NaN)

        val hasAll = !acce.isNaN() && !gyro.isNaN() && !magn.isNaN()
        if (hasAll) {
            cachedAcceThreshold = acce
            cachedGyroThreshold = gyro
            cachedMagnThreshold = magn

            return true
        }

        return false
    }

    /**
     * Check if valid minimum sampling rates have been set (i.e., profiling has been run)
     */
    fun hasValidMinimumRates(): Boolean {
        val acceRate = prefs.getFloat(KEY_ACCE_MIN_RATE, INVALID_RATE.toFloat())
        val gyroRate = prefs.getFloat(KEY_GYRO_MIN_RATE, INVALID_RATE.toFloat())
        val magnRate = prefs.getFloat(KEY_MAGN_MIN_RATE, INVALID_RATE.toFloat())
        
        return acceRate > 0 && gyroRate > 0 && magnRate > 0
    }
    
    /**
     * Get a user-friendly status message about the minimum rates
     */
    fun getMinimumRatesStatusMessage(): String {
        return if (hasValidMinimumRates()) {
            val rates = getAllMinRates()
            "Global minimum rates are set:\n" +
                    "• Accelerometer: ${String.format("%.1f", rates[Constants.AccelerometerOutputName])} Hz\n" +
                    "• Gyroscope: ${String.format("%.1f", rates[Constants.GyroscopeOutputName])} Hz\n" +
                    "• Magnetometer: ${String.format("%.1f", rates[Constants.MagnetometerOutputName])} Hz"
        } else {
            "No minimum sampling rates have been configured.\nProfiling must be run first to determine device capabilities."
        }
    }
} 