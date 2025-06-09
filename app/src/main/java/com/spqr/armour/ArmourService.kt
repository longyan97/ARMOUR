package com.spqr.armour

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.spqr.armour.utils.cleanDirectory
import java.io.File
import java.util.Timer
import java.util.TimerTask

class ArmourService : Service() {

    private lateinit var sampleRateList: List<String>
    private var rateIndex = 0 // for multi sample rate testing

    private lateinit var mTestName: String
    private lateinit var mSampleRates: String
    private lateinit var outputDir: String
    private var isProfiling: Boolean = false
    private var profRunDelay: Int = 10

    private var mImuManager: IMUManager? = null
    private var timer: Timer? = null // for multi sample rate testing

    // Real-time detection
    private var realtimeDetectionManager: RealtimeDetectionManager? = null
    private var sensorConfigManager: SensorConfigManager? = null

    // Control flags for independent functionality
    private var enableLogging: Boolean = true
    private var enableRealtimeNotifications: Boolean = true
    
    // Notification timeout control
    private val notificationHandler = Handler(Looper.getMainLooper())
    private var notificationTimeoutRunnable: Runnable? = null
    private val HEADS_UP_DURATION_MS = 1500L // 1.5 seconds

    @SuppressLint("ForegroundServiceType")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(Constants.mainLogTag, "Service Starting...")
        if (intent == null) {
            return super.onStartCommand(intent, flags, startId)
        }

        mTestName = intent.getStringExtra("inputExtra").toString()
        mSampleRates = intent.getStringExtra("sampleRate").toString()
        outputDir = intent.getStringExtra("outputDir").toString()
        isProfiling = intent.getBooleanExtra("profiling", false)
        enableLogging = intent.getBooleanExtra("enableLogging", true)
        enableRealtimeNotifications = intent.getBooleanExtra("enableRealtimeNotifications", true)

        logServiceSettingInfos(mTestName, mSampleRates, outputDir, isProfiling)

        rateIndex = 0
        sampleRateList = mSampleRates.split(",").map { it.trim() }

        mImuManager = IMUManager(this)
        timer = Timer()

        // Initialize real-time detection for monitoring mode only (not profiling)
        // and only if real-time notifications are enabled
        if (!isProfiling && enableRealtimeNotifications) {
            initializeRealtimeDetection()
        } else {
            // Ensure no real-time detection manager is connected when disabled
            mImuManager?.setRealtimeDetectionManager(null)
            Log.d(Constants.mainLogTag, "ArmourService: Real-time notifications disabled - no detection manager created")
        }

        // for profiling
        if (isProfiling) {
            profRunDelay = intent.getIntExtra("profRunDelay", 10);
            Log.d("Sample Guard - Profiling", "Cycle run time: " + profRunDelay);
        }

