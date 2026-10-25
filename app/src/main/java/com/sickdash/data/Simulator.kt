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
        var gear = 1
        var rpm = 850f
        var speed = 0f
        var coolant = 64f
        var oil = 58f
        var t = 0f
        var phase = 0 // 0 launch/pull, 1 brake + downshift, 2 corner, 3 idle at a stop
        var phaseTime = 0f
        var shiftTimer = 0f // > 0 while the clutch is in for a change
        var nextGear = 1
        val dt = 0.033f

        while (coroutineContext.isActive) {
            val c = cfg()
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
                        val accel = 8.5f * (c.gearRatios[gear - 1] / c.gearRatios[0]).coerceAtMost(1f)
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

            VehicleData.set(Channel.RPM, rpm)
            VehicleData.set(Channel.SPEED, speed)
            VehicleData.set(Channel.THROTTLE, throttle)
            VehicleData.set(Channel.BRAKE, brake)
            VehicleData.set(Channel.CLUTCH, if (clutchIn) 100f else 0f)
            VehicleData.set(Channel.GEAR, leverGear.toFloat())
            VehicleData.set(Channel.COOLANT, coolant)
            VehicleData.set(Channel.OIL_TEMP, oil)
            VehicleData.set(Channel.INTAKE_TEMP, 24f)
            VehicleData.set(Channel.BATTERY, 14.2f + 0.08f * sin(t))
            VehicleData.set(Channel.FUEL, 61f)
            VehicleData.set(Channel.STEERING, latG * 140f)
            VehicleData.set(Channel.LAT_G, latG)
            VehicleData.set(Channel.LON_G, lonG)
            VehicleData.set(Channel.TIRE_FL, 36f * 6.8948f)
            VehicleData.set(Channel.TIRE_FR, 36f * 6.8948f)
            VehicleData.set(Channel.TIRE_RL, 35f * 6.8948f)
            VehicleData.set(Channel.TIRE_RR, 35f * 6.8948f)
            VehicleData.countFrame()
            delay((dt * 1000).toLong())
        }
    }
}
