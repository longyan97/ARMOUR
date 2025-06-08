package com.spqr.armour

import android.content.Context
import android.hardware.Sensor
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * Real-time sensor usage detection using state machine approach.
 * Detects transitions: below threshold → above threshold (usage started)
 *                     above threshold → below threshold (usage ended)
 */
class RealtimeDetectionManager(
    private val context: Context,
    private val sensorConfigManager: SensorConfigManager,
    private val notificationCallback: (DetectionEvent) -> Unit
) {
    
    // ==================== DETECTION PARAMETERS ====================
    // These parameters can be easily adjusted by developers as needed
    
    /**
     * THRESHOLD_CROSSING_CONFIRMATION_SAMPLES: Number of consecutive samples needed to confirm state transition
     * Value: 3
     * Purpose: Prevents noise-induced state flipping by requiring multiple consistent readings
     * TUNING: Lower = faster detection, Higher = more noise rejection
     */
    private val THRESHOLD_CROSSING_CONFIRMATION_SAMPLES = 3
    
    /**
     * ANALYSIS_FREQUENCY_MS: How often to check sensor states and calculate instantaneous rates
     * Value: 100ms (reduced from 200ms for faster detection)
     * Purpose: Balance between responsiveness and CPU usage
     * TUNING: Lower = faster detection but more CPU, Higher = slower detection but less CPU
     */
    private val ANALYSIS_FREQUENCY_MS = 100L
    
    /**
     * MINIMUM_SUSTAINED_DURATION_MS: Minimum time above threshold before confirming detection
     * Value: 200ms (reduced from 300ms for faster response)
     * Purpose: Suppress brief spikes and ensure genuine sensor usage
     * TUNING: Lower = faster detection but more false positives, Higher = slower but more accurate
     */
    private val MINIMUM_SUSTAINED_DURATION_MS = 200L
    
    /**
     * NOTIFICATION_RATE_LIMIT_MS: Maximum frequency of notifications for new sensor usage
     * Value: 5000ms (5 seconds as specified)
     * Purpose: Prevent notification spam while allowing immediate re-detection after usage ends
     */
    private val NOTIFICATION_RATE_LIMIT_MS = 5000L
    
    /**
     * SIMULTANEOUS_DETECTION_GROUPING_WINDOW_MS: Time window to group multiple sensor detections
     * Value: 2000ms (2 seconds as specified) 
     * Purpose: Group nearby detections into single notification (e.g., "Accel + Gyro detected")
     */
    private val SIMULTANEOUS_DETECTION_GROUPING_WINDOW_MS = 2000L
    
    /**
     * INSTANTANEOUS_RATE_CALCULATION_WINDOW: Number of timestamps to use for rate calculation
     * Value: 2 (matches existing offline detection: 1e9 / (curTime - preTime))
     * Purpose: Calculate instantaneous sampling rate from consecutive sensor timestamps
     */
    private val INSTANTANEOUS_RATE_CALCULATION_WINDOW = 2
    
    // ==================== END PARAMETERS ====================
    
    // Detection states for each sensor
    enum class SensorState {
        IDLE,       // Below threshold
        ACTIVE,     // Above threshold but not yet confirmed
        DETECTED    // Above threshold and confirmed (≥300ms)
    }
    
    // Detection event data class
    data class DetectionEvent(
        val sensorType: Int,
        val sensorName: String,
        val eventType: EventType,
        val instantRate: Double,
        val threshold: Double,
        val timestamp: Long = System.currentTimeMillis()
    )
    
    enum class EventType {
        USAGE_STARTED,    // Transition from ACTIVE to DETECTED
        USAGE_ENDED       // Transition from DETECTED to IDLE
    }
    
    // State tracking per sensor
    private data class SensorStateData(
        var state: SensorState = SensorState.IDLE,
        var stateStartTime: Long = 0L,
        var confirmationSamples: Int = 0,  // Count of consecutive samples confirming current transition
        val recentTimestamps: ArrayDeque<Long> = ArrayDeque(),
        var lastNotificationTime: Long = 0L
    )
    
    // Per-sensor state tracking (thread-safe)
    private val sensorStates = ConcurrentHashMap<Int, SensorStateData>()
    
    // Analysis timer
    private val analysisHandler = Handler(Looper.getMainLooper())
    private var isRunning = false
    
    // Pending grouped notifications
    private val pendingGroupedDetections = mutableListOf<DetectionEvent>()
    private var groupingTimer: Runnable? = null
    
    /**
     * Start real-time detection analysis
     */
    fun startDetection() {
        if (isRunning) return
        
        isRunning = true
        Log.d(Constants.mainLogTag, "RealtimeDetectionManager: Starting detection")
        
        // Initialize sensor states
        initializeSensorStates()
        
        // Start periodic analysis
        scheduleNextAnalysis()
    }
    
    /**
     * Stop real-time detection analysis
     */
    fun stopDetection() {
        if (!isRunning) return
        
        isRunning = false
        Log.d(Constants.mainLogTag, "RealtimeDetectionManager: Stopping detection")
        
        // Cancel analysis timer
        analysisHandler.removeCallbacksAndMessages(null)
        
        // End any ongoing detections
        endAllActiveDetections()
        
        // Clear state data
        sensorStates.clear()
        pendingGroupedDetections.clear()
    }
    
    /**
     * Process new sensor timestamp (called from IMUManager.onSensorChanged)
     */
    fun onSensorTimestamp(sensorType: Int, timestamp: Long) {
        if (!isRunning) return
        
        val stateData = sensorStates[sensorType] ?: return
        
        // Add timestamp to recent history
        synchronized(stateData) {
            stateData.recentTimestamps.addLast(timestamp)
            
            // Keep only enough timestamps for rate calculation
            while (stateData.recentTimestamps.size > INSTANTANEOUS_RATE_CALCULATION_WINDOW) {
                stateData.recentTimestamps.removeFirst()
            }
        }
    }
    
    /**
     * Initialize state tracking for all supported sensors
     */
    private fun initializeSensorStates() {
        // Initialize state for accelerometer, gyroscope, magnetometer
        sensorStates[Sensor.TYPE_ACCELEROMETER] = SensorStateData()
        sensorStates[Sensor.TYPE_GYROSCOPE] = SensorStateData()
        sensorStates[Sensor.TYPE_MAGNETIC_FIELD] = SensorStateData()
        
        Log.d(Constants.mainLogTag, "RealtimeDetectionManager: Initialized states for ${sensorStates.size} sensors")
    }
    
    /**
     * Periodic analysis of all sensor states
     */
    private fun scheduleNextAnalysis() {
        if (!isRunning) return
        
        analysisHandler.postDelayed({
            performStateAnalysis()
            scheduleNextAnalysis()  // Schedule next analysis
        }, ANALYSIS_FREQUENCY_MS)
    }
    
    /**
     * Analyze current state for all sensors
     */
    private fun performStateAnalysis() {
        val currentTime = System.currentTimeMillis()
        
        sensorStates.forEach { (sensorType, stateData) ->
            synchronized(stateData) {
                val instantRate = calculateInstantaneousRate(stateData)
                val threshold = getThresholdForSensor(sensorType)
                
                if (instantRate != null && threshold != null) {
                    processStateMachine(sensorType, stateData, instantRate, threshold, currentTime)
                }
            }
        }
    }
    
    /**
     * Calculate instantaneous sampling rate from recent timestamps
     * Matches existing offline detection method: 1e9 / (curTime - preTime)
     */
    private fun calculateInstantaneousRate(stateData: SensorStateData): Double? {
        if (stateData.recentTimestamps.size < INSTANTANEOUS_RATE_CALCULATION_WINDOW) {
            return null
        }
        
        val latest = stateData.recentTimestamps.last()
        val previous = stateData.recentTimestamps[stateData.recentTimestamps.size - 2]
        
        val timeDiff = latest - previous
        return if (timeDiff > 0) {
            1e9 / timeDiff.toDouble()  // Convert nanoseconds to Hz
        } else null
    }
    
    /**
     * Get detection threshold for specific sensor type
     */
    private fun getThresholdForSensor(sensorType: Int): Double? {
        return when (sensorType) {
            Sensor.TYPE_ACCELEROMETER -> sensorConfigManager.getAcceThreshold()
            Sensor.TYPE_GYROSCOPE -> sensorConfigManager.getGyroThreshold()
            Sensor.TYPE_MAGNETIC_FIELD -> sensorConfigManager.getMagnThreshold()
            else -> null
        }
    }
    
    /**
     * State machine logic for individual sensor
     */
    private fun processStateMachine(
        sensorType: Int,
        stateData: SensorStateData,
        instantRate: Double,
        threshold: Double,
        currentTime: Long
    ) {
        val isAboveThreshold = instantRate > threshold
        
        when (stateData.state) {
            SensorState.IDLE -> {
                if (isAboveThreshold) {
                    stateData.confirmationSamples++
                    if (stateData.confirmationSamples >= THRESHOLD_CROSSING_CONFIRMATION_SAMPLES) {
                        // Transition to ACTIVE
                        stateData.state = SensorState.ACTIVE
                        stateData.stateStartTime = currentTime
                        stateData.confirmationSamples = 0
                        Log.d(Constants.mainLogTag, "RealtimeDetectionManager: ${getSensorName(sensorType)} transitioned to ACTIVE (rate: ${"%.1f".format(instantRate)} Hz)")
                    }
                } else {
                    stateData.confirmationSamples = 0  // Reset confirmation counter
                }
            }
            
            SensorState.ACTIVE -> {
                if (isAboveThreshold) {
                    // Check if sustained long enough to confirm detection
                    val duration = currentTime - stateData.stateStartTime
                    if (duration >= MINIMUM_SUSTAINED_DURATION_MS) {
                        // Transition to DETECTED
                        stateData.state = SensorState.DETECTED
                        sendDetectionEvent(sensorType, EventType.USAGE_STARTED, instantRate, threshold, currentTime)
                        Log.d(Constants.mainLogTag, "RealtimeDetectionManager: ${getSensorName(sensorType)} DETECTED (sustained for ${duration}ms)")
                    }
                } else {
                    stateData.confirmationSamples++
                    if (stateData.confirmationSamples >= THRESHOLD_CROSSING_CONFIRMATION_SAMPLES) {
                        // Brief spike ended before confirmation - return to IDLE
                        stateData.state = SensorState.IDLE
                        stateData.confirmationSamples = 0
                        Log.d(Constants.mainLogTag, "RealtimeDetectionManager: ${getSensorName(sensorType)} brief spike ended, returned to IDLE")
                    }
                }
            }
            
            SensorState.DETECTED -> {
                if (!isAboveThreshold) {
                    stateData.confirmationSamples++
                    if (stateData.confirmationSamples >= THRESHOLD_CROSSING_CONFIRMATION_SAMPLES) {
                        // Usage ended - transition to IDLE
                        stateData.state = SensorState.IDLE
                        stateData.confirmationSamples = 0
                        sendDetectionEvent(sensorType, EventType.USAGE_ENDED, instantRate, threshold, currentTime)
                        Log.d(Constants.mainLogTag, "RealtimeDetectionManager: ${getSensorName(sensorType)} usage ENDED")
                    }
                } else {
                    stateData.confirmationSamples = 0  // Reset counter while still above threshold
                }
            }
        }
    }
    
    /**
     * Send detection event with rate limiting and grouping
     */
    private fun sendDetectionEvent(
        sensorType: Int,
        eventType: EventType,
        instantRate: Double,
        threshold: Double,
        currentTime: Long
    ) {
        val stateData = sensorStates[sensorType] ?: return
        
        // Check rate limiting for new detections
        if (eventType == EventType.USAGE_STARTED) {
            if (currentTime - stateData.lastNotificationTime < NOTIFICATION_RATE_LIMIT_MS) {
                Log.d(Constants.mainLogTag, "RealtimeDetectionManager: Rate limited notification for ${getSensorName(sensorType)}")
                return
            }
            stateData.lastNotificationTime = currentTime
        }
        
        val event = DetectionEvent(
            sensorType = sensorType,
            sensorName = getSensorName(sensorType),
            eventType = eventType,
            instantRate = instantRate,
            threshold = threshold,
            timestamp = currentTime
        )
        
        // Handle grouping for simultaneous detections
        if (eventType == EventType.USAGE_STARTED) {
            handleGroupedDetection(event)
        } else {
            // Send usage ended events immediately
            notificationCallback(event)
        }
    }
    
    /**
     * Handle grouping of simultaneous detections
     */
    private fun handleGroupedDetection(event: DetectionEvent) {
        pendingGroupedDetections.add(event)
        
        // Cancel existing grouping timer
        groupingTimer?.let { analysisHandler.removeCallbacks(it) }
        
        // Set new grouping timer
        groupingTimer = Runnable {
            if (pendingGroupedDetections.isNotEmpty()) {
                // Send all pending detections (will be grouped by notification system)
                pendingGroupedDetections.forEach { notificationCallback(it) }
                pendingGroupedDetections.clear()
            }
        }
        
        analysisHandler.postDelayed(groupingTimer!!, SIMULTANEOUS_DETECTION_GROUPING_WINDOW_MS)
    }
    
    /**
     * End all active detections (called when stopping detection)
     */
    private fun endAllActiveDetections() {
        val currentTime = System.currentTimeMillis()
        
        sensorStates.forEach { (sensorType, stateData) ->
            if (stateData.state == SensorState.DETECTED) {
                val threshold = getThresholdForSensor(sensorType) ?: 0.0
                sendDetectionEvent(sensorType, EventType.USAGE_ENDED, 0.0, threshold, currentTime)
            }
        }
    }
    
    /**
     * Get human-readable sensor name
     */
    private fun getSensorName(sensorType: Int): String {
        return when (sensorType) {
            Sensor.TYPE_ACCELEROMETER -> Constants.AccelerometerOutputName
            Sensor.TYPE_GYROSCOPE -> Constants.GyroscopeOutputName  
            Sensor.TYPE_MAGNETIC_FIELD -> Constants.MagnetometerOutputName
            else -> "Unknown Sensor"
        }
    }
    
    /**
     * Get current detection status for debugging
     */
    fun getDetectionStatus(): Map<String, String> {
        return sensorStates.mapKeys { (sensorType, _) -> getSensorName(sensorType) }
            .mapValues { (_, stateData) -> stateData.state.name }
    }
} 