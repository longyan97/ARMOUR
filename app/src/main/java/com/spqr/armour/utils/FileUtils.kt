package com.spqr.armour.utils

import android.content.Context
import android.os.Environment
import android.util.Log
import com.spqr.armour.Constants
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Utility functions for file operations
 */
object FileUtils {
    
    /**
     * Creates a unique output directory for storing sensor data.
     * The directory name combines the timestamp and test name to ensure uniqueness.
     * Used by the monitoring activity.
     *
     * @param context The application context
     * @param testName The user-provided test name (can be empty)
     * @return The absolute path of the created directory
     */
    fun createUniqueOutputDir(context: Context, testName: String): String {
        // Format: YYYY_MM_DD_HH_mm_ss_TestName
        val dateFormat = SimpleDateFormat("yyyy_MM_dd_HH_mm_ss", Locale.US)
        val timestamp = dateFormat.format(Date())
        
        // Create folder name with timestamp first, then test name
        val folderName = if (testName.trim().isEmpty()) {
            "${timestamp}_unnamed"
        } else {
            "${timestamp}_${testName.trim()}"
        }
        
        // Get the external files directory path
        val targetDir = context.getExternalFilesDir(
            Environment.getDataDirectory().absolutePath
        )?.absolutePath
        
        // Combine to create the full output directory path
        val outputDir = targetDir + File.separator + folderName
        
        // Create the directory
        val isDirCreated = File(outputDir).mkdirs()
        Log.d(Constants.mainLogTag, "$outputDir created: $isDirCreated")
        
        return outputDir
    }
    
    /**
     * Creates a unique output directory for storing monitoring data.
     * The directory name combines the timestamp, "Monitoring", and test name to ensure uniqueness.
     * Used exclusively by the monitoring activity.
     *
     * @param context The application context
     * @param testName The user-provided test name (can be empty)
     * @return The absolute path of the created directory
     */
    fun createUniqueMonitoringDir(context: Context, testName: String): String {
        // Format: YYYY_MM_DD_HH_mm_ss_Monitoring_TestName
        val dateFormat = SimpleDateFormat("yyyy_MM_dd_HH_mm_ss", Locale.US)
        val timestamp = dateFormat.format(Date())
        
        // Create folder name with timestamp first, then "Monitoring", then test name
        val folderName = if (testName.trim().isEmpty()) {
            "${timestamp}_Monitoring_unnamed"
        } else {
            "${timestamp}_Monitoring_${testName.trim()}"
        }
        
        // Get the external files directory path
        val targetDir = context.getExternalFilesDir(
            Environment.getDataDirectory().absolutePath
        )?.absolutePath
        
        // Combine to create the full output directory path
        val outputDir = targetDir + File.separator + folderName
        
        // Create the directory
        val isDirCreated = File(outputDir).mkdirs()
        Log.d(Constants.mainLogTag, "$outputDir created: $isDirCreated")
        
        return outputDir
    }
    
    /**
     * Creates a unique output directory for storing profiling data.
     * The directory name combines the timestamp, "Profiling", and test name to ensure uniqueness.
     * Used exclusively by the profiling activity.
     *
     * @param context The application context
     * @param testName The user-provided test name (can be empty)
     * @return The absolute path of the created directory
     */
    fun createUniqueProfilingDir(context: Context, testName: String): String {
        // Format: YYYY_MM_DD_HH_mm_ss_Profiling_TestName
        val dateFormat = SimpleDateFormat("yyyy_MM_dd_HH_mm_ss", Locale.US)
        val timestamp = dateFormat.format(Date())
        
        // Create folder name with timestamp first, then "Profiling", then test name
        val folderName = if (testName.trim().isEmpty()) {
            "${timestamp}_Profiling_unnamed"
        } else {
            "${timestamp}_Profiling_${testName.trim()}"
        }
        
        // Get the external files directory path
        val targetDir = context.getExternalFilesDir(
            Environment.getDataDirectory().absolutePath
        )?.absolutePath
        
        // Combine to create the full output directory path
        val outputDir = targetDir + File.separator + folderName
        
        // Create the directory
        val isDirCreated = File(outputDir).mkdirs()
        Log.d(Constants.mainLogTag, "$outputDir created: $isDirCreated")
        
        return outputDir
    }
} 