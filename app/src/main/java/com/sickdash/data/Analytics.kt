package com.sickdash.data

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.tan

/**
 * Everything the HUD works out from the raw channels, fed once per rendered frame. Uses the
 * snapshot clock, so the phone screen and the car screen both feeding it is harmless.
 *
 * - Clutch: the gearbox input speed implied by road speed and gear, the engine's difference
 *   from it, slip %, and a running friction-heat estimate.
 * - VDC: the yaw rate the steering asks for (bicycle model) against the yaw the car does, as an
 *   understeer / oversteer balance.
 * - Dyno: wheel power from acceleration (mass × a + drag + rolling) × speed, binned by rpm per pull.
 * - Timers: 0-60 / 0-100, 0-100 mph, 1/8 and 1/4 mile with trap speed, and 60-0 braking.
 * - Signals: how many channels are mapped and how many are live and plausible.
 */
object Analytics {
    // ---- clutch
    @Volatile var gearboxRpm: Float? = null; private set
    @Volatile var slipRpm = 0f; private set
    @Volatile var slipPct = 0f; private set
    @Volatile var clutchHeatC = 30f; private set
    @Volatile var slipEvents = 0; private set
    val slipHistory = FloatArray(300) { Float.NaN }   // last 30 s at 10 Hz
    @Volatile var slipHead = 0; private set

    // ---- VDC
    @Volatile var yawActual: Float? = null; private set
    @Volatile var yawExpected: Float? = null; private set
    @Volatile var balance = 0f; private set          // deg/s; + oversteer, - understeer
    @Volatile var oversteerTimeS = 0f; private set
    /** Body slip angle estimate (deg, + nose pointing left of travel); needs a CAN yaw rate. */
    @Volatile var slipAngle: Float? = null; private set
    /** Steering is wound against the way the car is rotating. */
    @Volatile var counterSteer = false; private set
    val balanceHistory = FloatArray(200) { Float.NaN } // last 10 s at 20 Hz
    @Volatile var balanceHead = 0; private set

    // ---- dyno
    @Volatile var powerKw = 0f; private set
    @Volatile var pulling = false; private set
    val pullKw = FloatArray(90)      // per 100 rpm bin, current/last pull
    val bestKw = FloatArray(90)
    @Volatile var peakKw = 0f; private set
    @Volatile var peakKwRpm = 0; private set
    @Volatile var peakNm = 0f; private set
    @Volatile var peakNmRpm = 0; private set

    // ---- timers
    class Run(val label: String) { @Volatile var best: Float? = null; @Volatile var last: Float? = null }
    val zeroTo60 = Run("0-60 MPH")
    val zeroTo100k = Run("0-100 KM/H")
    val zeroTo100m = Run("0-100 MPH")
    val eighth = Run("1/8 MILE")
    val quarter = Run("1/4 MILE")
    @Volatile var quarterTrapKph: Float? = null; private set
    @Volatile var brake60m: Float? = null; private set
    @Volatile var brake60Best: Float? = null; private set
    @Volatile var runState = "READY"; private set
    @Volatile var runElapsed = 0f; private set
    @Volatile var runDistanceM = 0f; private set

    // ---- signals
    @Volatile var liveSignals = 0; private set
    @Volatile var verifiedSignals = 0; private set
    val rateHz = FloatArray(Channel.entries.size)

    private var last = 0L
    private var accel = 0f
    private var lastSpeed = -1f
    private var slipStart = 0L
    private var slipFlagged = false
    private var lastSlipSample = 0L
    private var lastBalSample = 0L
    private var lastRateAt = 0L
    private val lastUpdates = LongArray(Channel.entries.size)
    private var pullEndAt = 0L
    private var pullOnSince = 0L
    private var pullMinRpm = 0
    private var pullMaxRpm = 0
    private var runStart = 0L
    private var runMaxKph = 0f
    private var brakeArmed = false
    private var brakeDist = 0f
    private var brakeMin = 0f
    private var engineWasOff = true
    private var lastOverRev = 0L
    private var lastCold = 0L
    private var lowFuelWarned = false
    private var lastLock: Float? = null
    private var launchArmedShown = 0L

    const val MASS_KG = 1360f       // GR86 ≈ 1,280 kg + driver
    private const val CDA = 0.61f
    private const val CRR = 0.012f

