package com.spqr.armour

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Environment
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.jjoe64.graphview.GraphView
import com.jjoe64.graphview.series.DataPoint
import com.jjoe64.graphview.series.LineGraphSeries
import com.opencsv.CSVReader
import com.spqr.armour.utils.GraphDisplayManager
import com.spqr.armour.utils.KeyboardUtils
import java.io.File
import java.io.FileReader
import android.util.Log
import com.spqr.armour.utils.SensorDataUtils
import java.io.FileNotFoundException
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt
import java.text.SimpleDateFormat
import java.util.*

class ProfilingResultsActivity : AppCompatActivity() {
    
    // UI elements
    private lateinit var summaryTextView: TextView
    private lateinit var rateChipGroup: ChipGroup
    
    // Minimum rate summary elements
    private lateinit var accelerometerMinRateLabel: TextView
    private lateinit var gyroscopeMinRateLabel: TextView
    private lateinit var magnetometerMinRateLabel: TextView
    private lateinit var saveGlobalSettingsButton: Button
    
    // Accelerometer elements
    private lateinit var accelerometerRequestedRateLabel: TextView
    private lateinit var accelerometerActualRateLabel: TextView
    private lateinit var accelerometerMinMaxLabel: TextView
    private lateinit var accelerometerGraph: GraphView
    
    // Gyroscope elements
    private lateinit var gyroscopeRequestedRateLabel: TextView
    private lateinit var gyroscopeActualRateLabel: TextView
    private lateinit var gyroscopeMinMaxLabel: TextView
    private lateinit var gyroscopeGraph: GraphView
    
    // Magnetometer elements
    private lateinit var magnetometerRequestedRateLabel: TextView
    private lateinit var magnetometerActualRateLabel: TextView
    private lateinit var magnetometerMinMaxLabel: TextView
    private lateinit var magnetometerGraph: GraphView
    
    // Config manager
    private lateinit var sensorConfigManager: SensorConfigManager
    
    private val sensorTypes = arrayOf(
        Constants.AccelerometerOutputName,
        Constants.GyroscopeOutputName,
        Constants.MagnetometerOutputName
    )
    
    private var testName: String = ""
    private var sampleRates: List<String> = emptyList()
    private var currentSampleRate: String = ""
    
    // Store calculated statistics for each sensor and rate
    private val sensorRateStats = mutableMapOf<String, MutableMap<String, SensorRateStats>>()
    
    // Store minimum supported rates for each sensor
    private val minSupportedRates = mutableMapOf<String, Double>()
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(Constants.mainLogTag, "ProfilingResultsActivity: onCreate")
        
        try {
            setContentView(R.layout.activity_profiling_results)
            
            // Initialize sensor config manager
            sensorConfigManager = SensorConfigManager.getInstance(this)
            
            // Get data from intent
            testName = intent.getStringExtra("testName") ?: "unnamed"
            val sampleRatesStr = intent.getStringExtra("sampleRates") ?: ""
            sampleRates = sampleRatesStr.split(",").map { it.trim() }
            
            // Set activity title
            title = "Profiling Results: $testName"
            
            Log.d(Constants.mainLogTag, "ProfilingResultsActivity: Received testName=$testName, sampleRates=$sampleRatesStr")
            
            // Initialize views
            initializeViews()
            
            // Hide the save button since we'll show an automatic dialog
            saveGlobalSettingsButton.visibility = View.GONE
            
            // Get the external files directory
            val externalFilesDir = getExternalFilesDir(Environment.getDataDirectory().absolutePath)?.absolutePath ?: ""
            
            // Find the test directory
            val testDirectory = MinSampRateFinder.findTestDirectory(externalFilesDir, testName)
            
            if (testDirectory != null) {
                // Load and process data
                loadAllProfilingData()
                
                // Calculate minimum supported rates using MinSampRateFinder
                minSupportedRates.putAll(MinSampRateFinder.calculateMinSupportedRates(testDirectory))
                
                // Update minimum rate summary
                updateMinimumRatesSummary()
                
                // Setup rate chips (must be after loading data)
                setupRateChips()
                
                // Show automatic dialog to update global settings
                showAutoUpdateGlobalSettingsDialog()
                
                // Initial display will be handled by setupRateChips selecting the first rate
            } else {
                val message = "Error: Could not find data directory for test '$testName'"
                Log.e(Constants.mainLogTag, message)
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e(Constants.mainLogTag, "Error in ProfilingResultsActivity.onCreate", e)
            Toast.makeText(this, "Error initializing profiling results: ${e.message}", Toast.LENGTH_LONG).show()
            finish() // Return to previous activity if there's a critical error
        }
    }
    
