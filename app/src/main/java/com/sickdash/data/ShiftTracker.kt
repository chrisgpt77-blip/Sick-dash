package com.sickdash.data

import kotlin.math.abs

/**
 * Watches gear changes and grades how well the revs were matched to the road speed once the
 * clutch came back out: 100 = engine speed exactly what the new gear needs.
 */
object ShiftTracker {
    class Shift(val from: Int, val to: Int, val at: Long, val rpm: Float, val target: Float, val score: Int) {
        val rating: String get() = when {
            score >= 90 -> "PERFECT"
            score >= 75 -> "CLEAN"
            score >= 55 -> "GOOD"
            score >= 30 -> "ROUGH"
            else -> "MISSED"
        }
        val downshift: Boolean get() = to < from
    }

    @Volatile var last: Shift? = null; private set
    @Volatile var count = 0; private set
    private val recent = ArrayDeque<Shift>()
    private var lastGear = 0
    private var pendingGear = 0
    private var pendingAt = 0L
    private var lastAt = 0L

    fun history(): List<Shift> = synchronized(this) { recent.toList() }

    @Synchronized
    fun reset() {
        last = null; count = 0; recent.clear(); lastGear = 0; pendingGear = 0
    }

    @Synchronized
    fun update(s: Snapshot, gear: Int?, cfg: DashConfig) {
        if (s.now <= lastAt) return
        lastAt = s.now
        if (gear == null || gear <= 0) return
        if (gear != lastGear && gear != pendingGear) {
            if (lastGear > 0) { pendingGear = gear; pendingAt = s.now } else lastGear = gear
            return
        }
        if (pendingGear == 0) return
        val clutch = s[Channel.CLUTCH]
        val engaged = if (clutch != null) clutch < 50f else s.now - pendingAt > 400
        if (!engaged && s.now - pendingAt < 4000) return
        val rpm = s[Channel.RPM] ?: return
        val speed = s[Channel.SPEED] ?: return
        val ratio = cfg.gearRatios.getOrNull(pendingGear - 1) ?: return
        val target = speed / 3.6f / cfg.tireCircumferenceM * 60f * ratio * cfg.finalDrive
        val err = if (target > 300f) abs(rpm - target) / target else 1f
        val score = (100f - err * 400f).coerceIn(0f, 100f).toInt()
        val shift = Shift(lastGear, pendingGear, s.now, rpm, target, score)
        last = shift
        count++
        recent.addLast(shift)
        while (recent.size > 20) recent.removeFirst()
        lastGear = pendingGear
        pendingGear = 0
    }
}