    @Synchronized
    fun reset() {
        slipEvents = 0; clutchHeatC = 30f; slipHistory.fill(Float.NaN); balanceHistory.fill(Float.NaN)
        oversteerTimeS = 0f; pullKw.fill(0f); bestKw.fill(0f); peakKw = 0f; peakNm = 0f
        for (r in listOf(zeroTo60, zeroTo100k, zeroTo100m, eighth, quarter)) { r.best = null; r.last = null }
        quarterTrapKph = null; brake60m = null; brake60Best = null; runState = "READY"
    }

    @Synchronized
    fun update(s: Snapshot, cfg: DashConfig, gear: Int?) {
        val now = s.now
        if (now <= last) return
        val dt = if (last == 0L) 0.033f else ((now - last) / 1000f).coerceIn(0.001f, 0.25f)
        last = now

        val rpm = s[Channel.RPM]
        val kph = s[Channel.SPEED]
        val v = (kph ?: 0f) / 3.6f
        val throttle = s[Channel.THROTTLE] ?: 0f
        val clutchIn = (s[Channel.CLUTCH] ?: 0f) >= 50f

        // smoothed longitudinal acceleration from road speed
        if (kph != null) {
            if (lastSpeed >= 0f) accel += ((kph - lastSpeed) / 3.6f / dt - accel) * min(1f, dt * 6f)
            lastSpeed = kph
        }

        clutch(now, dt, rpm, kph, gear, clutchIn, throttle, s, cfg)
        vdc(now, dt, s, v)
        dyno(now, rpm, v, throttle, clutchIn, gear, cfg)
        timers(now, dt, kph, cfg)
        signals(now, s)
        events(now, s, rpm, kph, gear, clutchIn, cfg)
    }

    private fun clutch(now: Long, dt: Float, rpm: Float?, kph: Float?, gear: Int?, clutchIn: Boolean, throttle: Float, s: Snapshot, cfg: DashConfig) {
        val ratio = gear?.takeIf { it > 0 }?.let { cfg.gearRatios.getOrNull(it - 1) }
        gearboxRpm = if (ratio != null && kph != null) kph / 3.6f / cfg.tireCircumferenceM * 60f * ratio * cfg.finalDrive else null
        val gb = gearboxRpm
        val slipping = rpm != null && gb != null && !clutchIn && (kph ?: 0f) > 8f && gb > 600f
        if (slipping) {
            slipRpm = rpm!! - gb!!
            slipPct = slipRpm / gb * 100f
        } else {
            slipRpm = 0f
            slipPct = 0f
        }
        // friction heat: grows with slip speed and load, sheds toward ambient
        val ambient = s[Channel.INTAKE_TEMP] ?: 25f
        if (slipping && abs(slipPct) > 2.5f) clutchHeatC += abs(slipRpm) / 1000f * (0.4f + throttle / 100f) * 9f * dt
        clutchHeatC -= (clutchHeatC - ambient) * 0.015f * dt
        if (slipping && slipPct > 6f) {
            if (slipStart == 0L) slipStart = now
            if (!slipFlagged && now - slipStart > 400) {
                slipFlagged = true
                slipEvents++
                Events.emit(Events.Kind.SLIP, "CLUTCH SLIP", "%.0f%% · %,d RPM OVER".format(slipPct, slipRpm.roundToInt()), strong = true, now = now)
            }
        } else { slipStart = 0L; slipFlagged = false }
        if (now - lastSlipSample >= 100) {
            lastSlipSample = now
            slipHistory[slipHead] = if (slipping) slipPct else Float.NaN
            slipHead = (slipHead + 1) % slipHistory.size
        }
    }