    private fun initializeViews() {
        try {
            summaryTextView = findViewById(R.id.summaryTextView)
            rateChipGroup = findViewById(R.id.rateChipGroup)
            
            // Initialize TextViews for minimum rate summary
            accelerometerMinRateLabel = findViewById(R.id.accelerometerMinRateLabel)
            gyroscopeMinRateLabel = findViewById(R.id.gyroscopeMinRateLabel)
            magnetometerMinRateLabel = findViewById(R.id.magnetometerMinRateLabel)
            
            // Initialize save button
            saveGlobalSettingsButton = findViewById(R.id.saveGlobalSettingsButton)
            
            // Initialize Accelerometer views
            accelerometerRequestedRateLabel = findViewById(R.id.accelerometerRequestedRateLabel)
            accelerometerActualRateLabel = findViewById(R.id.accelerometerActualRateLabel)
            accelerometerMinMaxLabel = findViewById(R.id.accelerometerMinMaxLabel)
            accelerometerGraph = findViewById(R.id.accelerometerGraph)
            
            // Initialize Gyroscope views
            gyroscopeRequestedRateLabel = findViewById(R.id.gyroscopeRequestedRateLabel)
            gyroscopeActualRateLabel = findViewById(R.id.gyroscopeActualRateLabel)
            gyroscopeMinMaxLabel = findViewById(R.id.gyroscopeMinMaxLabel)
            gyroscopeGraph = findViewById(R.id.gyroscopeGraph)
            
            // Initialize Magnetometer views
            magnetometerRequestedRateLabel = findViewById(R.id.magnetometerRequestedRateLabel)
            magnetometerActualRateLabel = findViewById(R.id.magnetometerActualRateLabel)
            magnetometerMinMaxLabel = findViewById(R.id.magnetometerMinMaxLabel)
            magnetometerGraph = findViewById(R.id.magnetometerGraph)
            
            // Configure all graphs using the GraphDisplayManager
            val graphs = listOf(accelerometerGraph, gyroscopeGraph, magnetometerGraph)
            
            graphs.forEach { graphView ->
                GraphDisplayManager.setupGraphBasics(graphView, null, false)
            }
            
            Log.d(Constants.mainLogTag, "ProfilingResultsActivity: Views initialized successfully")
        } catch (e: Exception) {
            Log.e(Constants.mainLogTag, "Error initializing views", e)
            throw e
        }
    }
    
    private fun setupRateChips() {
        rateChipGroup.removeAllViews()
        
        // Get the actual rates we found data for (across all sensor types)
        val availableRates = sensorRateStats.values
            .flatMap { it.keys }
            .distinct()
            .sortedBy { 
                // Sort rates numerically (with special handling for "00", "01", etc.)
                when {
                    it == "00" -> -2
                    it == "01" -> -1
                    it == "02" -> 0
                    it == "03" -> 1
                    it.startsWith("0") && it.length > 1 -> it.substring(1).toIntOrNull() ?: 0
                    else -> it.toIntOrNull() ?: 0
                }
            }
        
        Log.d(Constants.mainLogTag, "Setting up rate chips for available rates: $availableRates")
        
        if (availableRates.isEmpty()) {
            Log.w(Constants.mainLogTag, "No rate data available to display")
            summaryTextView.text = "No sensor data found for this test"
            return
        }
        
        availableRates.forEach { rate ->
            val chip = Chip(this).apply {
                text = formatRateDisplay(rate)
                isCheckable = true
                setOnClickListener {
                    KeyboardUtils.hideKeyboard(this@ProfilingResultsActivity)
                    currentSampleRate = rate
                    Log.d(Constants.mainLogTag, "Rate chip selected: $rate")
                    updateDisplayForAllSensors(rate)
                }
            }
            rateChipGroup.addView(chip)
        }
        
        // Select first chip by default
        if (availableRates.isNotEmpty()) {
            (rateChipGroup.getChildAt(0) as? Chip)?.isChecked = true
            currentSampleRate = availableRates[0]
            Log.d(Constants.mainLogTag, "Default rate selected: $currentSampleRate")
            updateDisplayForAllSensors(currentSampleRate)
        } else {
            Log.w(Constants.mainLogTag, "No sample rates available to display")
        }
    }
    
    private fun formatRateDisplay(rate: String): String {
        return when (rate) {
            "00" -> "DELAY_FASTEST"
            "01" -> "DELAY_GAME"
            "02" -> "DELAY_UI"
            "03" -> "DELAY_NORMAL"
            else -> {
                if (rate.startsWith("0") && rate.length > 1) {
                    rate.substring(1).toInt().toString() + " Hz"
                } else {
                    "$rate Hz"
                }
            }
        }
    }
    
