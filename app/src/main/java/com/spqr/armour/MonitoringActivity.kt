package com.spqr.armour

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.text.isDigitsOnly
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.Legend
import com.github.mikephil.charting.components.LimitLine
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.IFillFormatter
import com.github.mikephil.charting.formatter.ValueFormatter
import com.github.mikephil.charting.interfaces.dataprovider.LineDataProvider
import com.github.mikephil.charting.interfaces.datasets.ILineDataSet
import com.opencsv.CSVReader
import com.spqr.armour.databinding.ActivityMonitoringBinding
import com.spqr.armour.utils.FileUtils
import com.spqr.armour.utils.KeyboardUtils
import com.spqr.armour.utils.SensorDataUtils
import java.io.File
import java.io.FileReader
import java.text.SimpleDateFormat
import java.util.*
import kotlin.collections.ArrayList
import androidx.appcompat.app.AlertDialog

class MonitoringActivity : AppCompatActivity() {

    // ui binding
    private lateinit var testNameEditText: EditText
    private lateinit var sampleRateEditText: EditText
    private lateinit var startStopButton: Button
    private lateinit var generateGraphButton: Button
    private lateinit var helpButton: Button
    private lateinit var backgroundMonitoringCheckBox: CheckBox
    private lateinit var yAxisTitle: TextView
    private lateinit var resultMessageBox: TextView
    private lateinit var mpAcceLineChart: LineChart
    private lateinit var mpGyroLineChart: LineChart
    private lateinit var mpMagnLineChart: LineChart
    private lateinit var lineCharts: List<LineChart>
    private lateinit var acceLayout: View
    private lateinit var gyroLayout: View
    private lateinit var magnLayout: View
    private lateinit var resultsSummaryLayout: View
    private lateinit var sensorLayouts: List<View>


    private lateinit var binding: ActivityMonitoringBinding

    private lateinit var sensorConfigManager: SensorConfigManager
    private lateinit var acceThresholdString: String
    private lateinit var gyroThresholdString: String
    private lateinit var magnThresholdString: String
    private lateinit var thresholds: List<Float>
    private val accessedSensors: MutableSet<String> = mutableSetOf()
    private var retestingFlag: Boolean = false

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
        helpButton = binding.btnHelp
        backgroundMonitoringCheckBox = binding.checkboxBackgroundMonitoring
        yAxisTitle = binding.yAxisTitle
        resultMessageBox = binding.resultMessageBox
        mpAcceLineChart = binding.mpAcceChart
        mpGyroLineChart = binding.mpGyroChart
        mpMagnLineChart = binding.mpMagnChart
        acceLayout = binding.acceLayout
        gyroLayout = binding.gyroLayout
        magnLayout = binding.magnLayout
        resultsSummaryLayout = binding.resultsSummaryLayout
        sensorLayouts = listOf(acceLayout, gyroLayout, magnLayout, resultsSummaryLayout)

        sensorConfigManager = SensorConfigManager.getInstance(this)

        checkPermission()
        // Removed createNotificationChannel() - now handled by ArmourService
        initSensorThresholds()

        lineCharts = listOf(mpAcceLineChart, mpGyroLineChart, mpMagnLineChart)
        thresholds = listOf(acceThresholdString.toFloat(), gyroThresholdString.toFloat(), magnThresholdString.toFloat())

        // Set background monitoring checkbox to checked by default
        backgroundMonitoringCheckBox.isChecked = true

        // event binding
        startStopButton.setOnClickListener{ view -> 
            KeyboardUtils.hideKeyboard(this)
            onClickStartStopButton(view) 
        }

        // start/stop service
        generateGraphButton.setOnClickListener{ view -> 
            KeyboardUtils.hideKeyboard(this)
            onGenerateButtonClicked(view) 
        }
        
