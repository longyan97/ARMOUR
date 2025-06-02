package com.spqr.armour.utils

import android.util.Log
import com.opencsv.CSVReader
import com.spqr.armour.Constants
import java.io.File
import java.io.FileReader
import java.io.FileWriter

object SensorDataUtils {

    // input sensor data files, output processed sensor data files

    fun cleanSensorData(sensorDataFiles: List<File>): List<File> {
        val processedSensorDataFiles = mutableListOf<File>()
        
        for (file in sensorDataFiles) {
            
            // ########################### 1. read sensor data file iteratively ###########################
            val csvReader = CSVReader(FileReader(file))
            val entries = csvReader.readAll()
            csvReader.close()
            
            Log.d(Constants.mainLogTag, "Processing file: ${file.name} with ${entries.size} entries")

            // 1.1 skip the header
            entries.removeAt(0)

            // ########################### 2. clean the sensor data ###########################
        
            // 2.1 get time series data and unix time series data
            val time_series = entries.map { it[0] }
            val unix_time_series = entries.map { it.last() }
            
            // 2.2 if size of time_series and unix_time_series less than 2, return the original file
            if (time_series.size < 2 || unix_time_series.size < 2) {
                processedSensorDataFiles.add(file)
                continue
            }

            // 2.3 init the cleaned sensor data
            val callSeries = time_series.zipWithNext()
                .filter { (preTime, curTime) -> curTime != preTime }
                .map { (preTime, curTime) -> 1e9 / (curTime.toDouble() - preTime.toDouble()) }
                .toMutableList()
            val unixTimeSeriesProcessed = unix_time_series.drop(1).toMutableList()

            // Remove outliers at the start of the series
            while (callSeries.size > 2 && (outlierChecker(callSeries[0].toFloat(), callSeries[1].toFloat()) || outlierChecker(callSeries[0].toFloat(), callSeries[2].toFloat()))) {
                callSeries.removeAt(0)
                unixTimeSeriesProcessed.removeAt(0)
            }

            // Remove outliers at the end of the series
            while (callSeries.size >= 3 && (outlierChecker(callSeries[callSeries.size - 1].toFloat(), callSeries[callSeries.size - 2].toFloat()) || outlierChecker(callSeries[callSeries.size - 1].toFloat(), callSeries[callSeries.size - 3].toFloat()))) {
                callSeries.removeAt(callSeries.size - 1)
                unixTimeSeriesProcessed.removeAt(unixTimeSeriesProcessed.size - 1)
            }

            if (callSeries.size < 2) {
                processedSensorDataFiles.add(file)
                continue
            }

            // Create new lists with padding at start and end, STEP = 3
            val paddedCallSeries = listOf(callSeries[0], callSeries[0]) + callSeries + listOf(callSeries.last(), callSeries.last())
            val paddedUnixTimeSeries = listOf(unixTimeSeriesProcessed[0], unixTimeSeriesProcessed[0]) + 
                                    unixTimeSeriesProcessed + 
                                    listOf(unixTimeSeriesProcessed.last(), unixTimeSeriesProcessed.last())

            // Initialize stable series with first element
            val stableSeries = mutableListOf(
                mutableListOf(false, toRoundFloat(paddedCallSeries[0]), paddedUnixTimeSeries[0])
            )

            // Process the middle elements
            for (i in 2 until paddedCallSeries.size - 2) {
                val call1 = toRoundFloat(paddedCallSeries[i - 2])
                val call2 = toRoundFloat(paddedCallSeries[i - 1])
                val call3 = toRoundFloat(paddedCallSeries[i])
                val call4 = toRoundFloat(paddedCallSeries[i + 1])
                val call5 = toRoundFloat(paddedCallSeries[i + 2])

                if ((outlierChecker(call3, call2) || outlierChecker(call3, call1)) && (outlierChecker(call3, call4) || outlierChecker(call3, call5))) {
                    // Outlier
                    val newCall = if (Math.abs(call3 - call1) <= Math.abs(call3 - call5)) call1 else call5
                    stableSeries.add(mutableListOf(false, newCall, paddedUnixTimeSeries[i]))

                } else {
                    // Stable
                    stableSeries.add(mutableListOf(false, call3, paddedUnixTimeSeries[i]))
                }
            }

            // Add the last element
            stableSeries.add(mutableListOf(false, toRoundFloat(paddedCallSeries.last()), paddedUnixTimeSeries.last()))

            // 2.4 get the cleaned stable series
            val cleanedStableSeries = mutableListOf<MutableList<Any>>()
            cleanedStableSeries.add(stableSeries[0].toMutableList())
            var i = 1
            while (i < stableSeries.size - 1) {
                val (_, call1Any, _) = cleanedStableSeries.last()
                val (outlierFlag2, call2Any, time2) = stableSeries[i]
                val (outlierFlag3, call3Any, _) = stableSeries[i + 1]

                val call1 = (call1Any as Number).toFloat()
                var call2 = (call2Any as Number).toFloat()
                var call3 = (call3Any as Number).toFloat()

                when {
                    !outlierChecker(call2, call1) && !outlierChecker(call2, call3) -> {
                        // Stable data point in series, skip
                    }
                    !outlierChecker(call2, call1) && outlierChecker(call2, call3) -> {
                        // The ending of a stable series
//                        if (cleanedStableSeries.size > 1 && !outlierChecker(call2, cleanedStableSeries[cleanedStableSeries.size - 2][1].toString().toFloat())) {
//                            cleanedStableSeries.last()[2] = time2
//                        } else {
                        cleanedStableSeries.add(stableSeries[i].toMutableList())
                    }
                    outlierChecker(call2, call1) && !outlierChecker(call2, call3) -> {
                        // The beginning of a stable series
                        cleanedStableSeries.add(stableSeries[i].toMutableList())
                    }
                    outlierChecker(call2, call1) && outlierChecker(call2, call3) -> {
                        // Multiple outlier
//                        cleanedStableSeries.add(stableSeries[i].toMutableList())
                        // should no this case
                    }
                    else -> {
                        throw RuntimeException("what else? ")
                    }
                }
                i++
            }

            // Process the last element
//            if (cleanedStableSeries.size > 1 && !outlierChecker(stableSeries.last()[1] as Float, cleanedStableSeries[cleanedStableSeries.size - 2][1] as Float)) {
//                cleanedStableSeries.last()[2] = stableSeries.last()[2]
//            } else {
//                cleanedStableSeries.add(stableSeries.last().toMutableList())
//            }
            cleanedStableSeries.add(stableSeries.last().toMutableList())
            // 3. save the processed sensor data to a new file: first line is the frequency data, second line is the unix time data
            val processedSensorDataFile = File(file.parent, "cleaned_${file.name}")
            val writer = FileWriter(processedSensorDataFile)
            // 3.1 write the header
            writer.write("frequency,unix_time\n")
            // write the data
            for (i in 0 until cleanedStableSeries.size) {
                writer.write("${cleanedStableSeries[i][1]},${cleanedStableSeries[i][2]}\n")
            }
            writer.close()
            processedSensorDataFiles.add(processedSensorDataFile)
        }
        // 4. return the list of processed sensor data files
        return processedSensorDataFiles
    }

