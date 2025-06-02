package com.spqr.armour


import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

import android.hardware.SensorManager.SENSOR_DELAY_NORMAL
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.util.Log
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter

class IMUManager(fgService: Context): SensorEventListener {
    companion object {
        // armour service will change this variable
        var mSensorRate: Int = SENSOR_DELAY_NORMAL;
    }
    private lateinit var mSensorManager: SensorManager
    private lateinit var mAcclSensor: Sensor
    private lateinit var mGyroSensor: Sensor
    private lateinit var mMagnSensor: Sensor

    private lateinit var mSensorThread: HandlerThread

    // It ensures that reads and writes to the variable are directly from and to the main memory
    // providing visibility guarantees for multi-threaded operations.
    @Volatile
    private var mRecordingInertialData: Boolean = false
    private lateinit var writers: List<BufferedWriter>
    private var mAcceWriter: BufferedWriter? = null
    private var mGyroWriter: BufferedWriter? = null
    private var mMagnWriter: BufferedWriter? = null

    private val ImuHeader = "Timestamp[nanosec],sensor_x,sensor_y,sensor_z,Unix time[nanosec]\n";


    init {
        mSensorManager = fgService.getSystemService(Context.SENSOR_SERVICE) as SensorManager

        mAcclSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)!!
        mGyroSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)!!
        mMagnSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)!!

        Log.d(Constants.mainLogTag, "Creating new IMU Manager [Success]")
    }

    private class SensorPacket(val timestamp: Long, val unixTime: Long, val values: List<Float>) {
        override fun toString(): String {
            val delimiter = ","
            return StringBuilder().run {
                append(timestamp)
                for (value in values) {
                    append(delimiter).append(value)
                }
                append(delimiter + unixTime + "000000")
                toString()
            }
        }
    }

    fun startRecording(captureResultDir: String) {
        Log.d(Constants.mainLogTag, "Recording ...")
        Log.d(Constants.mainLogTag, "TargetDir: ${captureResultDir}")

        // init writers
        writers = listOf(
            Constants.AccelerometerOutputName +".csv",
            Constants.GyroscopeOutputName +".csv",
            Constants.MagnetometerOutputName +".csv"
            ).map {
                fileName -> BufferedWriter(FileWriter(captureResultDir + File.separator + fileName))
        }
        writers.forEach{writer -> writer.write(ImuHeader)}
        mAcceWriter = writers[0]
        mGyroWriter = writers[1]
        mMagnWriter = writers[2]
        mRecordingInertialData = true
    }

    fun stopRecording() {
        // close writers
        if (mRecordingInertialData) {
            mRecordingInertialData = false
            for (writer in writers) {
                writer.flush()
                writer.close()
            }
        }
        mAcceWriter = null
        mMagnWriter = null
        mGyroWriter = null

        Log.d(Constants.mainLogTag, "Recording Stopped. [Success] ")
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (!mRecordingInertialData or (event == null)) {
            return
        }

        // recording sensor data
        val unixTime = System.currentTimeMillis()
        val sensorPacket = SensorPacket(event!!.timestamp, unixTime, event.values.toList())

        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                mAcceWriter!!.write(sensorPacket.toString() + "\n")
            }
            Sensor.TYPE_GYROSCOPE -> {
                mGyroWriter!!.write(sensorPacket.toString() + "\n")
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                mMagnWriter!!.write(sensorPacket.toString() + "\n")
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    public fun register() {
        Log.d(Constants.mainLogTag, "IMUManager Register Start [target sensor rate ${mSensorRate}] ...")
        mSensorThread = HandlerThread("Sensor Thread", Process.THREAD_PRIORITY_MORE_FAVORABLE)
        mSensorThread.start()

        val sensorHandler = Handler(mSensorThread.looper)
        val sensors = listOf(mAcclSensor, mGyroSensor, mMagnSensor)
        for (sensor in sensors) {
            mSensorManager.registerListener(this, sensor, mSensorRate, sensorHandler)
        }
        Log.d(Constants.mainLogTag, "IMUManager Register End [Success]")
    }

    fun unregister() {
        Log.d(Constants.mainLogTag, "IMUManager Unregister Start ...")
        val sensors = listOf(mAcclSensor, mGyroSensor, mMagnSensor)
        for (sensor in sensors) {
            mSensorManager.unregisterListener(this, sensor)
        }
        mSensorManager.unregisterListener(this)
        mSensorThread.quitSafely()
        stopRecording()
        Log.d(Constants.mainLogTag, "IMUManager Unregister End [Success]")
    }

    fun exit() {
        stopRecording()
        unregister()
    }
}