    private fun vdc(now: Long, dt: Float, s: Snapshot, v: Float) {
        val steer = s[Channel.STEERING]
        val yawCan = s[Channel.YAW_RATE]
        val lat = s[Channel.LAT_G, 1000]
        yawActual = yawCan ?: if (lat != null && v > 3f) (-lat * 9.81f / v * 180f / PI.toFloat()) else null
        yawExpected = if (steer != null && v > 3f) {
            val delta = steer / 13.5f * PI.toFloat() / 180f
            (v * tan(delta) / (2.575f * (1f + 0.0025f * v * v))) * 180f / PI.toFloat()
        } else null
        val a = yawActual
        val e = yawExpected
        balance = if (a != null && e != null && v > 5f) {
            // positive when the car rotates more than asked (oversteer), negative when less;
            // rotating against the steering (a counter-steered slide) is all oversteer
            if (abs(a) < 0.5f || abs(e) < 0.5f || kotlin.math.sign(a) == kotlin.math.sign(e)) abs(a) - abs(e)
            else abs(a) + abs(e)
        } else 0f
        if (balance > 4f) oversteerTimeS += dt
        // β' = ay / v - r. Only meaningful with a measured yaw rate; leaks back to 0 so drift can't build up.
        slipAngle = if (yawCan != null && lat != null && v > 5f) {
            val rate = (-lat * 9.81f / v) - yawCan * PI.toFloat() / 180f
            val b = (slipAngle ?: 0f) * PI.toFloat() / 180f
            ((b + rate * dt) * (1f - 0.5f * dt)).coerceIn(-0.8f, 0.8f) * 180f / PI.toFloat()
        } else null
        counterSteer = a != null && steer != null && v > 5f && abs(a) > 4f && abs(steer) > 8f && kotlin.math.sign(a) != kotlin.math.sign(steer)
        if (now - lastBalSample >= 50) {
            lastBalSample = now
            balanceHistory[balanceHead] = if (a != null && e != null && v > 5f) balance else Float.NaN
            balanceHead = (balanceHead + 1) % balanceHistory.size
        }
    }

    private fun dyno(now: Long, rpm: Float?, v: Float, throttle: Float, clutchIn: Boolean, gear: Int?, cfg: DashConfig) {
        val force = MASS_KG * accel + 0.5f * 1.2f * CDA * v * v + CRR * MASS_KG * 9.81f
        powerKw = max(0f, force * v / 1000f)
        val pullOn = rpm != null && throttle >= 90f && !clutchIn && (gear ?: 0) > 0 && v > 4f
        if (pullOn && pullOnSince == 0L) pullOnSince = now
        if (!pullOn) pullOnSince = 0L
        // the smoothed acceleration needs a moment to settle after a shift before it means anything
        if (pullOn && now - pullOnSince >= 400) {
            if (!pulling) { pullKw.fill(0f); pullMinRpm = rpm!!.toInt(); pullMaxRpm = rpm.toInt(); pulling = true }
            val bin = (rpm!! / 100f).toInt().coerceIn(0, pullKw.size - 1)
            pullKw[bin] = max(pullKw[bin], powerKw)
            pullMinRpm = min(pullMinRpm, rpm.toInt()); pullMaxRpm = max(pullMaxRpm, rpm.toInt())
            pullEndAt = 0L
        } else if (pulling) {
            if (pullEndAt == 0L) pullEndAt = now
            if (now - pullEndAt > 300) {
                pulling = false
                if (pullMaxRpm - pullMinRpm > 1500) {
                    for (i in pullKw.indices) bestKw[i] = max(bestKw[i], pullKw[i])
                    var pk = 0f; var pkR = 0; var tq = 0f; var tqR = 0
                    for (i in bestKw.indices) {
                        if (bestKw[i] > pk) { pk = bestKw[i]; pkR = i * 100 }
                        val nm = if (i > 10) bestKw[i] * 1000f / (i * 100f * 2f * PI.toFloat() / 60f) else 0f
                        if (nm > tq) { tq = nm; tqR = i * 100 }
                    }
                    peakKw = pk; peakKwRpm = pkR; peakNm = tq; peakNmRpm = tqR
                    Events.emit(Events.Kind.RESULT, "DYNO PULL", "%d WHP @ %,d RPM".format((pk * 1.341f).roundToInt(), pkR), now = now)
                }
            }
        }
    }

    private var lastStandstill = true
    private val marks = HashSet<Run>()

    /** Records a split the first time this run reaches it. Returns true when it was new. */
    private fun mark(r: Run): Boolean {
        if (!marks.add(r)) return false
        r.last = runElapsed
        if (r.best == null || runElapsed < r.best!!) r.best = runElapsed
        return true
    }

