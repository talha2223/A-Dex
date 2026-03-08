package com.adex.app.util

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.sqrt

object ShakeAlertManager {
    private var sensorManager: SensorManager? = null
    private var listener: SensorEventListener? = null
    private var threshold: Float = 16f
    private var enabled: Boolean = false
    private var lastShakeAt: Long = 0L
    private var shakeCount: Int = 0

    fun start(context: Context, threshold: Float): Boolean {
        this.threshold = threshold
        val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val accelerometer = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return false

        stop(context)

        val shakeListener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val values = event.values
                if (values.size < 3) {
                    return
                }

                val x = values[0]
                val y = values[1]
                val z = values[2]
                val gForce = sqrt((x * x + y * y + z * z).toDouble()) - SensorManager.GRAVITY_EARTH

                if (gForce >= this@ShakeAlertManager.threshold) {
                    val now = System.currentTimeMillis()
                    if (now - lastShakeAt >= 700L) {
                        lastShakeAt = now
                        shakeCount += 1
                    }
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
                // No-op
            }
        }

        val registered = manager.registerListener(shakeListener, accelerometer, SensorManager.SENSOR_DELAY_NORMAL)
        if (!registered) {
            return false
        }

        sensorManager = manager
        listener = shakeListener
        enabled = true
        return true
    }

    fun stop(context: Context) {
        listener?.let { activeListener ->
            val manager = sensorManager ?: (context.getSystemService(Context.SENSOR_SERVICE) as SensorManager)
            runCatching { manager.unregisterListener(activeListener) }
        }

        sensorManager = null
        listener = null
        enabled = false
    }

    fun statusMap(): Map<String, Any> {
        return mapOf(
            "enabled" to enabled,
            "threshold" to threshold,
            "lastShakeAt" to lastShakeAt,
            "shakeCount" to shakeCount
        )
    }
}