    // save value with n decimal places
    fun toRoundFloat(value: Double, n: Int = 1): Float {
        return String.format("%.${n}f", value).toFloat()
    }

    fun outlierChecker(call1: Float, call2: Float): Boolean {
        val maxCall = maxOf(call1, call2)
        // Example of how to use a threshold based on maxCall, if needed:
        val threshold = when {
            maxCall < 10 -> 0.1
            maxCall < 50 -> 0.5
            maxCall < 400 -> 1.0
            else -> 2.0
        }
        return kotlin.math.abs(call1 - call2) > threshold
    }

    // Input Sensor Data Files, Output Two Illustration Data list: x and y
    fun getIllustrationData(sensorDataFile: File): Pair<List<Double>, List<Double>> {
        val csvReader = CSVReader(FileReader(sensorDataFile))
        val entries = csvReader.readAll()
        csvReader.close()

        // Skip the header
        entries.removeAt(0)

        // Get the time series and unix time series
        val time_series = entries.map { it[0].toDouble() }
        val unix_time_series = entries.map { it.last().toDouble() }

        // If size of time_series and unix_time_series less than 2, return empty lists
        if (time_series.size < 2 || unix_time_series.size < 2) {
            return Pair(emptyList(), emptyList())
        }

        // Get the cleaned stable series
        val cleanedStableSeries = mutableListOf<MutableList<Any>>()
        cleanedStableSeries.add(mutableListOf(false, toRoundFloat(time_series[0]), unix_time_series[0]))
        
        for (i in 1 until time_series.size) {
            cleanedStableSeries.add(mutableListOf(false, toRoundFloat(time_series[i]), unix_time_series[i]))
        }

        // Prepare x and y data for illustration
        val xData = cleanedStableSeries.map { it[1] as Double }
        val yData = cleanedStableSeries.map { it[2] as Double }

        return Pair(xData, yData)
    }

}