        // create notification
        val notificationIntent = Intent(this, MonitoringActivity::class.java)
        // Add flags to bring the existing activity to front instead of creating a new one
        notificationIntent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE)
        
        // Create a highly visible notification for background monitoring
        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // For Android Oreo and above, create two notification channels
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            
            // Channel 1: High importance for sensor detection alerts
            val alertChannel = NotificationChannel(
                "${Constants.CHANNEL_ID}_ALERT",
                "ARMOUR Sensor Alerts",
                NotificationManager.IMPORTANCE_HIGH
            )
            alertChannel.description = "Alerts when sensor access is detected"
            alertChannel.enableLights(true)
            alertChannel.setShowBadge(true)
            alertChannel.enableVibration(false)
            notificationManager.createNotificationChannel(alertChannel)
            
            // Channel 2: Low importance for status updates (idle state)
            val statusChannel = NotificationChannel(
                "${Constants.CHANNEL_ID}_STATUS",
                "ARMOUR Status Updates", 
                NotificationManager.IMPORTANCE_LOW
            )
            statusChannel.description = "Shows monitoring status and idle state updates"
            statusChannel.enableLights(false)
            statusChannel.setShowBadge(true)
            statusChannel.enableVibration(false)
            notificationManager.createNotificationChannel(statusChannel)
            
            // Start with status channel for initial "monitoring active" notification
            NotificationCompat.Builder(this, "${Constants.CHANNEL_ID}_STATUS")
                .setContentTitle("ARMOUR Monitoring Active")
                .setContentText("Detecting sensor access by other apps")
                .setSmallIcon(R.drawable.ic_launcher_background)
                .setContentIntent(pendingIntent)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setOngoing(true)
                .setAutoCancel(false)
                .setOnlyAlertOnce(true)
                .build()
        } else {
            // For older versions, use the original channel ID with low priority for initial notification
            NotificationCompat.Builder(this, Constants.CHANNEL_ID)
                .setContentTitle("ARMOUR Monitoring Active")
                .setContentText("Detecting sensor access by other apps")
                .setSmallIcon(R.drawable.ic_launcher_background)
                .setContentIntent(pendingIntent)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setOngoing(true)
                .setAutoCancel(false)
                .setOnlyAlertOnce(true)
                .build()
        }

        startRecordingOneRate()
        // promote the service to the foreground state
        startForeground(1, notification)

        return START_NOT_STICKY
    }

    fun startRecordingOneRate() {
        if (mImuManager == null) {
            Log.d(Constants.mainLogTag, "ImuManager is None, Cannot start recording rate")
            return
        }

        if (timer == null) {
            Log.d(Constants.mainLogTag, "Timer is None, Cannot start recording rate")
            return
        }

        val aSampleRate: String = sampleRateList[rateIndex]
        Log.d(Constants.mainLogTag, "Starting recording for rate: '$aSampleRate' (index: $rateIndex)")
        
        if (aSampleRate[0] == '0') {
            Log.d(Constants.mainLogTag, "Default aSampleRate = ${aSampleRate.toDouble().toInt()}")
            IMUManager.mSensorRate = aSampleRate.toDouble().toInt()
        } else {
            val mSensorRate = Math.round(1 / (aSampleRate.toDouble() / Math.pow(10.0, 6.0))).toInt()
            Log.d(Constants.mainLogTag, "Customized aSampleRate = ${mSensorRate}")
            IMUManager.mSensorRate = mSensorRate
        }

        // Always create a subdirectory for the rate, even when there's only one rate
        // This fixes the bug where the requested rate is always shown as "1 Hz"
        val rateDir = File(outputDir, aSampleRate)
        if (rateDir.exists()) {
            rateDir.cleanDirectory()
        }

        val isDirCreated: Boolean = rateDir.mkdirs()
        Log.d(Constants.mainLogTag, rateDir.toString() + " Created: " + isDirCreated);
        
        mImuManager!!.register()
        mImuManager!!.startRecording(rateDir.toString())

        // Pass logging setting to IMUManager
        mImuManager!!.setLoggingEnabled(enableLogging)

        // Send broadcast that rate has changed
        if (isProfiling) {
            val intent = Intent(ProfilingActivity.ACTION_RATE_CHANGED)
            intent.putExtra("currentRateIndex", rateIndex)
            intent.putExtra("currentRate", aSampleRate)
            sendBroadcast(intent)
            Log.d(Constants.mainLogTag, "Sent rate changed broadcast for rate: '$aSampleRate'")
            
            timer!!.schedule(TimeExecution(), (profRunDelay * 1000).toLong())
        }
    }

    inner class TimeExecution: TimerTask() {
        override fun run() {
            mImuManager!!.stopRecording()
            mImuManager!!.unregister()

            Log.d(Constants.mainLogTag, "Rate done: " + IMUManager.mSensorRate)
            rateIndex += 1

            if (rateIndex < sampleRateList.size) {
                startRecordingOneRate()
            } else {
                Log.d(Constants.mainLogTag, "All recording done, killing service")
                
                // Send a broadcast to notify that all profiling is complete
                if (isProfiling) {
                    val intent = Intent(Constants.ACTION_PROFILING_COMPLETE)
                    intent.putExtra("testName", mTestName)
                    intent.putExtra("sampleRates", mSampleRates)
                    sendBroadcast(intent)
                    Log.d(Constants.mainLogTag, "Sent profiling complete broadcast")
                }
                
                stopSelf()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()

        Log.d(Constants.mainLogTag, "Service onDestroy")

        // Explicitly clear all notifications before other cleanup (Samsung S9 fix)
        clearAllNotifications()

        // Stop real-time detection
        realtimeDetectionManager?.stopDetection()
        mImuManager?.setRealtimeDetectionManager(null)
        realtimeDetectionManager = null

        // Clean up notification timeout
        notificationTimeoutRunnable?.let { notificationHandler.removeCallbacks(it) }
        notificationTimeoutRunnable = null

        timer = null
        mImuManager!!.stopRecording()
        mImuManager!!.unregister()
        mImuManager = null

        Log.d(Constants.mainLogTag, "Service onDestroy [Success]")
    }

    /**
     * Clear all notifications explicitly - Samsung device compatibility fix
     */
    private fun clearAllNotifications() {
        try {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            
            // Cancel specific notification ID first
            notificationManager.cancel(1)
            Log.d(Constants.mainLogTag, "ArmourService: Cancelled notification ID 1")
            
            // For Android O+ and Samsung devices, use more aggressive cleanup
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // Cancel notifications from both channels to handle dual-channel approach
                try {
                    // Note: We can't cancel by channel directly, but cancelAll() will clear everything
                    // This is more aggressive but necessary for Samsung devices
                    if (Build.MANUFACTURER.equals("samsung", ignoreCase = true) || 
                        Build.BRAND.equals("samsung", ignoreCase = true)) {
                        notificationManager.cancelAll()
                        Log.d(Constants.mainLogTag, "ArmourService: Used cancelAll() for Samsung device")
                    }
                } catch (e: Exception) {
                    Log.w(Constants.mainLogTag, "ArmourService: Samsung-specific cleanup failed, using standard cleanup", e)
                }
            }
            
            Log.d(Constants.mainLogTag, "ArmourService: Notification cleanup completed")
            
        } catch (e: Exception) {
            Log.e(Constants.mainLogTag, "ArmourService: Failed to clear notifications", e)
        }
    }

    override fun onBind(intent: Intent): IBinder? {
        return null;
    }

    fun logServiceSettingInfos(mTestName: String, mSampleRates: String, outputDir: String, isProfiling: Boolean) {
        Log.d(Constants.mainLogTag, "Sensor Setting Info: ")
        Log.d(Constants.mainLogTag, "mTestName = ${mTestName}")
        Log.d(Constants.mainLogTag, "mSampleRates = ${mSampleRates}")
        Log.d(Constants.mainLogTag, "outputDir = ${outputDir}")
        Log.d(Constants.mainLogTag, "isProfiling = ${isProfiling}")
        Log.d(Constants.mainLogTag, "enableLogging = ${enableLogging}")
        Log.d(Constants.mainLogTag, "enableRealtimeNotifications = ${enableRealtimeNotifications}")
    }

    /**
     * Initialize real-time detection system for monitoring mode
     */
    private fun initializeRealtimeDetection() {
        try {
            sensorConfigManager = SensorConfigManager.getInstance(this)
            
            // Only initialize if we have valid thresholds
            if (sensorConfigManager!!.hasValidMinimumRates()) {
                realtimeDetectionManager = RealtimeDetectionManager(
                    context = this,
                    sensorConfigManager = sensorConfigManager!!,
                    notificationCallback = ::handleDetectionEvent
                )
                
                // Connect to IMU manager
                mImuManager?.setRealtimeDetectionManager(realtimeDetectionManager)
                
                // Start detection
                realtimeDetectionManager?.startDetection()
                
                Log.d(Constants.mainLogTag, "ArmourService: Real-time detection initialized and started")
            } else {
                Log.w(Constants.mainLogTag, "ArmourService: Cannot initialize real-time detection - no valid thresholds")
            }
        } catch (e: Exception) {
            Log.e(Constants.mainLogTag, "ArmourService: Failed to initialize real-time detection", e)
        }
    }

    /**
     * Handle detection events from RealtimeDetectionManager
     */
    private fun handleDetectionEvent(event: RealtimeDetectionManager.DetectionEvent) {
        Log.d(Constants.mainLogTag, "ArmourService: Detection event - ${event.sensorName} ${event.eventType} (${String.format("%.1f", event.instantRate)} Hz)")
        
        // Update notification for both start and end events
        // But make end events silent (no sound/vibration)
        val isSilentUpdate = (event.eventType == RealtimeDetectionManager.EventType.USAGE_ENDED)
        updateNotificationWithDetection(event, isSilentUpdate)
    }

    /**
     * Update the foreground notification to show detection information
     */
    private fun updateNotificationWithDetection(event: RealtimeDetectionManager.DetectionEvent, isSilentUpdate: Boolean) {
        try {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val detectionStatus = realtimeDetectionManager?.getDetectionStatus() ?: emptyMap()
            
            // Count active detections
            val activeDetections = detectionStatus.values.count { it == "DETECTED" }
            val activeSensors = detectionStatus.filterValues { it == "DETECTED" }.keys
            
            // Create updated notification content
            val (title, text) = when {
                activeDetections == 0 -> {
                    "ARMOUR Monitoring Active" to "Detecting sensor access by other apps"
                }
                activeDetections == 1 -> {
                    "⚠️ Sensor Access Detected" to "${activeSensors.first()} being used (${String.format("%.1f", event.instantRate)} Hz)"
                }
                else -> {
                    "⚠️ Multiple Sensors Detected" to "${activeSensors.joinToString(", ")} being used"
                }
            }
            
            // Build updated notification
            val notificationIntent = Intent(this, MonitoringActivity::class.java)
            notificationIntent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE)
            
            // Choose appropriate channel and priority based on detection state
            val (channelId, priority, shouldAlert) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                when {
                    activeDetections > 0 -> Triple("${Constants.CHANNEL_ID}_ALERT", NotificationCompat.PRIORITY_HIGH, !isSilentUpdate)
                    else -> Triple("${Constants.CHANNEL_ID}_STATUS", NotificationCompat.PRIORITY_LOW, false) // Always silent for idle state
                }
            } else {
                // For older Android versions, use original channel with appropriate priority
                val legacyPriority = if (isSilentUpdate || activeDetections == 0) {
                    NotificationCompat.PRIORITY_LOW
                } else {
                    if (activeDetections > 0) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT
                }
                Triple(Constants.CHANNEL_ID, legacyPriority, !isSilentUpdate && activeDetections > 0)
            }
            
            val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationCompat.Builder(this, channelId)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setSmallIcon(R.drawable.ic_launcher_background)
                    .setContentIntent(pendingIntent)
                    .setPriority(priority)
                    .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                    .setOngoing(true)
                    .setAutoCancel(false)
                    .setOnlyAlertOnce(!shouldAlert) // Don't alert for silent updates or idle states
                    .setSilent(!shouldAlert) // Make silent if shouldn't alert
                    .setGroup("armour_notifications") // Group to prevent spam
                    .build()
            } else {
                NotificationCompat.Builder(this, channelId)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setSmallIcon(R.drawable.ic_launcher_background)
                    .setContentIntent(pendingIntent)
                    .setPriority(priority)
                    .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                    .setOngoing(true)
                    .setAutoCancel(false)
                    .setOnlyAlertOnce(!shouldAlert) // Don't alert for silent updates or idle states
                    .setGroup("armour_notifications") // Group to prevent spam
                    .build()
            }
            
            // Update the notification
            notificationManager.notify(1, notification)
            
            // Only schedule priority reduction for alert notifications (not status updates)
            if (shouldAlert && activeDetections > 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                scheduleNotificationPriorityReduction(notificationManager, title, text, pendingIntent)
            }
            
            Log.d(Constants.mainLogTag, "ArmourService: Updated notification - Channel: $channelId, Priority: $priority, ShouldAlert: $shouldAlert, ActiveDetections: $activeDetections")
            
        } catch (e: Exception) {
            Log.e(Constants.mainLogTag, "ArmourService: Failed to update notification", e)
        }
    }

    /**
     * Schedule automatic reduction of notification priority after heads-up duration
     * This transitions from alert channel to status channel to ensure heads-up disappears
     */
    private fun scheduleNotificationPriorityReduction(
        notificationManager: NotificationManager, 
        title: String, 
        text: String, 
        pendingIntent: PendingIntent
    ) {
        // Cancel any existing timeout
        notificationTimeoutRunnable?.let { notificationHandler.removeCallbacks(it) }
        
        notificationTimeoutRunnable = Runnable {
            try {
                // Transition from alert channel to status channel to ensure heads-up disappears
                // This is more effective than just changing priority on the same channel
                val notification = NotificationCompat.Builder(this, "${Constants.CHANNEL_ID}_STATUS")
                    .setContentTitle(title)
                    .setContentText(text)
                    .setSmallIcon(R.drawable.ic_launcher_background)
                    .setContentIntent(pendingIntent)
                    .setPriority(NotificationCompat.PRIORITY_LOW) // Use low priority in status channel
                    .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                    .setOngoing(true)
                    .setAutoCancel(false)
                    .setOnlyAlertOnce(true) // Don't alert again
                    .setSilent(true) // Make it completely silent
                    .setGroup("armour_notifications")
                    .build()
                
                // Update with status channel notification
                notificationManager.notify(1, notification)
                Log.d(Constants.mainLogTag, "ArmourService: Transitioned to status channel after ${HEADS_UP_DURATION_MS}ms")
                
            } catch (e: Exception) {
                Log.e(Constants.mainLogTag, "ArmourService: Failed to transition notification channel", e)
            }
        }
        
        // Schedule the channel transition
        notificationHandler.postDelayed(notificationTimeoutRunnable!!, HEADS_UP_DURATION_MS)
        Log.d(Constants.mainLogTag, "ArmourService: Scheduled notification channel transition in ${HEADS_UP_DURATION_MS}ms")
    }
}