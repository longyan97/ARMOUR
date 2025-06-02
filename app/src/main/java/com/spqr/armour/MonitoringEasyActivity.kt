package com.spqr.armour

import android.content.Intent
import android.graphics.Color
import android.graphics.DashPathEffect
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.Legend
import com.github.mikephil.charting.components.Legend.LegendForm
import com.github.mikephil.charting.components.LimitLine
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.highlight.Highlight
import com.github.mikephil.charting.interfaces.datasets.ILineDataSet
import com.github.mikephil.charting.listener.OnChartValueSelectedListener
import com.github.mikephil.charting.utils.Utils
import com.spqr.armour.custom.MyMarkerView
import java.io.File


class MonitoringEasyActivity : AppCompatActivity(), SeekBar.OnSeekBarChangeListener, OnChartValueSelectedListener {

    // ui binding
    private lateinit var lineChart: LineChart
    private lateinit var seekBarX: SeekBar
    private lateinit var seekBarY: SeekBar
    private lateinit var tvX: TextView
    private lateinit var tvY: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_monitoring_easy)

        lineChart = findViewById(R.id.linechart)
        seekBarX = findViewById(R.id.seekBarX)
        seekBarY = findViewById(R.id.seekBarY)
        tvX = findViewById(R.id.tvXMax)
        tvY = findViewById(R.id.tvYMax)

        seekBarX.setOnSeekBarChangeListener(this)
        seekBarY.setOnSeekBarChangeListener(this)

        // Chart Style
        // background color
        lineChart.setBackgroundColor(Color.WHITE)

        // disable description text
        lineChart.description.isEnabled = false

        // enable touch gestures
        lineChart.setTouchEnabled(true)

        // set listeners (implement OnChartValueSelectedListener if needed)
        lineChart.setOnChartValueSelectedListener(this)
        lineChart.setDrawGridBackground(false)

        // create marker to display box when values are selected
        val mv = MyMarkerView(this, R.layout.custom_marker_view)

        // Set the marker to the chart
        mv.chartView = lineChart
        lineChart.marker = mv

        // enable scaling and dragging
        lineChart.isDragEnabled = true
        lineChart.setScaleEnabled(true)

        // force pinch zoom along both axis
        lineChart.setPinchZoom(true)

        // X-Axis Style
        val xAxis = lineChart.xAxis
        // vertical grid lines
        xAxis.enableGridDashedLine(10f, 10f, 0f)

        // Y-Axis Style
        val yAxis = lineChart.axisLeft
        // disable dual axis (only use LEFT axis)
        lineChart.axisRight.isEnabled = false
        // horizontal grid lines
        yAxis.enableGridDashedLine(10f, 10f, 0f)
        // axis range
        yAxis.axisMaximum = 200f
        yAxis.axisMinimum = -50f

        // Create Limit Lines
        // Note: Typeface tfRegular is not defined in your code. If you want to use a custom typeface, define it accordingly.
        // For now, we'll skip setting the typeface.

        // X Axis Limit Line
        val llXAxis = LimitLine(9f, "Index 10").apply {
            lineWidth = 4f
            enableDashedLine(10f, 10f, 0f)
            labelPosition = LimitLine.LimitLabelPosition.RIGHT_BOTTOM
            textSize = 10f
            // typeface = tfRegular // Uncomment and define tfRegular if needed
        }

        // Y Axis Upper Limit Line
        val ll1 = LimitLine(150f, "Upper Limit").apply {
            lineWidth = 4f
            enableDashedLine(10f, 10f, 0f)
            labelPosition = LimitLine.LimitLabelPosition.RIGHT_TOP
            textSize = 10f
            // typeface = tfRegular // Uncomment and define tfRegular if needed
        }

        // Y Axis Lower Limit Line
        val ll2 = LimitLine(-30f, "Lower Limit").apply {
            lineWidth = 4f
            enableDashedLine(10f, 10f, 0f)
            labelPosition = LimitLine.LimitLabelPosition.RIGHT_BOTTOM
            textSize = 10f
            // typeface = tfRegular // Uncomment and define tfRegular if needed
        }

        // Draw limit lines behind data instead of on top
        yAxis.setDrawLimitLinesBehindData(true)
        xAxis.setDrawLimitLinesBehindData(true)

        // Add limit lines
        yAxis.addLimitLine(ll1)
        yAxis.addLimitLine(ll2)
        xAxis.addLimitLine(llXAxis) // Uncomment if you want to add the X axis limit line

        // add data
        seekBarX.progress = 45
        seekBarY.progress = 180
        setData(45, 180)

        // draw points over time
        lineChart.animateX(1500)

        // get the legend (only possible after setting data)
        val legend: Legend = lineChart.legend

        // draw legend entries as lines
        legend.form = LegendForm.LINE

    }

    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
        tvX.text = "X: ${seekBarX.progress}"
        tvY.text = "Y: ${seekBarY.progress}"
        setData(seekBarX.progress, seekBarY.progress)

        lineChart.invalidate()

    }

    private fun setData(count: Int, range: Int) {
        val values = ArrayList<Entry>()

        for (i in 0 until count) {
            val value = (Math.random() * range).toFloat() - 30
            val drawable = resources.getDrawable(R.drawable.star, null)
            values.add(Entry(i.toFloat(), value, drawable))
        }

        val data = lineChart.data
        if (data != null && data.dataSetCount > 0) {
            val lineDataset1 = data.getDataSetByIndex(0) as LineDataSet
            lineDataset1.values = values
            lineDataset1.notifyDataSetChanged()
            data.notifyDataChanged()
            lineChart.notifyDataSetChanged()
        } else {
            val lineDataset1 = LineDataSet(values, "DataSet 1")
            lineDataset1.setDrawIcons(false)

            // draw dashed line
            lineDataset1.enableDashedLine(10f, 5f, 0f)

            // black lines and points
            lineDataset1.color = android.graphics.Color.BLACK
            lineDataset1.setCircleColor(android.graphics.Color.BLACK)

            // line thickness and point size
            lineDataset1.lineWidth = 1f
            lineDataset1.circleRadius = 3f

            // draw points as solid circles
            lineDataset1.setDrawCircleHole(false)

            // customize legend entry
            lineDataset1.formLineWidth = 1f
            lineDataset1.formLineDashEffect = DashPathEffect(floatArrayOf(10f, 5f), 0f) // Make legend line solid
            lineDataset1.formSize = 15f

            // text size of values
            lineDataset1.valueTextSize = 9f

            // draw selection line as dashed
            lineDataset1.enableDashedHighlightLine(10f, 5f, 0f)

            val dataSets = ArrayList<ILineDataSet>()
            dataSets.add(lineDataset1) // add the data sets

            // create a data object with the data sets
            val lineData = LineData(dataSets)

            // set data
            lineChart.data = lineData
        }
    }

    override fun onStartTrackingTouch(seekBar: SeekBar?) {
        // No action needed
    }
    override fun onStopTrackingTouch(seekBar: SeekBar?) {
        // No action needed
    }

    override fun onValueSelected(e: Entry?, h: Highlight?) {
        Log.i("Entry selected", e.toString());
        Log.i("LOW HIGH", "low: " + lineChart.getLowestVisibleX() + ", high: " + lineChart.getHighestVisibleX());
        Log.i("MIN MAX", "xMin: " + lineChart.getXChartMin() + ", xMax: " + lineChart.getXChartMax() + ", yMin: " + lineChart.getYChartMin() + ", yMax: " + lineChart.getYChartMax());

    }

    override fun onNothingSelected() {
        Log.i("Nothing selected", "Nothing selected.");
    }
}