        // help button
        helpButton.setOnClickListener {
            KeyboardUtils.hideKeyboard(this)
            showHelpDialog()
        }

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
            generateGraphButton.isEnabled = false
            generateGraphButton.alpha = 0.5f  // Gray out when monitoring is running
        } else {
            startStopButton.text = "Start Monitoring"
            generateGraphButton.isEnabled = true
            generateGraphButton.alpha = 1.0f  // Full opacity when monitoring is not running
        }
    }

    fun onClickStartStopButton(view: View) {
        // if service is running, stop it.
        if (isServiceRunning) {
            stopRecordingService()
            // generateGraphButton can not be clicked when service is running
            generateGraphButton.isEnabled = true
            generateGraphButton.alpha = 1.0f  // Restore full opacity
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
        generateGraphButton.isEnabled = false // Disable graph generation while service is running
        generateGraphButton.alpha = 0.5f  // Gray out the button visually
        for (sensorLayout in sensorLayouts) {
            sensorLayout.visibility = View.GONE // Hide sensor layouts while service is running
        }
        
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
        generateGraphButton.text = "Generating Graphs..."
        generateGraphButton.isEnabled = false // Disable button while generating graphs
        for (sensorLayout in sensorLayouts) {
            sensorLayout.visibility = View.GONE // Hide sensor layouts while generating graphs
        }

        mSampleRates = sampleRateEditText.text.toString().trim()
        if (mSampleRates.isEmpty()) {
            mSampleRates = "1"
        }
        
        // Update the test name from the UI
        mTestName = testNameEditText.text.toString().trim()
        if (mTestName.isEmpty()) {
            mTestName = "unnamed"
        }

        val sensorDataFiles: List<File>? = initDataFiles()

        if (sensorDataFiles == null || sensorDataFiles.isEmpty()) {
            Log.d(Constants.mainLogTag, "No sensor data files found")
            Toast.makeText(this, "No recording file to plot.", Toast.LENGTH_SHORT).show()
            return
        }

        initGraphSettings()
        plotSensorDatas(sensorDataFiles, cleanedFlag = true)

        generateGraphButton.text = "Generate Graphs"
        generateGraphButton.isEnabled = true // Re-enable button after generating graphs
        var accessedSensorMessage: String = ""
        if (accessedSensors.isEmpty()) {
            accessedSensorMessage = Constants.NO_SENSOR_ACCESS_NOTE
        } else {
            val accessedSensorNames = accessedSensors.joinToString(",")
            accessedSensorMessage = "Accessed Sensors: $accessedSensorNames"
        }
        accessedSensors.clear()
        if (retestingFlag) {
            accessedSensorMessage += "\nSensor access detected only briefly. Try running it one more time."
            retestingFlag = false
        }
        resultMessageBox.text = accessedSensorMessage



        for (sensorLayout in sensorLayouts) {
            sensorLayout.visibility = View.VISIBLE // Show sensor layouts after generating graphs
        }
    }

    private fun initDataFiles(): List<File>? {
        // Find the most recent monitoring directory
        val monitoringDir = findMonitoringDirectory()
        if (monitoringDir == null) {
            Log.d(Constants.mainLogTag, "No monitoring directory found")
            Toast.makeText(this, "No monitoring data found.", Toast.LENGTH_SHORT).show()
            return null
        }

        outputDir = monitoringDir.absolutePath
        Log.d(Constants.mainLogTag, "Using monitoring directory: $outputDir")

        val currentRate = mSampleRates.split(",").firstOrNull()?.trim().orEmpty()
        val sensorDataNames = listOf(
            Constants.AccelerometerOutputName,
            Constants.GyroscopeOutputName,
            Constants.MagnetometerOutputName
        ).map { "$it.csv" }

        // Try current rate subdirectory first
        val rateDir = File(outputDir, currentRate)
        val sensorDataFiles = if (rateDir.exists() && rateDir.isDirectory) {
            sensorDataNames.map { File(rateDir, it) }
        } else {
            sensorDataNames.map { File(outputDir, it) }
        }

        // If files not found, try all rate subdirectories
        if (!sensorDataFiles[0].exists()) {
            val allRateDirs = monitoringDir.listFiles()?.filter { it.isDirectory } ?: emptyList()
            allRateDirs.forEach { altRateDir ->
                val altFiles = sensorDataNames.map { File(altRateDir, it) }
                if (altFiles[0].exists()) {
                    Log.d(Constants.mainLogTag, "Found files in rate directory: ${altRateDir.name}")
                    return SensorDataUtils.cleanSensorData(altFiles)
                }
            }
            Log.d(Constants.mainLogTag, "File Not Created: ${sensorDataFiles[0]}")
            Toast.makeText(this, "No recording file to plot.", Toast.LENGTH_SHORT).show()
            return null
        }

        sensorDataFiles.forEach {
            Log.d(Constants.mainLogTag, "Checking file: ${it.absolutePath}, exists: ${it.exists()}")
        }

        // Clean and return sensor data files
        return SensorDataUtils.cleanSensorData(sensorDataFiles)
    }

    private fun initGraphSettings() {
        for ((index, lineChart) in lineCharts.withIndex()) {
            if (lineChart.data != null && lineChart.data.dataSetCount > 0) {
                // if data already exists, clear it
                lineChart.clear()
            }

            // background color
            lineChart.setBackgroundColor(Color.WHITE)

            // enable touch gestures
            lineChart.setTouchEnabled(true)
            lineChart.description.isEnabled = false

            // set listeners (implement OnChartValueSelectedListener if needed)
            // lineChart.setOnChartValueSelectedListener(this)

            // enable scaling and dragging
            lineChart.isDragEnabled = true
            lineChart.setScaleEnabled(true)

            // force pinch zoom along both axis
            lineChart.setPinchZoom(true)
            // scaling can now only be done on x- and y-axis separately

            // X-Axis Style
            val xAxis = lineChart.xAxis
            xAxis.position = XAxis.XAxisPosition.BOTTOM
            xAxis.textSize = 12f

            // vertical grid lines
            xAxis.enableGridDashedLine(10f, 10f, 0f)

            // Y-Axis Style
            val yAxis = lineChart.axisLeft
            yAxis.textColor = Color.BLACK
            yAxis.textSize = 12f
            // disable dual axis (only use LEFT axis)
            lineChart.axisRight.isEnabled = false
            // horizontal grid lines
            yAxis.enableGridDashedLine(10f, 10f, 0f)


            // limit line as threhold
            // Add a limit line (threshold) to the Y axis
            val limitLine = LimitLine(thresholds[index], thresholds[index].toString()).apply {
                lineWidth = 1.5f
                enableDashedLine(10f, 10f, 0f)
                labelPosition = LimitLine.LimitLabelPosition.RIGHT_TOP
                textSize = 12f
                lineColor = Color.RED
                textColor = Color.RED
            }
            yAxis.removeAllLimitLines()
            yAxis.addLimitLine(limitLine)
            
        }
    }

    private fun plotSensorDatas(sensorDataFiles: List<File>, cleanedFlag: Boolean = true) {
        val datasetLabels = listOf(
            Constants.AccelerometerOutputName,
            Constants.GyroscopeOutputName,
            Constants.MagnetometerOutputName
        )
        var maxX: Float = 0f
        for ((index, lineChart) in lineCharts.withIndex()) {
            var mEntries: List<Array<String>> = emptyList()
            try {
                val csvReader = CSVReader(FileReader(sensorDataFiles[index]))
                mEntries = csvReader.readAll()
                if (mEntries.size <= 2) {
                    return
                }
            } catch (e: Exception) {
                Log.e(Constants.mainLogTag, "Error loading sensor data: ${e.message}", e)
                return 
            }
            
            val values: List<Entry> = extractSensorData(lineChart, mEntries, thresholds[index], datasetLabels[index], cleanedFlag)
            maxX = maxOf(maxX, values.maxOfOrNull { it.x } ?: 0f)

            setSensorData(lineChart, values, datasetLabels[index], thresholds[index])

            // get the legend (only possible after setting data)
            val legend: Legend = lineChart.getLegend()

            // draw legend entries as lines
            legend.form = Legend.LegendForm.LINE
            // set legend position to top
            legend.verticalAlignment = Legend.LegendVerticalAlignment.TOP
            legend.horizontalAlignment = Legend.LegendHorizontalAlignment.CENTER
            legend.orientation = Legend.LegendOrientation.HORIZONTAL
            legend.setDrawInside(false)

            lineChart.invalidate()
        }
        // Set the X axis maximum after all charts are set
        for (lineChart in lineCharts) {
            lineChart.xAxis.axisMaximum = maxX * 1.08f
            lineChart.xAxis.axisMinimum = -maxX * 0.08f
            lineChart.xAxis.granularity = maxX / 10 // Set granularity to 10% of maxX
        }
    }

    private fun extractSensorData(lineChart: LineChart, mEntries: List<Array<String>>, threshold: Float, sensorName: String, cleanedFlag: Boolean = true): List<Entry> {
        val values = ArrayList<Entry>()
        var maxY = Double.MIN_VALUE
        var minY = Double.MAX_VALUE

        if (!cleanedFlag) {
            val initTime = mEntries[1][0].toDouble()
            for (i in 2 until mEntries.size) {
                val curTime = mEntries[i][0].toDouble()
                val preTime = mEntries[i - 1][0].toDouble()
                val x = (curTime - initTime) / 1e9
                val y = 1e9 / (curTime - preTime)
                values.add(Entry(x.toFloat(), y.toFloat()))
                maxY = maxOf(maxY, y)
                minY = minOf(minY, y)
            }
        } else {
            val initTime = mEntries[1][1].toDouble()
            for (i in 1 until mEntries.size) {
                val x = (mEntries[i][1].toDouble() - initTime) / 1e9
                val y = mEntries[i][0].toDouble()
                values.add(Entry(x.toFloat(), y.toFloat()))
                maxY = maxOf(maxY, y)
                minY = minOf(minY, y)

                if (i % 2 == 0 && x - values[values.size - 2].x < Constants.RETESTING_THRESHOLD && y > threshold) {
                    // Check for local peak
                    if ((i - 2 < 1 || mEntries[i-2][0].toDouble() - y > Constants.THRESHOLD_BIAS) && (i + 1 >= mEntries.size || mEntries[i+1][0].toDouble() - y > Constants.THRESHOLD_BIAS)) {
                        // if the current value is a peak, mark it for retesting
                        Log.d(Constants.mainLogTag, "Retesting flag set for sensor: $sensorName at x: $x, y: $y last x: ${values[values.size - 2].x}, last y: ${values[values.size - 2].y}")
                        retestingFlag = true
                    }
                    // Check for local peak
                    else if ((i - 2 < 1 || y - mEntries[i-2][0].toDouble() > Constants.THRESHOLD_BIAS) && (i + 1 >= mEntries.size || y - mEntries[i+2][0].toDouble() > Constants.THRESHOLD_BIAS)) {
                        // if the current value is a valley, mark it for retesting
                        Log.d(Constants.mainLogTag, "Retesting flag set for sensor: $sensorName at x: $x, y: $y last x: ${values[values.size - 2].x}, last y: ${values[values.size - 2].y}")
                        retestingFlag = true
                    }
                }
            }
        }

        maxY = maxOf(maxY, threshold.toDouble())

        with(lineChart.axisLeft) {
            axisMaximum = if (maxY - minY < 5) (maxY + 1.0).toFloat() else (maxY + (maxY - minY) * 0.1).toFloat()
            axisMinimum = if (maxY - minY < 5) (minY - 1.0).toFloat() else (minY - (maxY - minY) * 0.1).toFloat()
        }

        // Track if this sensor's data exceeds the threshold
        if (maxY > threshold) {
            accessedSensors.add(sensorName)
        }
        Log.d(Constants.mainLogTag, "accessedSensors: $accessedSensors")

        return values
    }

    

    private fun setSensorData(lineChart: LineChart, values: List<Entry>, datasetLabel: String, threshold: Float) {
        val data = lineChart.data
        val thresholdData = listOf(Entry(lineChart.xAxis.axisMinimum, threshold), Entry(lineChart.xAxis.axisMaximum, threshold))
        val upperThresholdData = getUpperThresholdData(values, threshold)
        if (data != null && data.dataSetCount > 0) {
            val lineDataset1 = data.getDataSetByIndex(0) as LineDataSet
            val lineDataset2 = data.getDataSetByIndex(1) as LineDataSet
            val lineDataset3 = data.getDataSetByIndex(2) as LineDataSet
            lineDataset1.values = values
            lineDataset2.values = thresholdData
            lineDataset3.values = upperThresholdData

            lineDataset1.notifyDataSetChanged()
            lineDataset2.notifyDataSetChanged()
            lineDataset3.notifyDataSetChanged()
            lineChart.data.notifyDataChanged()
            lineChart.notifyDataSetChanged()
        } else {
            val lineDataset1 = LineDataSet(values, datasetLabel)
            val lineDataset2 = LineDataSet(thresholdData, "Threshold")
            val lineDataset3 = LineDataSet(upperThresholdData, "")
            // lineDataset2.isVisible = false

            lineDataset1.setDrawIcons(false)
            lineDataset2.setDrawIcons(false)
            lineDataset3.setDrawIcons(false)

            // draw dashed line
            lineDataset2.enableDashedLine(1f, 20f, 0f)
            lineDataset3.enableDashedLine(1f, 20f, 0f)

            // black lines and points
            lineDataset1.color = Color.BLUE
            lineDataset2.color = Color.parseColor("#DFABA7") // light red
            lineDataset3.color = Color.BLUE

            // line thickness and point size
            lineDataset1.lineWidth = 1f
            lineDataset2.lineWidth = 0.1f
            lineDataset3.lineWidth = 0.1f
            // lineDataset1.circleRadius = 3f

            // draw points as solid circles
            // lineDataset2.setDrawCircleHole(false)
            lineDataset2.setDrawCircles(false)
            lineDataset3.setDrawCircles(false)

            // customize legend entry
            lineDataset1.formLineWidth = 1f
            lineDataset1.formSize = 15f
            lineDataset2.formLineWidth = 10f
            lineDataset2.formSize = 10f
            lineDataset3.formSize = 0f

            // text size of values
            // Only draw values for odd-indexed entries
            lineDataset1.setDrawValues(true)
            lineDataset1.valueFormatter = object : ValueFormatter() {
                override fun getPointLabel(entry: Entry?): String {
                    if (entry == null) return ""
                    val index = values.indexOf(entry)
                    return if (index % 2 == 0) super.getPointLabel(entry) else ""
                }
            }
            lineDataset1.valueTextSize = 12f
            lineDataset2.setDrawValues(false)
            lineDataset3.setDrawValues(false)

            // draw selection line as dashed
            lineDataset1.isHighlightEnabled = true
            lineDataset1.enableDashedHighlightLine(10f, 5f, 0f)
            lineDataset1.highLightColor = Color.BLUE
            lineDataset1.highlightLineWidth = 0.5f
            lineDataset2.isHighlightEnabled = false
            lineDataset3.isHighlightEnabled = false


            lineDataset3.setDrawFilled(true)
            lineDataset3.fillFormatter = object : IFillFormatter {
                override fun getFillLinePosition(dataSet: ILineDataSet?, dataProvider: LineDataProvider?): Float {
                    return threshold
                }
            }
            lineDataset3.fillColor = Color.RED

            val dataSets = ArrayList<ILineDataSet>()
            dataSets.add(lineDataset1) // add the data sets
            dataSets.add(lineDataset2)
            dataSets.add(lineDataset3)

            // create a data object with the data sets
            val lineData = LineData(dataSets)

            // set data
            lineChart.data = lineData
        }
    }

    private fun getUpperThresholdData(values: List<Entry>, threshold: Float): List<Entry> {

        val upperThresholdData = mutableListOf<Entry>()
        if (values.isEmpty()) return upperThresholdData
        // Add the first point
        val first = values[0]
        if (first.y >= threshold) {
            upperThresholdData.add(first)
        } else {
            upperThresholdData.add(Entry(first.x, threshold))
        }
        for (i in 0 until values.size - 1) {
            val cur = values[i]
            val net = values[i + 1]
            val curAbove = cur.y >= threshold
            val netAbove = net.y >= threshold

            if (curAbove && netAbove) {
                // Both above threshold, add next point
                upperThresholdData.add(net)
            } else if (!curAbove && !netAbove) {
                // Both below threshold, add next point at threshold level
                upperThresholdData.add(Entry(net.x, threshold))
            } else if (curAbove && !netAbove) {
                // Crossing from above to below: add intersection at threshold
                val dx = net.x - cur.x
                val dy = net.y - cur.y
                if (dy != 0f) {
                    val t = (threshold - cur.y) / dy
                    val xIntersect = cur.x + t * dx
                    upperThresholdData.add(Entry(xIntersect, threshold))
                    upperThresholdData.add(Entry(net.x, threshold))
                } else {
                    upperThresholdData.add(Entry(net.x, threshold))
                }
            } else if (!curAbove && netAbove) {
                // Crossing from below to above: add intersection at threshold, then next point
                val dx = net.x - cur.x
                val dy = net.y - cur.y
                if (dy != 0f) {
                    val t = (threshold - cur.y) / dy
                    val xIntersect = cur.x + t * dx
                    upperThresholdData.add(Entry(xIntersect, threshold))
                    upperThresholdData.add(net)
                } else {
                    upperThresholdData.add(net)
                }
            }
        }
        return upperThresholdData
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

    private fun initSensorThresholds() {
        val allSensorThresholds: Map<String, Double> = sensorConfigManager.getAllThresholds()
        acceThresholdString = allSensorThresholds.get(Constants.AccelerometerOutputName).toString()
        gyroThresholdString = allSensorThresholds.get(Constants.GyroscopeOutputName).toString()
        magnThresholdString = allSensorThresholds.get(Constants.MagnetometerOutputName).toString()
    }

    private fun showHelpDialog() {
        val helpMessage = """
            
            🎯 About:
            • Detect apps that secretly access device zero-permission sensors
            • Real-time detection of accelerometer, gyroscope, and magnetometer usage
            • Privacy enhancement through sensor usage awareness
            
            ⚙️ How Monitoring Works:
            
            1️⃣ Configure Settings:
            • Enter a descriptive test name (optional)
            • Set request rate in Hz (default: 1 Hz works well on most phones)
            • Keep "Background Monitoring" checked so that ARMOUR keeps running when the device runs other apps
                        
            2️⃣ Start Monitoring Target Apps:
            • Tap "Start Monitoring" - ARMOUR will move to background
            • Open and interact with the app you want to test
            
            3️⃣ View Results:
            • Return to ARMOUR app and tap "Stop" to end monitoring
            • Tap "See Results" to view detection graphs
            • Spikes above red threshold lines indicate sensor access by other apps
           
        """.trimIndent()
        
        AlertDialog.Builder(this)
            .setTitle("Monitoring Guide")
            .setMessage(helpMessage)
            .setPositiveButton("Got it!") { dialog, _ -> 
                dialog.dismiss()
            }
            .setIcon(android.R.drawable.ic_dialog_info)
            .show()
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