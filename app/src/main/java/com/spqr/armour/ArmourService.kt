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

        logServiceSettingInfos(mTestName, mSampleRates, outputDir, isProfiling)

        rateIndex = 0
        sampleRateList = mSampleRates.split(",").map { it.trim() }

        mImuManager = IMUManager(this)
        timer = Timer()

        // for profiling
        if (isProfiling) {
            profRunDelay = intent.getIntExtra("profRunDelay", 10);
            Log.d("Sample Guard - Profiling", "Cycle run time: " + profRunDelay);
        }

        // create notification
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE)
        
        // Create a minimal notification that won't show in the notification drawer
        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // For Android Oreo and above, use a low importance notification channel
            val channelId = "armour_minimal_channel"
            val channel = NotificationChannel(
                channelId,
                "Minimal Service Channel",
                NotificationManager.IMPORTANCE_MIN
            )
            channel.setShowBadge(false)
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
            
            NotificationCompat.Builder(this, channelId)
                .setContentTitle("ARMOUR Service")
                .setContentText("Service running")
                .setSmallIcon(R.drawable.ic_launcher_background)
                .setContentIntent(pendingIntent)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setVisibility(NotificationCompat.VISIBILITY_SECRET)
                .setOngoing(true)
                .build()
        } else {
            // For older versions
            NotificationCompat.Builder(this, Constants.CHANNEL_ID)
                .setContentTitle("ARMOUR Service")
                .setContentText("Service running")
                .setSmallIcon(R.drawable.ic_launcher_background)
                .setContentIntent(pendingIntent)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setVisibility(NotificationCompat.VISIBILITY_SECRET)
                .setOngoing(true)
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

        timer = null
        mImuManager!!.stopRecording()
        mImuManager!!.unregister()
        mImuManager = null

        Log.d(Constants.mainLogTag, "Service onDestroy [Success]")
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
    }
}