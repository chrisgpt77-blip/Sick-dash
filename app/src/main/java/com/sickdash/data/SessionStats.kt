package com.sickdash.data

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * What this drive has amounted to: best 0-60 (or 0-100 km/h), top speed, peak g, distance.
 * Fed once per rendered frame; the timing uses the snapshot clock, so being fed by both the
 * phone and the car screen at once is harmless.
 */
object SessionStats {
    @Volatile var bestLaunchMs: Long? = null; private set
    @Volatile var lastLaunchMs: Long? = null; private set
    @Volatile var topKph = 0f; private set
    @Volatile var peakLatG = 0f; private set
    @Volatile var peakBrakeG = 0f; private set
    @Volatile var peakAccelG = 0f; private set
    @Volatile var distanceKm = 0.0; private set
    @Volatile var startedAt = 0L; private set
    @Volatile var movingMs = 0L; private set
    @Volatile var maxRpm = 0f; private set
    /** Fuel burned this drive (litres): from mass airflow when the car reports it, else from the level dropping. */
    @Volatile var fuelUsedL = 0f; private set
    /** Instantaneous economy from mass airflow, L/100 km; null without MAF or when stopped. */
    @Volatile var nowL100: Float? = null; private set
    private var fuelStartPct = -1f
    private var mafFuelL = 0f

    private var standingSince = 0L
    private var armed = false
    private var lastAt = 0L

    @Synchronized
    fun reset() {
        bestLaunchMs = null; lastLaunchMs = null; topKph = 0f
        peakLatG = 0f; peakBrakeG = 0f; peakAccelG = 0f
        distanceKm = 0.0; startedAt = 0L; armed = false; lastAt = 0L
        movingMs = 0L; maxRpm = 0f; fuelUsedL = 0f; nowL100 = null; fuelStartPct = -1f; mafFuelL = 0f
    }

    /** Peak combined g so far. */
    val peakG: Float get() = max(peakLatG, max(peakBrakeG, peakAccelG))

    @Synchronized
    fun update(s: Snapshot, cfg: DashConfig) {
        val imperial = cfg.imperial
        val now = s.now
        if (now <= lastAt) return
        val speed = s[Channel.SPEED, 1500]
        s[Channel.RPM]?.let { maxRpm = max(maxRpm, it) }
        // fuel: petrol at a 14.7 air-fuel ratio and 0.745 kg/L
        val maf = s[Channel.MAF, 3000]
        val dtS = if (lastAt == 0L) 0f else (now - lastAt) / 1000f
        if (maf != null) {
            val lps = maf / 14.7f / 745f
            mafFuelL += lps * dtS
            nowL100 = if (speed != null && speed > 5f) lps * 3600f / speed * 100f else null
        } else nowL100 = null
        s[Channel.FUEL]?.let { pct ->
            if (fuelStartPct < 0f) fuelStartPct = pct
            if (pct > fuelStartPct + 5f) fuelStartPct = pct   // filled up mid-drive
        }
        val fromLevel = s[Channel.FUEL]?.let { ((fuelStartPct - it) / 100f * cfg.car.tankL).coerceAtLeast(0f) } ?: 0f
        fuelUsedL = if (mafFuelL > 0f) mafFuelL else fromLevel
        if (speed != null) {
            if (startedAt == 0L) startedAt = now
            if (lastAt != 0L) distanceKm += speed * (now - lastAt) / 3_600_000.0
            if (speed >= 1f && lastAt != 0L) movingMs += now - lastAt
            topKph = max(topKph, speed)
            val target = if (imperial) 96.56f else 100f
            if (speed < 1f) { standingSince = now; armed = true }
            else if (armed && speed >= target) {
                val t = now - standingSince
                if (t in 1_500..30_000) {
                    lastLaunchMs = t
                    if (bestLaunchMs == null || t < bestLaunchMs!!) bestLaunchMs = t
                }
                armed = false
            }
        }
        val lat = s[Channel.LAT_G, 1000]
        val lon = s[Channel.LON_G, 1000]
        if (lat != null && lon != null && sqrt(lat * lat + lon * lon) < 2.5f) {
            peakLatG = max(peakLatG, abs(lat))
            peakBrakeG = max(peakBrakeG, -lon)
            peakAccelG = max(peakAccelG, lon)
        }
        lastAt = now
    }

    /** Average economy this drive (L/100 km), once there's enough distance and fuel to say. */
    val avgL100: Float? get() = if (distanceKm > 2.0 && fuelUsedL > 0.2f) (fuelUsedL / distanceKm * 100.0).toFloat() else null
}