    private fun timers(now: Long, dt: Float, kph: Float?, cfg: DashConfig) {
        if (kph == null) return
        if (runStart == 0L) {
            if (kph < 1f) runState = "READY"
            // a run starts the moment the car moves off from a standstill
            if (kph >= 1f && lastStandstill) {
                runStart = now; runDistanceM = 0f; runMaxKph = 0f; runElapsed = 0f; marks.clear(); runState = "RUNNING"
            }
        } else {
            runElapsed = (now - runStart) / 1000f
            runDistanceM += kph / 3.6f * dt
            runMaxKph = max(runMaxKph, kph)
            if (kph >= 96.56f && mark(zeroTo60)) {
                Events.emit(Events.Kind.RESULT, "0-60 MPH", "%.2f S%s".format(runElapsed,
                    if (zeroTo60.best == runElapsed) " · BEST" else ""), strong = true, now = now)
            }
            if (kph >= 100f) mark(zeroTo100k)
            if (kph >= 160.93f) mark(zeroTo100m)
            if (runDistanceM >= 201.17f) mark(eighth)
            if (runDistanceM >= 402.34f && mark(quarter)) {
                quarterTrapKph = kph
                Events.emit(Events.Kind.RESULT, "1/4 MILE", "%.2f S @ %d %s".format(runElapsed,
                    (if (cfg.imperial) kph * 0.621371f else kph).roundToInt(), if (cfg.imperial) "MPH" else "KM/H"), strong = true, now = now)
            }
            // the run is over once the car slows meaningfully, or after 30 s
            if (kph < runMaxKph - 8f || runElapsed > 30f) { runStart = 0L; runState = "DONE" }
        }
        // 60-0 braking distance: measured from the moment the car drops back through 60 mph
        if (!brakeArmed && kph >= 96.56f) { brakeArmed = true; brakeDist = 0f; brakeMin = kph }
        if (brakeArmed) {
            if (kph >= 96.56f) { brakeDist = 0f; brakeMin = kph } else brakeDist += kph / 3.6f * dt
            brakeMin = min(brakeMin, kph)
            if (kph > brakeMin + 8f) brakeArmed = false   // sped up again before stopping: not a stop
            if (kph < 1f) {
                if (brakeDist > 5f) {
                    brake60m = brakeDist
                    if (brake60Best == null || brakeDist < brake60Best!!) brake60Best = brakeDist
                }
                brakeArmed = false
            }
        }
        lastStandstill = kph < 1f
    }

    private fun signals(now: Long, s: Snapshot) {
        var live = 0
        var ok = 0
        for (c in Channel.entries) {
            val v = s[c, 3000] ?: continue
            live++
            if (!ChannelInfo.needsCheck(c, v, s)) ok++
        }
        liveSignals = live
        verifiedSignals = ok
        if (now - lastRateAt >= 1000) {
            val span = if (lastRateAt == 0L) 1f else (now - lastRateAt) / 1000f
            for (c in Channel.entries) {
                val n = VehicleData.updates[c.ordinal]
                rateHz[c.ordinal] = (n - lastUpdates[c.ordinal]) / span
                lastUpdates[c.ordinal] = n
            }
            lastRateAt = now
        }
    }

    private fun events(now: Long, s: Snapshot, rpm: Float?, kph: Float?, gear: Int?, clutchIn: Boolean, cfg: DashConfig) {
        if (rpm != null) {
            if (engineWasOff && rpm > 450f) Events.emit(Events.Kind.START, "ENGINE START", "${cfg.car.engine} · %,d RPM".format(rpm.roundToInt()), strong = true, now = now)
            engineWasOff = rpm < 150f
            if (rpm > cfg.redline + 150 && now - lastOverRev > 3000) {
                lastOverRev = now
                Events.emit(Events.Kind.OVER_REV, "OVER-REV", "%,d RPM".format(rpm.roundToInt()), strong = true, now = now)
            }
            val coolant = s[Channel.COOLANT]
            if (coolant != null && coolant < 60f && rpm > 4500f && now - lastCold > 60_000) {
                lastCold = now
                Events.emit(Events.Kind.WARNING, "ENGINE COLD", "OIL AND COOLANT STILL WARMING UP", now = now)
            }
            if ((kph ?: 99f) < 1f && rpm > 3000f && clutchIn && (gear ?: 0) >= 1 && now - launchArmedShown > 8000) {
                launchArmedShown = now
                Events.emit(Events.Kind.LAUNCH, "LAUNCH ARMED", "%,d RPM · TIMERS READY".format(rpm.roundToInt()), now = now)
            }
        }
        val fuel = s[Channel.FUEL]
        if (fuel != null && fuel < 12f && !lowFuelWarned) { lowFuelWarned = true; Events.emit(Events.Kind.WARNING, "LOW FUEL", "${fuel.roundToInt()}% LEFT", now = now) }
        val lock = s[Channel.DOOR_LOCK, 10_000]
        if (lock != null && lastLock != null && (lock >= 0.5f) != (lastLock!! >= 0.5f)) {
            Events.emit(Events.Kind.LOCK, if (lock >= 0.5f) "LOCKED" else "UNLOCKED", "", now = now)
        }
        if (lock != null) lastLock = lock
    }
}
