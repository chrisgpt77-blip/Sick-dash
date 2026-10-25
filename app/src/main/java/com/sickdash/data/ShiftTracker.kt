package com.sickdash.data

import kotlin.math.abs
import kotlin.math.max

/**
 * Watches gear changes and grades how well the revs were matched to the road speed once the
 * clutch came back out: 100 = engine speed exactly what the new gear needs. Also notices the
 * technique: a throttle blip, heel-and-toe (brake and throttle together) and double-clutching
 * (clutch let out in neutral between the gears).
 */
object ShiftTracker {
    class Shift(
        val from: Int, val to: Int, val at: Long, val rpm: Float, val target: Float, val score: Int,
        val blip: Boolean, val heelToe: Boolean, val doubleClutch: Boolean,
    ) {
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
    @Volatile var heelToeCount = 0; private set
    @Volatile var blipCount = 0; private set
    @Volatile var doubleClutchCount = 0; private set
    @Volatile var downshiftCount = 0; private set
    private val recent = ArrayDeque<Shift>()
    private var lastGear = 0
    private var pendingGear = 0
    private var pendingAt = 0L
    private var lastAt = 0L
    // what happened between leaving the old gear and engaging the new one
    private var inWindow = false
    private var maxThrottle = 0f
    private var overlap = false
    private var clutchOutInNeutral = false

    fun history(): List<Shift> = synchronized(this) { recent.toList() }

    @Synchronized
    fun reset() {
        last = null; count = 0; heelToeCount = 0; blipCount = 0; doubleClutchCount = 0; downshiftCount = 0
        recent.clear(); lastGear = 0; pendingGear = 0; inWindow = false
    }

    @Synchronized
    fun update(s: Snapshot, gear: Int?, cfg: DashConfig) {
        if (s.now <= lastAt) return
        lastAt = s.now
        val throttle = s[Channel.THROTTLE] ?: 0f
        val brake = s[Channel.BRAKE] ?: 0f
        val clutch = s[Channel.CLUTCH]
        val clutchIn = (clutch ?: 0f) >= 50f

        // a shift window opens when the clutch goes in (or the lever leaves a gear) while in gear
        if (!inWindow && lastGear > 0 && (clutchIn || gear == 0)) {
            inWindow = true; maxThrottle = 0f; overlap = false; clutchOutInNeutral = false
        }
        if (inWindow) {
            maxThrottle = max(maxThrottle, throttle)
            if (brake > 8f && throttle > 10f) overlap = true
            if (gear == 0 && clutch != null && !clutchIn) clutchOutInNeutral = true
        }

        if (gear == null || gear <= 0) return
        if (gear != lastGear && gear != pendingGear) {
            if (lastGear > 0) { pendingGear = gear; pendingAt = s.now } else lastGear = gear
            return
        }
        if (pendingGear == 0) {
            if (inWindow && !clutchIn && gear == lastGear) inWindow = false   // clutch dipped, no change
            return
        }
        val engaged = if (clutch != null) !clutchIn else s.now - pendingAt > 400
        if (!engaged && s.now - pendingAt < 4000) return
        val rpm = s[Channel.RPM] ?: return
        val speed = s[Channel.SPEED] ?: return
        val ratio = cfg.gearRatios.getOrNull(pendingGear - 1) ?: return
        val target = speed / 3.6f / cfg.tireCircumferenceM * 60f * ratio * cfg.finalDrive
        val err = if (target > 300f) abs(rpm - target) / target else 1f
        val score = (100f - err * 400f).coerceIn(0f, 100f).toInt()
        val down = pendingGear < lastGear
        val blip = down && maxThrottle > 15f
        val heelToe = down && overlap
        val dc = down && clutchOutInNeutral
        val shift = Shift(lastGear, pendingGear, s.now, rpm, target, score, blip, heelToe, dc)
        last = shift
        count++
        if (down) downshiftCount++
        if (blip) blipCount++
        if (heelToe) heelToeCount++
        if (dc) doubleClutchCount++
        recent.addLast(shift)
        while (recent.size > 20) recent.removeFirst()
        lastGear = pendingGear
        pendingGear = 0
        inWindow = false
        val tech = listOfNotNull(if (heelToe) "HEEL-TOE" else null, if (dc) "DOUBLE CLUTCH" else null, if (blip && !heelToe) "BLIP" else null)
        Events.emit(Events.Kind.SHIFT, "${shift.from} → ${shift.to} · ${shift.rating}",
            "REV MATCH $score / 100" + if (tech.isEmpty()) "" else " · " + tech.joinToString(" · "), strong = score >= 90, now = s.now)
    }
}
