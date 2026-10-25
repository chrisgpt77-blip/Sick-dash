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

    private var standingSince = 0L
    private var armed = false
    private var lastAt = 0L

    @Synchronized
    fun reset() {
        bestLaunchMs = null; lastLaunchMs = null; topKph = 0f
        peakLatG = 0f; peakBrakeG = 0f; peakAccelG = 0f
        distanceKm = 0.0; startedAt = 0L; armed = false; lastAt = 0L
    }

    /** Peak combined g so far. */
    val peakG: Float get() = max(peakLatG, max(peakBrakeG, peakAccelG))

    @Synchronized
    fun update(s: Snapshot, imperial: Boolean) {
        val now = s.now
        if (now <= lastAt) return
        val speed = s[Channel.SPEED, 1500]
        if (speed != null) {
            if (startedAt == 0L) startedAt = now
            if (lastAt != 0L) distanceKm += speed * (now - lastAt) / 3_600_000.0
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
}