    private fun loadAllProfilingData() {
        // Get the external files directory
        val externalFilesDir = getExternalFilesDir(Environment.getDataDirectory().absolutePath)?.absolutePath ?: ""
        Log.d(Constants.mainLogTag, "External files dir: $externalFilesDir")
        
        // Find all directories that match the test name pattern (either exact or with timestamp suffix)
        val allDirs = File(externalFilesDir).listFiles()?.filter { it.isDirectory } ?: emptyList()
        
        // First try to find directories with the new timestamp pattern: YYYY_MM_DD_HH_mm_ss_Profiling_testName
        var testDirs = allDirs.filter { dir -> 
            dir.name.matches(Regex("\\d{4}_\\d{2}_\\d{2}_\\d{2}_\\d{2}_\\d{2}_Profiling_${testName}"))
        }.sortedByDescending { it.lastModified() } // Most recent first
        
        // If no matches with the new format, try the legacy format: YYYY_MM_DD_HH_mm_ss_testName
        if (testDirs.isEmpty()) {
            testDirs = allDirs.filter { dir -> 
                dir.name.matches(Regex("\\d{4}_\\d{2}_\\d{2}_\\d{2}_\\d{2}_\\d{2}_${testName}"))
            }.sortedByDescending { it.lastModified() }
            
            if (testDirs.isNotEmpty()) {
                Log.d(Constants.mainLogTag, "Found legacy format test directories")
            }
        }
        
        if (testDirs.isNotEmpty()) {
            // Use the most recent directory that matches the pattern
            val mostRecentDir = testDirs.first()
            Log.d(Constants.mainLogTag, "Found most recent test directory: ${mostRecentDir.absolutePath}")
            loadFromDirectory(mostRecentDir)
            return
        }
        
        // If no timestamp directories found with testName, try any directory with "Profiling" in the name
        val profilingDirs = allDirs.filter { dir -> 
            dir.name.contains("Profiling", ignoreCase = true)
        }.sortedByDescending { it.lastModified() }
        
        if (profilingDirs.isNotEmpty()) {
            val mostRecentProfilingDir = profilingDirs.first()
            Log.d(Constants.mainLogTag, "Using most recent Profiling directory: ${mostRecentProfilingDir.absolutePath}")
            loadFromDirectory(mostRecentProfilingDir)
            return
        }
        
        // If no timestamp directories found, try the exact test name
        val exactTestDir = File(externalFilesDir, testName)
        if (exactTestDir.exists()) {
            Log.d(Constants.mainLogTag, "Found exact test directory: ${exactTestDir.absolutePath}")
            loadFromDirectory(exactTestDir)
            return
        }
        
        // Fallback to unnamed directories with timestamp
        val unnamedDirs = allDirs.filter { dir -> 
            dir.name.matches(Regex("\\d{4}_\\d{2}_\\d{2}_\\d{2}_\\d{2}_\\d{2}_Profiling_unnamed")) || 
            dir.name.matches(Regex("\\d{4}_\\d{2}_\\d{2}_\\d{2}_\\d{2}_\\d{2}_unnamed"))
        }.sortedByDescending { it.lastModified() }
        
        if (unnamedDirs.isNotEmpty()) {
            val mostRecentUnnamedDir = unnamedDirs.first()
            Log.d(Constants.mainLogTag, "Using most recent unnamed directory: ${mostRecentUnnamedDir.absolutePath}")
            loadFromDirectory(mostRecentUnnamedDir)
            return
        }
        
        // Final fallback to plain unnamed directory
        val unnamedDir = File(externalFilesDir, "unnamed")
        if (unnamedDir.exists()) {
            Log.d(Constants.mainLogTag, "Using fallback unnamed directory: ${unnamedDir.absolutePath}")
            loadFromDirectory(unnamedDir)
            return
        }
        
        // If we get here, we couldn't find the directory
        val message = "Error: Could not find data directory for test '$testName'"
        Log.e(Constants.mainLogTag, message)
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
    
    private fun loadFromDirectory(baseDirectory: File) {
        Log.d(Constants.mainLogTag, "Loading from directory: ${baseDirectory.absolutePath}")
        
        // Check if the directory contains rate subdirectories (00, 01, 02, etc.)
        val rateDirectories = baseDirectory.listFiles()?.filter { it.isDirectory } ?: emptyList()
        Log.d(Constants.mainLogTag, "Found ${rateDirectories.size} rate directories: ${rateDirectories.map { it.name }}")
        
        if (rateDirectories.isEmpty()) {
            // Maybe the sensor files are directly in this directory?
            val sensorFiles = sensorTypes.map { sensorType -> 
                File(baseDirectory, "$sensorType.csv") 
            }.filter { it.exists() }
            
            if (sensorFiles.isNotEmpty()) {
                Log.d(Constants.mainLogTag, "Found sensor files directly in base directory")
                // Initialize the data structure
                sensorTypes.forEach { sensorType ->
                    sensorRateStats[sensorType] = mutableMapOf()
                }
                
                // Try to determine the actual requested rate from the sample rates string
                // This fixes the bug where the requested rate is always shown as "1 Hz"
                val actualRequestedRate = extractSingleRateFromSampleRates()
                Log.d(Constants.mainLogTag, "Extracted actual requested rate: $actualRequestedRate")
                
                // Process each sensor file
                sensorTypes.forEach { sensorType ->
                    val file = File(baseDirectory, "$sensorType.csv")
                    val cleanedFile = SensorDataUtils.cleanSensorData(listOf(file))[0]
                    if (file.exists()) {
                        try {
                            val stats = calculateSensorStats(cleanedFile, cleanedFlag = true)
                            sensorRateStats[sensorType]?.put(actualRequestedRate, stats)
                            Log.d(Constants.mainLogTag, "Processed $sensorType.csv with avg rate: ${stats.avgRate}")
                        } catch (e: Exception) {
                            Log.e(Constants.mainLogTag, "Error processing file: ${file.absolutePath}", e)
                        }
                    }
                }
                
                updateSummaryText()
                return
            }
            
            Toast.makeText(this, "No rate directories or sensor files found", Toast.LENGTH_SHORT).show()
            return
        }
        
        // Initialize the data structure and load data from rate directories
        sensorTypes.forEach { sensorType ->
            sensorRateStats[sensorType] = mutableMapOf()
        }
        
        // Process each rate directory
        rateDirectories.forEach { rateDir ->
            val rate = rateDir.name
            Log.d(Constants.mainLogTag, "Processing rate directory: $rate")
            
            // Process each sensor type
            sensorTypes.forEach { sensorType ->
                val file = File(rateDir, "$sensorType.csv")
                val cleanedFile = SensorDataUtils.cleanSensorData(listOf(file))[0]
                
                if (cleanedFile.exists()) {
                    try {
                        val stats = calculateSensorStats(cleanedFile, cleanedFlag = true)
                        sensorRateStats[sensorType]?.put(rate, stats)
                        Log.d(Constants.mainLogTag, "Processed $sensorType.csv for rate $rate with avg rate: ${stats.avgRate}")
                    } catch (e: Exception) {
                        Log.e(Constants.mainLogTag, "Error processing file: ${file.absolutePath}", e)
                    }
                } else {
                    Log.d(Constants.mainLogTag, "File does not exist: ${file.absolutePath}")
                }
            }
        }
        
        updateSummaryText()
    }
    
    /**
     * Extract the actual requested rate from the sample rates string when there's only one rate
     */
    private fun extractSingleRateFromSampleRates(): String {
        try {
            // Check if there's only one rate in the list
            if (sampleRates.size == 1) {
                val singleRate = sampleRates[0]
                Log.d(Constants.mainLogTag, "Found single rate in sample rates list: $singleRate")
                return singleRate
            }
        } catch (e: Exception) {
            Log.e(Constants.mainLogTag, "Error extracting single rate", e)
        }
        
        // If we can't determine the rate, use "1" as a fallback
        Log.d(Constants.mainLogTag, "Couldn't determine single rate, using default '1'")
        return "1"
    }
    
    private fun calculateSensorStats(file: File, cleanedFlag: Boolean): SensorRateStats {
        try {
            val csvReader = CSVReader(FileReader(file))
            val entries = csvReader.readAll()
            csvReader.close()
            
            Log.d(Constants.mainLogTag, "Processing file: ${file.name} with ${entries.size} entries")

            if ((!cleanedFlag && entries.size <= 3) || (cleanedFlag && entries.size <= 2)) {
                Log.w(Constants.mainLogTag, "Not enough data in file: ${file.path}")
                return SensorRateStats(0.0, 0.0, 0.0, 0.0, emptyList())
            }
            
            val rates = mutableListOf<Pair<Double, Double>>() // time, rate
            var totalRate = 0.0
            var minRate = Double.MAX_VALUE
            var maxRate = Double.MIN_VALUE
            
            // Get initial timestamp for reference
            // cleaned file has two columns: frequency and timestamp
            val timeIndex = if (cleanedFlag) 1 else 0
            val initTime = try {
                entries[1][timeIndex].toDouble()
            } catch (e: Exception) {
                Log.e(Constants.mainLogTag, "Error parsing initial timestamp in ${file.name}: ${e.message}")
                return SensorRateStats(0.0, 0.0, 0.0, 0.0, emptyList())
            }
            
            Log.d(Constants.mainLogTag, "Initial timestamp in ${file.name}: $initTime")
            
            // Process each row to calculate instantaneous sampling rates
            for (i in 1 until entries.size) {
                try {
                    val curTime = entries[i][timeIndex].toDouble()
                    
                    // Calculate time in seconds and instantaneous rate
                    val timeInSec = (curTime - initTime) / 1.0e9
                    var rate = 0.0
                    if (!cleanedFlag) {
                        if (i == 1) continue // Skip the first row for non-cleaned data
                        val preTime = entries[i - 1][timeIndex].toDouble()
                        val timeDiff = curTime - preTime
                        
                        // Make sure we don't divide by zero or a very small number
                        if (timeDiff <= 0 || timeDiff < 1.0e-6) {
                            Log.w(Constants.mainLogTag, "Invalid time difference in ${file.name} at row $i: $timeDiff")
                            continue
                        }
                        
                        rate = 1.0e9 / timeDiff
                    } else {
                        rate = entries[i][0].toDouble()
                    }
                    
                    // Sanity check for extreme values
                    if (rate <= 0 || rate > 1000) {
                        Log.w(Constants.mainLogTag, "Extreme rate value in ${file.name} at row $i: $rate Hz")
                        continue
                    }
                    
                    rates.add(Pair(timeInSec, rate))
                    totalRate += rate
                    if (rate < minRate) minRate = rate
                    if (rate > maxRate) maxRate = rate
                    
                    if (i == 3 || i == entries.size - 1 || i % 10 == 0) {
                        Log.d(Constants.mainLogTag, "Rate at row $i in ${file.name}: $rate Hz (time: $timeInSec s)")
                    }
                } catch (e: Exception) {
                    Log.e(Constants.mainLogTag, "Error processing row $i in ${file.name}: ${e.message}")
                }
            }
            
            // Make sure we have valid data
            if (rates.isEmpty()) {
                Log.w(Constants.mainLogTag, "No valid rate data calculated for ${file.name}")
                return SensorRateStats(0.0, 0.0, 0.0, 0.0, emptyList())
            }
            
            // Check if min/max has not been updated (meaning all rates are invalid)
            if (minRate == Double.MAX_VALUE || maxRate == Double.MIN_VALUE) {
                minRate = 0.0
                maxRate = 0.0
            }
            
            val avgRate = totalRate / rates.size
            
            // Calculate standard deviation
            var sumSquaredDiff = 0.0
            rates.forEach { (_, rate) ->
                sumSquaredDiff += (rate - avgRate).pow(2)
            }
            val stdDev = sqrt(sumSquaredDiff / rates.size)
            
            Log.d(Constants.mainLogTag, "Stats for ${file.name}: " +
                    "avg=${avgRate}, min=${minRate}, max=${maxRate}, std=${stdDev}, points=${rates.size}")
            
            return SensorRateStats(avgRate, stdDev, minRate, maxRate, rates)
        } catch (e: FileNotFoundException) {
            Log.e(Constants.mainLogTag, "File not found: ${file.path}", e)
            throw e
        } catch (e: Exception) {
            Log.e(Constants.mainLogTag, "Error calculating stats for file: ${file.path}", e)
            throw e
        }
    }
    
    private fun updateSummaryText() {
        // Update the summary text to show information about available rates
        val availableRates = sensorRateStats.values
            .flatMap { it.keys }
            .distinct()
            .sortedBy { 
                when {
                    it == "00" -> -2
                    it == "01" -> -1
                    it == "02" -> 0
                    it == "03" -> 1
                    it.startsWith("0") && it.length > 1 -> it.substring(1).toIntOrNull() ?: 0
                    else -> it.toIntOrNull() ?: 0
                }
            }
        
        if (availableRates.isEmpty()) {
            summaryTextView.text = "Sample Rate Selection - No data available"
            return
        }
        
        summaryTextView.text = "Sample Rate Selection"
        Log.d(Constants.mainLogTag, "Updated summary text")
    }
    
    private fun updateDisplayForAllSensors(rate: String) {
        // Update display for each sensor
        updateDisplayForSensor(Constants.AccelerometerOutputName, rate)
        updateDisplayForSensor(Constants.GyroscopeOutputName, rate)
        updateDisplayForSensor(Constants.MagnetometerOutputName, rate)
    }
    
    private fun updateDisplayForSensor(sensorType: String, rate: String) {
        val stats = sensorRateStats[sensorType]?.get(rate)
        
        // Get the appropriate labels and graph based on sensor type
        val requestedRateLabel: TextView
        val actualRateLabel: TextView
        val minMaxLabel: TextView
        val graph: GraphView
        
        when (sensorType) {
            Constants.AccelerometerOutputName -> {
                requestedRateLabel = accelerometerRequestedRateLabel
                actualRateLabel = accelerometerActualRateLabel
                minMaxLabel = accelerometerMinMaxLabel
                graph = accelerometerGraph
            }
            Constants.GyroscopeOutputName -> {
                requestedRateLabel = gyroscopeRequestedRateLabel
                actualRateLabel = gyroscopeActualRateLabel
                minMaxLabel = gyroscopeMinMaxLabel
                graph = gyroscopeGraph
            }
            Constants.MagnetometerOutputName -> {
                requestedRateLabel = magnetometerRequestedRateLabel
                actualRateLabel = magnetometerActualRateLabel
                minMaxLabel = magnetometerMinMaxLabel
                graph = magnetometerGraph
            }
            else -> return
        }
        
        if (stats == null) {
            val message = "No data available"
            requestedRateLabel.text = "Requested: $message"
            actualRateLabel.text = "Actual (avg): $message"
            minMaxLabel.text = "Range: $message"
            graph.removeAllSeries()
            
            // For Magnetometer, set up a default viewport even if no data
            if (sensorType == Constants.MagnetometerOutputName) {
                graph.viewport.apply {
                    isYAxisBoundsManual = true
                    setMinY(0.0)
                    setMaxY(10.0)
                    setMinX(0.0)
                    setMaxX(5.0)
                }
            }
            
            Log.w(Constants.mainLogTag, "No data available for $sensorType at rate: $rate")
            return
        }
        
        // Add special handling for Magnetometer with minimal data
        val statsToUse = if (sensorType == Constants.MagnetometerOutputName && 
                           (stats.rateData.isEmpty() || stats.minRate == stats.maxRate)) {
            // Create a modified version with dummy data points if needed
            var modifiedStats = stats
            
            if (stats.rateData.isEmpty()) {
                // Create dummy data points around the requested rate
                val requestedRateValue = when (rate) {
                    "00" -> 200.0
                    "01" -> 100.0
                    "02" -> 60.0
                    "03" -> 20.0
                    else -> {
                        if (rate.startsWith("0") && rate.length > 1) {
                            rate.substring(1).toDoubleOrNull() ?: 5.0
                        } else {
                            rate.toDoubleOrNull() ?: 5.0
                        }
                    }
                }
                
                // Create a series of dummy points
                val dummyPoints = listOf(
                    Pair(0.0, requestedRateValue),
                    Pair(1.0, requestedRateValue),
                    Pair(2.0, requestedRateValue)
                )
                
                modifiedStats = SensorRateStats(
                    avgRate = requestedRateValue,
                    stdDev = 0.0,
                    minRate = requestedRateValue,
                    maxRate = requestedRateValue,
                    rateData = dummyPoints
                )
                
                Log.d(Constants.mainLogTag, "Created dummy data for Magnetometer: ${dummyPoints.size} points")
            }
            
            modifiedStats
        } else {
            stats
        }
        
        // Update stats card
        val requestedRateStr = when (rate) {
            "00" -> "DELAY_FASTEST"
            "01" -> "DELAY_GAME"
            "02" -> "DELAY_UI"
            "03" -> "DELAY_NORMAL"
            else -> {
                if (rate.startsWith("0") && rate.length > 1) {
                    rate.substring(1).toInt().toString() + " Hz"
                } else {
                    "$rate Hz"
                }
            }
        }
        
        requestedRateLabel.text = "Requested: $requestedRateStr"
        actualRateLabel.text = "Actual (avg): ${String.format("%.1f", statsToUse.avgRate)} Hz"
        minMaxLabel.text = "Range: ${String.format("%.1f", statsToUse.minRate)} Hz - ${String.format("%.1f", statsToUse.maxRate)} Hz"
        
        Log.d(Constants.mainLogTag, "Display updated for $sensorType at rate $rate: avg=${statsToUse.avgRate}, range=${statsToUse.minRate} - ${statsToUse.maxRate}")
        
        // Update graph
        updateSensorGraph(graph, statsToUse, sensorType)
    }
    
    private fun updateSensorGraph(graphView: GraphView, stats: SensorRateStats, sensorType: String) {
        if (stats.rateData.isEmpty()) {
            Log.w(Constants.mainLogTag, "No data points available for graph")
            
            // Even with no data, set a default viewport to make the graph visible
            graphView.viewport.apply {
                isYAxisBoundsManual = true
                setMinY(0.0)
                setMaxY(10.0)  // Default max value
                setMinX(0.0)
                setMaxX(5.0)   // Default X range
            }
            return
        }
        
        try {
            // Use GraphDisplayManager to plot the data
            GraphDisplayManager.plotSensorData(
                context = this,
                graphView = graphView,
                dataPoints = stats.rateData,
                sensorType = sensorType,
                addReferenceLine = false,
                referenceValue = null
            )
            
            // If there's only one data point or all points have the same value,
            // the graph might appear empty due to no visible range
            if (stats.minRate == stats.maxRate) {
                Log.d(Constants.mainLogTag, "All data points have the same value: ${stats.minRate}. Setting manual range.")
                graphView.viewport.apply {
                    isYAxisBoundsManual = true
                    val padding = max(0.1, stats.minRate * 0.1) // At least 0.1 or 10% padding
                    setMinY(max(0.0, stats.minRate - padding))
                    setMaxY(stats.minRate + padding)
                }
            }
            
            Log.d(Constants.mainLogTag, "Graph updated for $sensorType with ${stats.rateData.size} data points")
        } catch (e: Exception) {
            Log.e(Constants.mainLogTag, "Error updating graph", e)
            Toast.makeText(this, "Error displaying graph: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun getColorForSensor(sensorType: String): Int {
        return when (sensorType) {
            Constants.AccelerometerOutputName -> ContextCompat.getColor(this, android.R.color.holo_blue_dark)
            Constants.GyroscopeOutputName -> ContextCompat.getColor(this, android.R.color.holo_green_dark)
            Constants.MagnetometerOutputName -> ContextCompat.getColor(this, android.R.color.holo_red_dark)
            else -> Color.BLACK
        }
    }
    
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            Log.d(Constants.mainLogTag, "Back button pressed, finishing activity")
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
    
    private fun updateMinimumRatesSummary() {
        // Format the min rate labels
        val accelRate = minSupportedRates[Constants.AccelerometerOutputName] ?: 0.0
        val gyroRate = minSupportedRates[Constants.GyroscopeOutputName] ?: 0.0
        val magRate = minSupportedRates[Constants.MagnetometerOutputName] ?: 0.0
        
        // Force refresh the config manager to get the latest values
        sensorConfigManager.refreshValues()
        
        // Get the current global settings (fresh from SharedPreferences)
        val globalAccelRate = sensorConfigManager.getAcceMinRate()
        val globalGyroRate = sensorConfigManager.getGyroMinRate()
        val globalMagRate = sensorConfigManager.getMagnMinRate()
        
        accelerometerMinRateLabel.text = formatMinRateText(Constants.AccelerometerOutputName, accelRate, globalAccelRate)
        gyroscopeMinRateLabel.text = formatMinRateText(Constants.GyroscopeOutputName, gyroRate, globalGyroRate)
        magnetometerMinRateLabel.text = formatMinRateText(Constants.MagnetometerOutputName, magRate, globalMagRate)
        
        Log.d(Constants.mainLogTag, "Updated minimum rate summary with global rates - " +
                "Accel: $globalAccelRate Hz, Gyro: $globalGyroRate Hz, Mag: $globalMagRate Hz")
    }
    
    private fun formatMinRateText(sensorType: String, rate: Double, globalRate: Double): String {
        val displayName = when (sensorType) {
            Constants.AccelerometerOutputName -> "Accelerometer"
            Constants.GyroscopeOutputName -> "Gyroscope"
            Constants.MagnetometerOutputName -> "Magnetometer"
            else -> sensorType
        }
        
        val currentRateText = if (rate > 0) {
            val rateFormatted = String.format("%.1f", rate)
            // If the current rate is significantly better (lower) than global, highlight it
            if (rate < globalRate * 0.9) {
                "$rateFormatted Hz ↓" // Down arrow indicates improvement (lower rate is better)
            } else if (rate > globalRate * 1.1) {
                "$rateFormatted Hz ↑" // Up arrow indicates worse performance
            } else {
                "$rateFormatted Hz"
            }
        } else {
            "No data available"
        }
        
        return "$displayName: $currentRateText (Global: ${String.format("%.1f", globalRate)} Hz)"
    }
    
    /**
     * Show automatic dialog asking whether to update global settings based on profiling results
     * Only shows if the new rates are significantly different from existing global settings
     */
    private fun showAutoUpdateGlobalSettingsDialog() {
        val accelRate = minSupportedRates[Constants.AccelerometerOutputName] ?: 0.0
        val gyroRate = minSupportedRates[Constants.GyroscopeOutputName] ?: 0.0
        val magRate = minSupportedRates[Constants.MagnetometerOutputName] ?: 0.0
        
        // Check if we have valid rates from this profiling session
        if (accelRate <= 0.0 || gyroRate <= 0.0 || magRate <= 0.0) {
            Log.w(Constants.mainLogTag, "Cannot update global settings: Some sensor rates are invalid or missing")
            return
        }
        
        // Get current global settings to show comparison
        val hasExistingRates = sensorConfigManager.hasValidMinimumRates()
        
        // If no existing rates, always show dialog
        if (!hasExistingRates) {
            showUpdateDialog(accelRate, gyroRate, magRate, hasExistingRates, -1.0, -1.0, -1.0)
            return
        }
        
        val globalAccelRate = sensorConfigManager.getAcceMinRate()
        val globalGyroRate = sensorConfigManager.getGyroMinRate()
        val globalMagRate = sensorConfigManager.getMagnMinRate()
        
        // Check if any of the new rates are significantly different from existing ones
        // Use a tolerance of 5% to avoid showing dialog for minor differences
        val tolerance = 0.05
        val accelDifferent = kotlin.math.abs(accelRate - globalAccelRate) > (globalAccelRate * tolerance)
        val gyroDifferent = kotlin.math.abs(gyroRate - globalGyroRate) > (globalGyroRate * tolerance)
        val magDifferent = kotlin.math.abs(magRate - globalMagRate) > (globalMagRate * tolerance)
        
        if (accelDifferent || gyroDifferent || magDifferent) {
            Log.d(Constants.mainLogTag, "Rates have changed significantly, showing update dialog. " +
                    "Accel: $accelRate vs $globalAccelRate (diff: ${accelDifferent}), " +
                    "Gyro: $gyroRate vs $globalGyroRate (diff: ${gyroDifferent}), " +
                    "Mag: $magRate vs $globalMagRate (diff: ${magDifferent})")
            showUpdateDialog(accelRate, gyroRate, magRate, hasExistingRates, globalAccelRate, globalGyroRate, globalMagRate)
        } else {
            Log.d(Constants.mainLogTag, "Rates are similar to existing global settings, skipping update dialog. " +
                    "Accel: $accelRate vs $globalAccelRate, " +
                    "Gyro: $gyroRate vs $globalGyroRate, " +
                    "Mag: $magRate vs $globalMagRate")
        }
    }
    
    /**
     * Show the actual update dialog
     */
    private fun showUpdateDialog(accelRate: Double, gyroRate: Double, magRate: Double, 
                               hasExistingRates: Boolean, globalAccelRate: Double, globalGyroRate: Double, globalMagRate: Double) {
        // Format the message based on whether global settings already exist
        val message = if (hasExistingRates) {
            """
            Profiling completed! Would you like to update the global minimum supported sampling rates?
            
            Current Test Results → Current Global Settings:
            • Accelerometer: ${String.format("%.1f", accelRate)} Hz → ${String.format("%.1f", globalAccelRate)} Hz
            • Gyroscope: ${String.format("%.1f", gyroRate)} Hz → ${String.format("%.1f", globalGyroRate)} Hz
            • Magnetometer: ${String.format("%.1f", magRate)} Hz → ${String.format("%.1f", globalMagRate)} Hz
            
            These values will be used for all future sensor operations and threshold calculations.
            """.trimIndent()
        } else {
            """
            Profiling completed! Your device's minimum supported sampling rates have been determined:
            
            • Accelerometer: ${String.format("%.1f", accelRate)} Hz
            • Gyroscope: ${String.format("%.1f", gyroRate)} Hz
            • Magnetometer: ${String.format("%.1f", magRate)} Hz
            
            Would you like to set these as your global minimum rates? This will enable all monitoring and threshold features.
            """.trimIndent()
        }
        
        AlertDialog.Builder(this)
            .setTitle("Update Global Settings?")
            .setMessage(message)
            .setPositiveButton("Update Settings") { _, _ ->
                saveGlobalSettings(accelRate, gyroRate, magRate)
            }
            .setNegativeButton("Not Now", null)
            .setCancelable(true)
            .show()
    }
    
    /**
     * Save the global minimum sampling rates and update UI
     */
    private fun saveGlobalSettings(accelRate: Double, gyroRate: Double, magRate: Double) {
        try {
            // Save the new values
            sensorConfigManager.saveMinimumSamplingRates(accelRate, gyroRate, magRate)
            
            // Update the minimum rate summary with the new global values
            updateMinimumRatesSummary()
            
            // Show success message
            Toast.makeText(this, "Global minimum sampling rates updated successfully!\nMonitoring and threshold features are now available.", Toast.LENGTH_LONG).show()
            
            Log.d(Constants.mainLogTag, "Updated global minimum sampling rates: Accel=${accelRate}, Gyro=${gyroRate}, Mag=${magRate}")
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to save settings: ${e.message}", Toast.LENGTH_SHORT).show()
            Log.e(Constants.mainLogTag, "Error saving global settings", e)
        }
    }
    
    // Data class to store sensor rate statistics
    data class SensorRateStats(
        val avgRate: Double,
        val stdDev: Double,
        val minRate: Double,
        val maxRate: Double,
        val rateData: List<Pair<Double, Double>> // time, rate
    )
} 