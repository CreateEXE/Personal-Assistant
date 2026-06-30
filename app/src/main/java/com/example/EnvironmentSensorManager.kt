package com.example

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.BatteryManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class EnvironmentSensorManager(private val context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)
    private val accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private val _ambientLight = MutableStateFlow(0f)
    val ambientLight = _ambientLight.asStateFlow()

    private val _accelerometerData = MutableStateFlow(Triple(0f, 0f, 0f))
    val accelerometerData = _accelerometerData.asStateFlow()

    private var isListening = false
    private var job: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    fun startListening() {
        if (isListening) return
        isListening = true
        
        job = scope.launch {
            while (isActive) {
                val batteryLevel = getBatteryLevel()
                val isLowPower = batteryLevel != -1 && batteryLevel < 20
                
                registerListenersInternal()
                delay(5000) // Gather sensor telemetry for 5 seconds
                unregisterListenersInternal()
                
                val delayTime = if (isLowPower) {
                    60 * 60 * 1000L // Reduce frequency to 60 minutes
                } else {
                    5000L // Standard 5-second interval
                }
                delay(delayTime)
            }
        }
    }

    private fun registerListenersInternal() {
        lightSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
        accelSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    private fun unregisterListenersInternal() {
        sensorManager.unregisterListener(this)
    }

    fun stopListening() {
        isListening = false
        job?.cancel()
        unregisterListenersInternal()
    }

    fun getBatteryLevel(): Int {
        val batteryStatus: Intent? = IntentFilter(Intent.ACTION_BATTERY_CHANGED).let { ifilter ->
            context.registerReceiver(null, ifilter)
        }
        val level: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        return if (level != -1 && scale != -1) {
            (level * 100 / scale.toFloat()).toInt()
        } else {
            -1
        }
    }

    fun getEnvironmentContext(): String {
        return """
            [ENVIRONMENT CONTEXT]
            Battery Level: ${getBatteryLevel()}%
            Ambient Light: ${_ambientLight.value} lx
            Accelerometer: x=${_accelerometerData.value.first}, y=${_accelerometerData.value.second}, z=${_accelerometerData.value.third}
        """.trimIndent()
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event?.let {
            when (it.sensor.type) {
                Sensor.TYPE_LIGHT -> {
                    _ambientLight.value = it.values[0]
                }
                Sensor.TYPE_ACCELEROMETER -> {
                    _accelerometerData.value = Triple(it.values[0], it.values[1], it.values[2])
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
