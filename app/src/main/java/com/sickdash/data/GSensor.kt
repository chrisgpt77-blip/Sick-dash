package com.sickdash.data

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Lateral / longitudinal g from the phone's own sensors, for cars whose CAN data has no
 * accelerometer. Works out the mount by itself: upright (screen facing driver) or flat.
 */
object GSensor : SensorEventListener {
    private var manager: SensorManager? = null
    private var thread: HandlerThread? = null
    private val gravity = FloatArray(3)
    private var hasGravity = false
    private var lat = 0f
    private var lon = 0f

    fun start(context: Context) {
        if (manager != null) return
        val sm = context.getSystemService(SensorManager::class.java) ?: return
        val lin = sm.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION) ?: return
        val grav = sm.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: return
        // on their own thread, so ~100 events a second don't queue in front of taps on the main thread
        val t = HandlerThread("g-sensor").also { it.start() }
        val h = Handler(t.looper)
        sm.registerListener(this, lin, SensorManager.SENSOR_DELAY_GAME, h)
        sm.registerListener(this, grav, SensorManager.SENSOR_DELAY_GAME, h)
        thread = t
        manager = sm
    }

    fun stop() {
        manager?.unregisterListener(this)
        manager = null
        thread?.quitSafely()
        thread = null
    }

    override fun onSensorChanged(e: SensorEvent) {
        when (e.sensor.type) {
            Sensor.TYPE_GRAVITY -> { System.arraycopy(e.values, 0, gravity, 0, 3); hasGravity = true }
            Sensor.TYPE_LINEAR_ACCELERATION -> if (hasGravity) update(e.values)
        }
    }

    private fun update(a: FloatArray) {
        val g = normalize(gravity) ?: return
        // Car-forward in device coordinates: -Z when upright facing the driver, +Y when lying flat.
        val fwdDevice = if (abs(g[2]) > 0.8f) floatArrayOf(0f, 1f, 0f) else floatArrayOf(0f, 0f, -1f)
        val d = dot(fwdDevice, g)
        val fwd = normalize(floatArrayOf(fwdDevice[0] - d * g[0], fwdDevice[1] - d * g[1], fwdDevice[2] - d * g[2])) ?: return
        val right = cross(fwd, g)
        val newLon = dot(a, fwd) / SensorManager.GRAVITY_EARTH
        val newLat = dot(a, right) / SensorManager.GRAVITY_EARTH
        lon += (newLon - lon) * 0.15f
        lat += (newLat - lat) * 0.15f
        val cfg = DashConfig.current
        if (cfg == null || (cfg.phoneGSensor && cfg.source != Source.DEMO)) {
            VehicleData.set(Channel.LAT_G, lat, kind = VehicleData.SRC_PHONE)
            VehicleData.set(Channel.LON_G, lon, kind = VehicleData.SRC_PHONE)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun dot(a: FloatArray, b: FloatArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
    private fun cross(a: FloatArray, b: FloatArray) = floatArrayOf(
        a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0],
    )
    private fun normalize(v: FloatArray): FloatArray? {
        val n = sqrt(dot(v, v))
        return if (n < 1e-3f) null else floatArrayOf(v[0] / n, v[1] / n, v[2] / n)
    }
}
