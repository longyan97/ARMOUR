package com.spqr.armour

/**
 * Shared constants for the ARMOUR application
 */
object Constants {
    const val CHANNEL_ID = "ARMOUR_SERVICE" // for notification
    const val mainLogTag = "ARMOUR"
    const val MagnetometerOutputName = "Magnetometer"
    const val GyroscopeOutputName = "Gyroscope"
    const val AccelerometerOutputName = "Accelerometer"
    const val THRESHOLD_BIAS: Double = 0.5
    const val RETESTING_THRESHOLD = 0.05
    const val NO_SENSOR_ACCESS_NOTE: String = "No sensor access."
    
    // Broadcast action
    const val ACTION_PROFILING_COMPLETE = "com.spqr.armour.PROFILING_COMPLETE"
} 