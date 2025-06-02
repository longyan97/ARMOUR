package com.spqr.armour.utils

import android.content.Context
import android.graphics.Color
import android.util.Log
import androidx.core.content.ContextCompat
import com.jjoe64.graphview.GraphView
import com.jjoe64.graphview.LegendRenderer
import com.jjoe64.graphview.series.DataPoint
import com.jjoe64.graphview.series.LineGraphSeries
import com.spqr.armour.Constants
import kotlin.math.abs
import kotlin.math.max

/**
 * Utility class for managing graph displays across all activities.
 * This centralizes graph configuration and styling to ensure consistency
 * and avoid code duplication.
 */
class GraphDisplayManager {
    
    companion object {
        
        /**
         * Sets up basic graph configuration that's common to all graphs
         * 
         * @param graphView The GraphView to configure
         * @param title The title to display on the graph (can be null for no title)
         * @param showLegend Whether to show the graph legend
         */
        fun setupGraphBasics(graphView: GraphView, title: String? = null, showLegend: Boolean = false) {
            // Set title if provided
            if (!title.isNullOrEmpty()) {
                graphView.title = title
                graphView.titleTextSize = 40f
            }
            
            // Configure viewport
            graphView.viewport.apply {
                isXAxisBoundsManual = false
                isScrollable = true
                isScalable = true
                isYAxisBoundsManual = false
            }
            
            // Configure grid labels
            graphView.gridLabelRenderer.apply {
                horizontalAxisTitle = "Time (s)"
                verticalAxisTitle = "Inst. Sample Rate (Hz)"
                numHorizontalLabels = 5
                isHorizontalLabelsVisible = true
                isVerticalLabelsVisible = true
//                textSize = 25f
//                horizontalAxisTitleTextSize = 25f
//                verticalAxisTitleTextSize = 25f
//                labelsSpace = 20
//                padding = 20
            }


            
            // Configure legend
//            graphView.legendRenderer.apply {
//                isVisible = showLegend
//                if (showLegend) {
//                    align = LegendRenderer.LegendAlign.BOTTOM
//                    textSize = 25f
//                    backgroundColor = Color.argb(50, 255, 255, 255)
//                    margin = 15
//                    width = 200
//                }
//            }
            
            Log.d(Constants.mainLogTag, "Graph basics configured: title=$title, showLegend=$showLegend")
        }
        
        /**
         * Plots sensor data on a graph with dynamic Y-axis range
         * 
         * @param context Application context for color resources
         * @param graphView The GraphView to plot on
         * @param dataPoints The data points to plot (time, rate)
         * @param sensorType The type of sensor (for coloring)
         * @param addReferenceLine Whether to add a reference line (for requested rate)
         * @param referenceValue The value for the reference line (if addReferenceLine is true)
         */
        fun plotSensorData(
            context: Context,
            graphView: GraphView, 
            dataPoints: List<Pair<Double, Double>>,
            sensorType: String,
            addReferenceLine: Boolean = false,
            referenceValue: Double? = null
        ) {
            if (dataPoints.isEmpty()) {
                Log.w(Constants.mainLogTag, "No data points available for graph")
                // Set default viewport to ensure the graph is visible
                graphView.viewport.apply {
                    isYAxisBoundsManual = true
                    setMinY(0.0)
                    setMaxY(10.0)
                    setMinX(0.0)
                    setMaxX(5.0)
                }
                return
            }
            
            try {
                // Clear previous series
                graphView.removeAllSeries()
                
                // Convert to DataPoint array
                val points = dataPoints.map { (time, rate) -> 
                    DataPoint(time, rate) 
                }
                .sortedBy { it.x }
                .toTypedArray()
                
                // Create and add the data series
                val series = LineGraphSeries(points).apply {
                    color = getColorForSensor(context, sensorType)
                    title = sensorType
                    isDrawDataPoints = true
                    dataPointsRadius = 4f
                    thickness = 3
                    isDrawAsPath = true

                }
                graphView.addSeries(series)
                
                // Add reference line if requested
                if (addReferenceLine && referenceValue != null) {
                    val refSeries = LineGraphSeries(arrayOf(
                        DataPoint(0.0, referenceValue),
                        DataPoint(dataPoints.last().first + 1, referenceValue)
                    )).apply {
                        color = Color.RED
                        title = "Requested"
                        isDrawDataPoints = true
                        thickness = 2
                        isDrawAsPath = true
                    }
                    graphView.addSeries(refSeries)
                }
                
                // Calculate dynamic range for y-axis
                val minRate = dataPoints.minOf { it.second }
                val maxRate = dataPoints.maxOf { it.second }
//                var minY = maxRate
                // Set graph bounds
                graphView.viewport.apply {
                    isXAxisBoundsManual = true
                    setMaxX(dataPoints.last().first + 0.5)
                    
                    // Check if all values are the same or very close
                    if (abs(maxRate - minRate) < 0.001) {
                        // If all values are the same, set a manual range around that value
                        Log.d(Constants.mainLogTag, "All data points have the same Y value: $minRate. Setting manual range.")
                        
                        val padding = max(0.1, minRate * 0.1) // At least 0.1 or 10% padding
                        isYAxisBoundsManual = true
                        val minY = max(0.0, minRate - padding)
                        setMinY(minY)
                        setMaxY(minRate + padding)
                    } else {
                        // Normal case with a range of values
                        val yRange = maxRate - minRate
                        val yPadding = max(0.1, yRange * 0.1) // At least 0.1 or 10% padding
                        
                        isYAxisBoundsManual = true
                        val minY = max(0.0, minRate - yPadding)
                        setMinY(minY) // Don't go below 0
                        setMaxY(maxRate + yPadding)
                    }
                }

                // 添加一条 y=当前最小y 的水平线作为“x轴”
                val minY = graphView.viewport.getMinY(false)
                val maxX = graphView.viewport.getMaxX(false)
                val xAxisLine = LineGraphSeries(
                    arrayOf(
                        DataPoint(0.0, minY),
                        DataPoint(maxX, minY)
                    )
                )
                xAxisLine.color = Color.BLACK
                xAxisLine.isDrawDataPoints = false
                xAxisLine.thickness = 5
                graphView.addSeries(xAxisLine)
                
                Log.d(Constants.mainLogTag, "Graph updated with ${dataPoints.size} data points")
                Log.d(Constants.mainLogTag, "Y-axis range: ${graphView.viewport.getMinY(false)} to ${graphView.viewport.getMaxY(false)}")
                
            } catch (e: Exception) {
                Log.e(Constants.mainLogTag, "Error plotting sensor data", e)
            }
        }
        
        /**
         * Get color for sensor type
         */
        private fun getColorForSensor(context: Context, sensorType: String): Int {
            return when (sensorType) {
                Constants.AccelerometerOutputName -> ContextCompat.getColor(context, android.R.color.holo_blue_dark)
                Constants.GyroscopeOutputName -> ContextCompat.getColor(context, android.R.color.holo_green_dark)
                Constants.MagnetometerOutputName -> ContextCompat.getColor(context, android.R.color.holo_red_dark)
                else -> Color.BLACK
            }
        }
    }
} 