package com.pedro.heartratewatch.shared

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

/**
 * Altitude from the device's barometer (air pressure), which is far steadier than GPS height for working out
 * how much a route climbs: GPS altitude can be off by 10 to 20 meters, pressure changes track a few meters of
 * climb. The number is only good relative to itself (the weather moves the pressure too), which is all a route needs.
 * Readings are smoothed a little. [altitudeMeters] is null until the first reading, or if there is no barometer.
 */
class BarometerSource(context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(SensorManager::class.java)

    @Volatile
    var altitudeMeters: Double? = null
        private set

    /** Returns false if the device has no barometer or registration was refused. */
    fun start(): Boolean {
        val sensor = sensorManager?.getDefaultSensor(Sensor.TYPE_PRESSURE, true)
            ?: sensorManager?.getDefaultSensor(Sensor.TYPE_PRESSURE)
            ?: return false
        return sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
    }

    fun stop() {
        sensorManager?.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        val hectopascals = event.values.firstOrNull() ?: return
        if (hectopascals <= 0f) return
        val raw = SensorManager.getAltitude(SensorManager.PRESSURE_STANDARD_ATMOSPHERE, hectopascals).toDouble()
        val previous = altitudeMeters
        altitudeMeters = if (previous == null) raw else previous + SMOOTHING * (raw - previous)
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit

    private companion object {
        /** How much of each new reading is taken in: low enough to smooth out noise, high enough to follow a climb. */
        const val SMOOTHING = 0.25
    }
}
