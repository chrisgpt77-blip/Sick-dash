package com.sickdash.data

import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * A fake back-road run so every page has something to show without a car: pulls through the
 * gears, brakes into a corner with heel-and-toe downshifts, and reports what a GR86's CAN bus
 * would (lever position, clutch, brake pressure, tyre pressures, temps).
 */
object Simulator {
    suspend fun run(cfg: () -> DashConfig) {
        VehicleData.setStatus(LinkState.CONNECTED, "Demo mode · simulated drive")
        val drive = Drive()
        val dt = 0.033f
        while (coroutineContext.isActive) {
            drive.step(cfg(), dt, System.currentTimeMillis())
            delay((dt * 1000).toLong())
        }
    }

    /** One simulated car; [step] advances it by [dt] seconds and writes every channel stamped [now]. */
    class Drive {
        private var gear = 1
        private var rpm = 850f
        private var speed = 0f
        private var coolant = 64f
        private var oil = 58f
        private var odometer = 8_421f
        private var t = 0f
        private var phase = 0 // 0 launch/pull, 1 brake + downshift, 2 corner, 3 idle at a stop
        private var phaseTime = 0f
        private var shiftTimer = 0f // > 0 while the clutch is in for a change
        private var nextGear = 1

        fun step(c: DashConfig, dt: Float, now: Long) {
            t += dt
            phaseTime += dt
            fun rpmFor(g: Int, kph: Float) = kph / 3.6f / c.tireCircumferenceM * 60f * c.gearRatios[g - 1] * c.finalDrive
            var throttle = 0f
            var brake = 0f
            var lonG = 0f
            var latG = 0f
            var clutchIn = shiftTimer > 0f

            when (phase) {
                0 -> {
                    throttle = if (clutchIn) 0f else 100f
                    if (!clutchIn) {
                        val vm = max(speed / 3.6f, 1f)
                        val accel = min(8.5f * (c.gearRatios[gear - 1] / c.gearRatios[0]).coerceAtMost(1f), 112_000f / (1360f * vm)) - 0.0003f * vm * vm
                        speed += accel * dt * 3.6f
                        lonG = accel / 9.81f
                        if (rpm >= c.shiftRpm + 200 && gear < 5) { nextGear = gear + 1; shiftTimer = 0.35f }
                    }
                    if (gear >= 4 && rpm > c.shiftRpm) { phase = 1; phaseTime = 0f }
                }
                1 -> {
                    brake = 70f
                    speed = max(0f, speed - 9.5f * dt * 3.6f)
                    lonG = -0.95f
                    if (!clutchIn && gear > 2 && rpmFor(gear - 1, speed) < c.shiftRpm - 1200) { nextGear = gear - 1; shiftTimer = 0.45f }
                    if (speed < 55f) { phase = 2; phaseTime = 0f }
                }
                2 -> {
                    throttle = 35f
                    latG = 0.9f * sin(phaseTime / 4f * Math.PI.toFloat())
                    if (phaseTime > 4f) { phase = if ((t / 60).toInt() % 2 == 0) 0 else 3; phaseTime = 0f }
                }
                else -> {
                    brake = if (speed > 0f) 40f else 10f
                    speed = max(0f, speed - 6f * dt * 3.6f)
                    lonG = if (speed > 0f) -0.6f else 0f
                    if (speed < 10f && gear > 1 && !clutchIn) { nextGear = 1; shiftTimer = 0.4f }
                    if (phaseTime > 6f) { phase = 0; phaseTime = 0f; gear = 1; shiftTimer = 0.5f; nextGear = 1 }
                }
            }

            // the change: lever through neutral, then blip (or drop) to the revs the new gear needs
            var leverGear = gear
            if (shiftTimer > 0f) {
                shiftTimer -= dt
                leverGear = if (shiftTimer > 0.15f) 0 else nextGear
                if (shiftTimer <= 0f) gear = nextGear
            }
            clutchIn = shiftTimer > 0f
            val target = if (speed < 6f) 850f else rpmFor(if (clutchIn) nextGear else gear, speed)
            rpm = when {
                speed < 6f && phase != 0 -> 820f + 25f * sin(t * 7)
                phase == 0 && speed < 14f -> max(3600f, target)
                clutchIn -> rpm + (target * (0.97f + 0.06f * sin(t * 3)) - rpm) * 0.18f
                else -> target
            }
            if (phase == 0 && speed < 14f) speed += 0.5f
            rpm = min(rpm, c.maxRpm.toFloat())
            coolant = min(92f, coolant + dt * 0.4f)
            oil = min(104f, oil + dt * 0.25f)

            val steer = -latG * 140f   // a right turn (lat +) is steering right (−)
            val v = speed / 3.6f
            // yaw from the corner, with a touch of oversteer on the exit
            val yaw = if (v > 3f) -latG * 9.81f / v * 57.3f * (1f + if (phase == 2 && phaseTime > 2.5f) 0.25f else 0f) else 0f
            // wheel speeds: outside wheels faster in the corner, rears a little ahead under full throttle
            val split = yaw / 57.3f * 0.77f * 3.6f
            val squat = if (phase == 0 && !clutchIn && gear <= 2) 1.03f else 1f
            odometer += speed / 3600f * dt
            fun put(ch: Channel, x: Float) = VehicleData.set(ch, x, now, kind = VehicleData.SRC_DEMO)
            put(Channel.RPM, rpm)
            put(Channel.SPEED, speed)
            put(Channel.THROTTLE, if (phase == 1 && clutchIn) 45f else throttle) // the heel-toe blip
            put(Channel.BRAKE, brake)
            put(Channel.BRAKE_LIGHT, if (brake > 5f) 1f else 0f)
            put(Channel.CLUTCH, if (clutchIn) 100f else 0f)
            put(Channel.GEAR, leverGear.toFloat())
            put(Channel.NEUTRAL, if (leverGear == 0) 1f else 0f)
            put(Channel.REVERSE, 0f)
            put(Channel.COOLANT, coolant)
            put(Channel.OIL_TEMP, oil)
            put(Channel.INTAKE_TEMP, 24f)
            put(Channel.BATTERY, 14.2f + 0.08f * sin(t))
            put(Channel.FUEL, 61f)
            put(Channel.STEERING, steer)
            put(Channel.YAW_RATE, yaw)
            put(Channel.LAT_G, latG)
            put(Channel.LON_G, lonG)
            put(Channel.WHEEL_FL, max(0f, speed - split / 2))
            put(Channel.WHEEL_FR, max(0f, speed + split / 2))
            put(Channel.WHEEL_RL, max(0f, speed * squat - split * 0.45f))
            put(Channel.WHEEL_RR, max(0f, speed * squat + split * 0.45f))
            put(Channel.TIRE_FL, 36f * 6.8948f)
            put(Channel.TIRE_FR, 36f * 6.8948f)
            put(Channel.TIRE_RL, 35f * 6.8948f)
            put(Channel.TIRE_RR, 35f * 6.8948f)
            put(Channel.DOOR_LOCK, if (speed > 15f || phase != 3) 1f else 0f)
            put(Channel.DOORS_OPEN, 0f)
            put(Channel.DOOR_DRIVER, 0f)
            put(Channel.DOOR_PASS, 0f)
            put(Channel.HATCH, 0f)
            put(Channel.TURN_SIGNAL, if (phase == 2 && phaseTime < 1.2f && (t * 2.5f).toInt() % 2 == 0) 2f else 0f)
            put(Channel.WIPERS, 0f)
            put(Channel.BELT, 1f)
            put(Channel.HEADLIGHTS, 2f)
            put(Channel.PARK_BRAKE, 0f)
            put(Channel.VDC_OFF, 0f)
            put(Channel.TRACK_MODE, 1f)
            put(Channel.CRUISE, 0f)
            put(Channel.ODOMETER, odometer)
            put(Channel.LOAD, 20f + throttle * 0.75f)
            put(Channel.TIMING, if (throttle > 90f) 18f + rpm / 1000f else 28f + 6f * sin(t))
            put(Channel.MAF, rpm / 7500f * (15f + throttle * 1.6f))
            put(Channel.LAMBDA, if (throttle > 90f) 0.86f else 1.0f + 0.01f * sin(t * 5))
            put(Channel.STFT, 1.6f * sin(t * 2))
            put(Channel.LTFT, -2.3f)
            VehicleData.countFrame(now)
        }
    }
}
