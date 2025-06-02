package com.spqr.armour

import android.util.Log
import java.io.File
import java.io.FileReader
import com.opencsv.CSVReader
import com.spqr.armour.utils.SensorDataUtils
import java.io.FileNotFoundException

/**
 * Helper class to find the minimum supported sampling rates for sensors
 * based on profiling test results.
 */
class MinSampRateFinder {
    
    companion object {
        private val sensorTypes = arrayOf(
            Constants.AccelerometerOutputName,
            Constants.GyroscopeOutputName,
            Constants.MagnetometerOutputName
        )
        
        /**
         * Calculate minimum supported sampling rates for all sensors based on a test directory
         * 
         * @param testDirectory The directory containing the profiling test results
         * @return Map of sensor names to their minimum supported sampling rates
         */
        fun calculateMinSupportedRates(testDirectory: File): Map<String, Double> {
            Log.d(Constants.mainLogTag, "MinSampRateFinder: Calculating minimum supported rates from ${testDirectory.absolutePath}")
            
            val result = mutableMapOf<String, Double>()
            val sensorRateStats = loadSensorStatsFromDirectory(testDirectory)
            
            // Calculate minimum for each sensor
            for (sensorType in sensorTypes) {
                val sensorStats = sensorRateStats[sensorType]
                
                if (sensorStats.isNullOrEmpty()) {
                    Log.w(Constants.mainLogTag, "MinSampRateFinder: No data available for $sensorType")
                    result[sensorType] = 0.0
                    continue
                }
                
                // Get the actual average rates for each requested rate
                val actualAverageRates = sensorStats.entries.mapNotNull { entry ->
                    val stats = entry.value
                    if (stats.sampleCount > 0) stats.avgRate else null
                }
                
                if (actualAverageRates.isEmpty()) {
                    Log.w(Constants.mainLogTag, "MinSampRateFinder: No valid rates for $sensorType")
                    result[sensorType] = 0.0
                    continue
                }
                
                // Find the minimum actual rate
                val minRate = actualAverageRates.minOrNull() ?: 0.0
                result[sensorType] = minRate
                
                Log.d(Constants.mainLogTag, "MinSampRateFinder: Minimum rate for $sensorType = $minRate Hz")
            }
            
            return result
        }
        
        /**
         * Load sensor statistics from a test directory
         */
        private fun loadSensorStatsFromDirectory(baseDirectory: File): Map<String, Map<String, SensorStats>> {
            val result = mutableMapOf<String, MutableMap<String, SensorStats>>()
            
            // Initialize result structure
            sensorTypes.forEach { sensorType ->
                result[sensorType] = mutableMapOf()
            }
            
            // Check if directory contains rate subdirectories
            val rateDirectories = baseDirectory.listFiles()?.filter { it.isDirectory } ?: emptyList()
            
            if (rateDirectories.isEmpty()) {
                // No rate directories, check if sensor files are directly in base directory
                for (sensorType in sensorTypes) {
                    val file = File(baseDirectory, "$sensorType.csv")
                    val cleanedFile = SensorDataUtils.cleanSensorData(listOf(file))[0]
                    if (cleanedFile.exists()) {
                        try {
                            val stats = calculateSensorStats(cleanedFile, cleanedFlag = true)
                            result[sensorType]?.put("1", stats)
                            Log.d(Constants.mainLogTag, "MinSampRateFinder: Processed $sensorType.csv directly with avg rate: ${stats.avgRate}")
                        } catch (e: Exception) {
                            Log.e(Constants.mainLogTag, "MinSampRateFinder: Error processing file: ${file.absolutePath}", e)
                        }
                    }
                }
            } else {
                // Process each rate directory
                for (rateDir in rateDirectories) {
                    val rate = rateDir.name
                    Log.d(Constants.mainLogTag, "MinSampRateFinder: Processing rate directory: $rate")
                    
                    // Process each sensor type
                    for (sensorType in sensorTypes) {
                        val file = File(rateDir, "$sensorType.csv")
                        val cleanedFile = SensorDataUtils.cleanSensorData(listOf(file))[0]
                        if (cleanedFile.exists()) {
                            try {
                                val stats = calculateSensorStats(cleanedFile, cleanedFlag = true)
                                result[sensorType]?.put(rate, stats)
                                Log.d(Constants.mainLogTag, "MinSampRateFinder: Processed $sensorType.csv for rate $rate with avg rate: ${stats.avgRate}")
                            } catch (e: Exception) {
                                Log.e(Constants.mainLogTag, "MinSampRateFinder: Error processing file: ${file.absolutePath}", e)
                            }
                        }
                    }
                }
            }
            
            return result
        }

        /**
         * Calculate statistics from a sensor data file
         */
        private fun calculateSensorStats(file: File, cleanedFlag: Boolean): SensorStats {
            try {
                val csvReader = CSVReader(FileReader(file))
                val entries = csvReader.readAll()
                csvReader.close()
                
                if ((!cleanedFlag && entries.size <= 3) || (cleanedFlag && entries.size <= 2)) {
                    Log.w(Constants.mainLogTag, "MinSampRateFinder: Not enough data in file: ${file.path}")
                    return SensorStats(0.0, 0)
                }
                
                var totalRate = 0.0
                val timeIndex = if (cleanedFlag) 1 else 0
                val initTime = entries[1][timeIndex].toDouble()

                if (!cleanedFlag) {
                    for (i in 3 until entries.size) {
                        val curTime = entries[i][0].toDouble()
                        val preTime = entries[i - 1][0].toDouble()
                        val rate = 1.0e9 / (curTime - preTime) // Convert nanoseconds to Hz

                        totalRate += rate
                    }
                    val avgRate = totalRate / (entries.size - 3)
                    return SensorStats(avgRate, entries.size - 3)
                } else {
                    // for cleaned data, the rate is the first column, the time is the second column
                    // 1. for every 2 data, calculate the rate duration, return the rate which has the largest duration
                    var maxRate = 0.0
                    var maxDuration = -1.0
                    for (i in 2 until entries.size step 2){
                        val curTime = entries[i][timeIndex].toDouble()
                        val preTime = entries[i - 1][timeIndex].toDouble()
                        val duration = curTime - preTime
                        val rate = entries[i][0].toDouble()

                        if (duration > maxDuration) {
                            maxDuration = duration
                            maxRate = rate
                        }
                    }
                    return SensorStats(maxRate, entries.size - 1)
                }
                
                
            } catch (e: FileNotFoundException) {
                Log.e(Constants.mainLogTag, "MinSampRateFinder: File not found: ${file.path}", e)
                throw e
            } catch (e: Exception) {
                Log.e(Constants.mainLogTag, "MinSampRateFinder: Error calculating stats for file: ${file.path}", e)
                throw e
            }
        }
        
        /**
         * Find the most recent test directory that matches the given test name
         */
        fun findTestDirectory(baseDirectory: String, testName: String): File? {
            val baseDir = File(baseDirectory)
            if (!baseDir.exists() || !baseDir.isDirectory) {
                Log.e(Constants.mainLogTag, "MinSampRateFinder: Base directory does not exist: $baseDirectory")
                return null
            }
            
            // Look for directories with timestamp pattern: YYYY_MM_DD_HH_mm_ss_Profiling_testName
            val allDirs = baseDir.listFiles()?.filter { it.isDirectory } ?: emptyList()
            
            // First try with new format: YYYY_MM_DD_HH_mm_ss_Profiling_testName
            var testDirs = allDirs.filter { dir -> 
                dir.name.matches(Regex("\\d{4}_\\d{2}_\\d{2}_\\d{2}_\\d{2}_\\d{2}_Profiling_${testName}"))
            }.sortedByDescending { it.lastModified() }
            
            // If no matches with new format, try legacy format: YYYY_MM_DD_HH_mm_ss_testName
            if (testDirs.isEmpty()) {
                testDirs = allDirs.filter { dir -> 
                    dir.name.matches(Regex("\\d{4}_\\d{2}_\\d{2}_\\d{2}_\\d{2}_\\d{2}_${testName}"))
                }.sortedByDescending { it.lastModified() }
            }
            
            if (testDirs.isNotEmpty()) {
                return testDirs.first()
            }
            
            // If still not found, try exact test name match
            val exactTestDir = File(baseDir, testName)
            if (exactTestDir.exists() && exactTestDir.isDirectory) {
                return exactTestDir
            }
            
            // Try unnamed directories
            val unnamedDirs = allDirs.filter { dir -> 
                dir.name.matches(Regex("\\d{4}_\\d{2}_\\d{2}_\\d{2}_\\d{2}_\\d{2}_Profiling_unnamed")) || 
                dir.name.matches(Regex("\\d{4}_\\d{2}_\\d{2}_\\d{2}_\\d{2}_\\d{2}_unnamed"))
            }.sortedByDescending { it.lastModified() }
            
            if (unnamedDirs.isNotEmpty()) {
                return unnamedDirs.first()
            }
            
            // Final fallback to plain unnamed directory
            val unnamedDir = File(baseDir, "unnamed")
            if (unnamedDir.exists() && unnamedDir.isDirectory) {
                return unnamedDir
            }
            
            Log.e(Constants.mainLogTag, "MinSampRateFinder: Could not find test directory for test: $testName")
            return null
        }
    }
    
    /**
     * Simple data class to store sensor statistics
     */
    data class SensorStats(
        val avgRate: Double,
        val sampleCount: Int
    )
} 