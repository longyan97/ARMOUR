package com.spqr.armour

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.text.isDigitsOnly
import com.jjoe64.graphview.GraphView
import com.jjoe64.graphview.series.DataPoint
import com.jjoe64.graphview.series.LineGraphSeries
import com.opencsv.CSVReader
import com.spqr.armour.databinding.ActivityMonitoringBinding
import com.spqr.armour.utils.FileUtils
import com.spqr.armour.utils.KeyboardUtils
import com.spqr.armour.utils.SensorDataUtils
import java.io.File
import java.io.FileReader
import java.text.SimpleDateFormat
import java.util.*

class MonitoringActivity_backup : AppCompatActivity() {

    // ui binding
    private lateinit var testNameEditText: EditText
    private lateinit var sampleRateEditText: EditText
    private lateinit var binding: ActivityMonitoringBinding
    private lateinit var startStopButton: Button
    private lateinit var generateGraphButton: Button
    private lateinit var backgroundMonitoringCheckBox: CheckBox
    private lateinit var outputDir: String

    private val REQUESt_CODE_ASK_PERMISSIONS = 5947 // for permission request and response
    private val REQUIRED_SDK_PERMISSIONS = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        // Android 10 (API 29) and above don't need WRITE_EXTERNAL_STORAGE for app-specific directories
        listOf(android.Manifest.permission.FOREGROUND_SERVICE)
    } else {
        // For older Android versions, keep requesting WRITE_EXTERNAL_STORAGE
        listOf(android.Manifest.permission.FOREGROUND_SERVICE, android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }

    private var mTestName: String = "unnamed"
    private var mSampleRates: String = "1"
    private var isServiceRunning: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)

        // ui binding
        binding = ActivityMonitoringBinding.inflate(layoutInflater)
        setContentView(binding.root)
        testNameEditText = binding.editTextInput
        sampleRateEditText = binding.editSampleRate
        startStopButton = binding.btnStartStop
        generateGraphButton = binding.btnGenerateGraph
        backgroundMonitoringCheckBox = binding.checkboxBackgroundMonitoring

        // event binding
        startStopButton.setOnClickListener{ view -> 
            KeyboardUtils.hideKeyboard(this)
            onClickStartStopButton(view) 
        } // start/stop service
        generateGraphButton.setOnClickListener{ view -> 
            KeyboardUtils.hideKeyboard(this)
            onGenerateButtonClicked(view) 
        }

        checkPermission()
        // Removed createNotificationChannel() - now handled by ArmourService

        // Initialize output directory with default name
        val dateFormat = SimpleDateFormat("yyyy_MM_dd_HH_mm_ss", Locale.US)
        val timestamp = dateFormat.format(Date())
        outputDir = getExternalFilesDir(Environment.getDataDirectory().absolutePath)?.absolutePath + 
                File.separator + "${timestamp}_Monitoring_unnamed"
    }

    override fun onResume() {
        super.onResume()
        
        // Don't automatically stop the service when resuming the activity
        // This allows the user to check on monitoring progress and return to the background
        
        // Update UI to reflect current state
        if (isServiceRunning) {
            startStopButton.text = "Stop"
        } else {
            startStopButton.text = "Start Monitoring"
        }
    }

    fun onClickStartStopButton(view: View) {
        // if service is running, stop it.
        if (isServiceRunning) {
            stopRecordingService()
            return
        }

        // else start the service
        mTestName = testNameEditText.text.toString().trim()
        mSampleRates = sampleRateEditText.text.toString().trim()

        // Set default values if fields are empty
        if (mTestName.isEmpty()) {
            mTestName = "unnamed"
            Log.d(Constants.mainLogTag, "Using default test name: unnamed")
        }
        
        if (mSampleRates.isEmpty()) {
            mSampleRates = "1"
            Log.d(Constants.mainLogTag, "Using default sample rate: 1")
        }

        val flag = checkAndUpdateMSampleRates() // check inputs
        if (!flag) { return; }

        // init service
        outputDir = resetOutputDir(this)
        val serviceIntent = Intent(this, ArmourService::class.java)
        serviceIntent.putExtra("inputExtra", mTestName)
        serviceIntent.putExtra("sampleRate", mSampleRates)
        serviceIntent.putExtra("outputDir", outputDir)
        serviceIntent.putExtra("profiling", false) // Never profiling from monitoring activity
        startStopButton.text = "Stop"

        ContextCompat.startForegroundService(this, serviceIntent)
        isServiceRunning = true
        
        // If background monitoring is checked, move app to background
        if (backgroundMonitoringCheckBox.isChecked) {
            moveTaskToBack(true) // Move app to background
        }
    }

    fun stopRecordingService() {
        Log.d(Constants.mainLogTag, "Stop Recording Service Start ...")
        val serviceIntent = Intent(this, ArmourService::class.java)
        stopService(serviceIntent)
        startStopButton.text = "Start Monitoring"
        isServiceRunning = false
        Toast.makeText(this, "Recording Ended: ${mTestName}", Toast.LENGTH_SHORT).show()
        
        Log.d(Constants.mainLogTag, "Stop Recording Service End")
    }

    protected fun resetOutputDir(context: Context): String {
        return FileUtils.createUniqueMonitoringDir(context, mTestName)
    }

    private fun onGenerateButtonClicked(view: View) {
        // Update the current sample rate from the UI
        mSampleRates = sampleRateEditText.text.toString().trim()
        if (mSampleRates.isEmpty()) {
            mSampleRates = "1"
        }
        
        // Update the test name from the UI
        mTestName = testNameEditText.text.toString().trim()
        if (mTestName.isEmpty()) {
            mTestName = "unnamed"
        }
        
        generateGraphs(view)
    }

    /**
     * Find the most recent monitoring directory for the current test name
     * Similar to MinSampRateFinder.findTestDirectory but for Monitoring directories
     */
    private fun findMonitoringDirectory(): File? {
        val baseDir = getExternalFilesDir(Environment.getDataDirectory().absolutePath)
        if (baseDir == null || !baseDir.exists() || !baseDir.isDirectory) {
            Log.e(Constants.mainLogTag, "Base directory does not exist")
            return null
        }
        
        // Look for directories with timestamp pattern: YYYY_MM_DD_HH_mm_ss_Monitoring_testName
        val allDirs = baseDir.listFiles()?.filter { it.isDirectory } ?: emptyList()
        
        // First try with exact match for current test name
        var testDirs = if (mTestName.isNotEmpty() && mTestName != "unnamed") {
            allDirs.filter { dir -> 
                dir.name.contains("_Monitoring_${mTestName}")
            }.sortedByDescending { it.lastModified() }
        } else {
            emptyList()
        }
        
        // If no matches, try with any monitoring directory
        if (testDirs.isEmpty()) {
            testDirs = allDirs.filter { dir -> 
                dir.name.contains("_Monitoring_")
            }.sortedByDescending { it.lastModified() }
        }
        
        // If still no matches, try legacy format without "Monitoring" tag
        if (testDirs.isEmpty()) {
            testDirs = allDirs.filter { dir -> 
                dir.name.matches(Regex("\\d{4}_\\d{2}_\\d{2}_\\d{2}_\\d{2}_\\d{2}_.*"))
            }.sortedByDescending { it.lastModified() }
        }
        
        return if (testDirs.isNotEmpty()) {
            Log.d(Constants.mainLogTag, "Found monitoring directory: ${testDirs.first().name}")
            testDirs.first()
        } else {
            Log.e(Constants.mainLogTag, "Could not find any monitoring directory")
            null
        }
    }

    private fun generateGraphs(view: View) {
        // Find the most recent monitoring directory
        val monitoringDir = findMonitoringDirectory()
        
        if (monitoringDir == null) {
            Log.d(Constants.mainLogTag, "No monitoring directory found")
            Toast.makeText(this, "No monitoring data found.", Toast.LENGTH_SHORT).show()
            return
        }
        
        // Update the outputDir to point to the found directory
        outputDir = monitoringDir.absolutePath
        Log.d(Constants.mainLogTag, "Using monitoring directory: $outputDir")
        
        // Get the current sample rate (or the first one if multiple)
        val currentRate = mSampleRates.split(",")[0].trim()
        
        // Check if there's a rate subdirectory
        val rateDir = File(outputDir, currentRate)
        val useRateSubdir = rateDir.exists() && rateDir.isDirectory
        
        Log.d(Constants.mainLogTag, "Rate directory exists: $useRateSubdir, path: ${rateDir.absolutePath}")
        
        // Define file paths based on whether rate subdirectory exists
        val sensorDataPaths = listOf(Constants.AccelerometerOutputName, Constants.GyroscopeOutputName, Constants.MagnetometerOutputName).map { it + ".csv" }
        val sensorDataFiles = if (useRateSubdir) {
            sensorDataPaths.map { File(rateDir, it) }
        } else {
            sensorDataPaths.map { File(outputDir, it) }
        }

        // Check if files exist and log their paths
        for (file in sensorDataFiles) {
            Log.d(Constants.mainLogTag, "Checking file: ${file.absolutePath}, exists: ${file.exists()}")
        }

        if (!sensorDataFiles[0].exists()) {
            // If files not found with current rate, try to find any rate directory
            val allRateDirs = monitoringDir.listFiles()?.filter { it.isDirectory } ?: emptyList()
            if (allRateDirs.isNotEmpty()) {
                Log.d(Constants.mainLogTag, "Trying alternative rate directories...")
                for (altRateDir in allRateDirs) {
                    val altFiles = sensorDataPaths.map { File(altRateDir, it) }
                    if (altFiles[0].exists()) {
                        Log.d(Constants.mainLogTag, "Found files in rate directory: ${altRateDir.name}")
                        plotGraphsWithFiles(altFiles)
                        return
                    }
                }
            }
            
            Log.d(Constants.mainLogTag, "File Not Created: ${sensorDataFiles[0]}")
            Toast.makeText(this, "No recording file to plot.", Toast.LENGTH_SHORT).show();
            return
        }

        // clean the sensor data
        val cleanedSensorDataFiles = SensorDataUtils.cleanSensorData(sensorDataFiles)
        
        plotGraphsWithFiles(cleanedSensorDataFiles, cleanedFlag = true)
    }
    
    private fun plotGraphsWithFiles(sensorDataFiles: List<File>, cleanedFlag: Boolean = false) {
        Log.d(Constants.mainLogTag, "File Existing: ${sensorDataFiles[0]}")

        val accelerometerGraphView = binding.accelGraph
        val gyroscopeGraphView = binding.gyroGraph
        val magnetometerGraphView = binding.magGraph
        val graphViews = listOf(accelerometerGraphView, gyroscopeGraphView, magnetometerGraphView)

        for (graphView in graphViews) {
            graphView.viewport.isXAxisBoundsManual = true
            graphView.viewport.isYAxisBoundsManual = true
            graphView.viewport.setMinX(0.0)
            graphView.viewport.isScrollable = true
            graphView.viewport.isScalable = true

            val gridLabel = graphView.gridLabelRenderer
            gridLabel.horizontalAxisTitle = "Times (s)"
            gridLabel.verticalAxisTitle = "Inst. Sample Rate (Hz)"
            gridLabel.numHorizontalLabels = 5
        }
        accelerometerGraphView.title = "Accl"
        gyroscopeGraphView.title = "Gyro"
        magnetometerGraphView.title = "Mag"

        plotSensorData(accelerometerGraphView, sensorDataFiles[0].toString(), cleanedFlag)
        plotSensorData(gyroscopeGraphView, sensorDataFiles[1].toString(), cleanedFlag)
        plotSensorData(magnetometerGraphView, sensorDataFiles[2].toString(), cleanedFlag)
    }

    private fun plotSensorData(graphView: GraphView, inertialFile: String, cleanedFlag: Boolean) {
        try {
            val lineGraphSeries: LineGraphSeries<DataPoint> = LineGraphSeries()
            val csvReader = CSVReader(FileReader(inertialFile))

            graphView.removeAllSeries()

            val mEntries = csvReader.readAll()
            if (mEntries.size <= 2) {
                return
            }
            var x: Double = 0.0
            var maxY: Double = 0.0
            var minY: Double = 1e9
            if (!cleanedFlag) {
                val initTime: Double = mEntries.get(1)[0].toDouble()
                for (i in 2 until mEntries.size) {
                    val curTime: Double = mEntries[i][0].toDouble()
                    val preTime: Double = mEntries[i - 1][0].toDouble()
                    x = (curTime - initTime) / Math.pow(10.0, 9.0)
                    val y = Math.pow(10.0, 9.0) / (curTime - preTime)
                    lineGraphSeries.appendData(DataPoint(x, y), true, 1_000_000)
                    maxY = maxOf(maxY, y)
                    minY = minOf(minY, y)
                }
            } else {
                // for cleaned data, the first column is the frequency, the second column is the time
                // skip the header of mEntries
                val initTime: Double = mEntries.get(1)[1].toDouble()
                for (i in 1 until mEntries.size) {
                    x = (mEntries[i][1].toDouble() - initTime) / Math.pow(10.0, 9.0)
                    val y = mEntries[i][0].toDouble()
                    lineGraphSeries.appendData(DataPoint(x, y), true, 1_000_000)
                    maxY = maxOf(maxY, y)
                    minY = minOf(minY, y)
                }
            }

            if (maxY - minY < 5) {
                graphView.viewport.setMinY(minY - 0.5)
                graphView.viewport.setMaxY(maxY + 0.5)
            } else {
                graphView.viewport.setMinY(minY * 0.9)
                graphView.viewport.setMaxY(maxY * 1.1)
            }

            graphView.viewport.setMaxX(x + .5)
            graphView.addSeries(lineGraphSeries)
        } catch (e: Exception) {
            Log.e(Constants.mainLogTag, "Error plotting sensor data: ${e.message}", e)
        }
    }

    private fun checkAndUpdateMSampleRates(): Boolean {
        if (mSampleRates.isBlank()) {
            mSampleRates = "1"
            Log.d(Constants.mainLogTag, "Using default sample rate: 1")
        }

        var mSampleRatesList = mSampleRates.split(',').toMutableList() // could test more sampling rate together

        // check the inputs
        mSampleRatesList.forEachIndexed{ index, mSampleRate ->
            mSampleRatesList[index] = mSampleRate.trim()
            if(!mSampleRatesList[index].isDigitsOnly()) {
                Toast.makeText(this, "Invalid sample rate. Please use comma-separated numbers.", Toast.LENGTH_LONG).show()
                return false;
            }
        }

        // update
        mSampleRates = mSampleRatesList.joinToString(",")
        return true;
    }

    // Check Permission
    protected fun checkPermission() {
        Log.d(Constants.mainLogTag, "Checking Permissions")

        // check all required dynamic permissions
        val missingPermissions = REQUIRED_SDK_PERMISSIONS.filter {
                permission -> ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED
        }

        if (!missingPermissions.isEmpty()) {
            // request
            ActivityCompat.requestPermissions(this,
                missingPermissions.toTypedArray(), REQUESt_CODE_ASK_PERMISSIONS
            )
            Log.d(Constants.mainLogTag, "Missing Permissions")
            missingPermissions.forEach{ Log.d(Constants.mainLogTag, "Missing Permissions: " + it) }
        } else {
            Log.d(Constants.mainLogTag, "Permissions All Set!")
            onRequestPermissionsResult(REQUESt_CODE_ASK_PERMISSIONS, REQUIRED_SDK_PERMISSIONS.toTypedArray(), IntArray(
                REQUIRED_SDK_PERMISSIONS.size){ PackageManager.PERMISSION_GRANTED})
        }
        Log.d(Constants.mainLogTag, "Checking Done")
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        when (requestCode) {
            REQUESt_CODE_ASK_PERMISSIONS -> {
                val deniedPermissions = mutableListOf<String>()
                
                for (i in permissions.indices) {
                    if (grantResults[i] != PackageManager.PERMISSION_GRANTED) {
                        // If WRITE_EXTERNAL_STORAGE is denied on Android 10+, it's not a problem
                        if (permissions[i] == android.Manifest.permission.WRITE_EXTERNAL_STORAGE && 
                            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            Log.d(Constants.mainLogTag, "WRITE_EXTERNAL_STORAGE denied but not needed on Android 10+")
                            continue
                        }
                        
                        deniedPermissions.add(permissions[i])
                    }
                }
                
                if (deniedPermissions.isNotEmpty()) {
                    // Only show a warning for permissions that are actually needed
                    Toast.makeText(
                        this, 
                        "Required Permission(s) not granted: ${deniedPermissions.joinToString()}", 
                        Toast.LENGTH_SHORT
                    ).show()
                    
                    // Log the denied permissions
                    Log.w(Constants.mainLogTag, "Denied permissions: ${deniedPermissions.joinToString()}")
                }
            }
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        
        // Make sure to stop the service when activity is destroyed
        if (isServiceRunning) {
            stopRecordingService()
        }
    }
} 