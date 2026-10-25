package com.sickdash.data

import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Fake 370Z laps so the dash can be tried without a car. */
object Simulator {
    suspend fun run(cfg: () -> DashConfig) {
        VehicleData.setStatus(LinkState.CONNECTED, "Demo mode · simulated VQ37VHR")
        var gear = 1
        var rpm = 900f
        var speed = 0f
        var coolant = 62f
        var oil = 55f
        var t = 0f
        var phase = 0 // 0 accelerate, 1 brake, 2 cruise
        var phaseTime = 0f
        val dt = 0.033f
        while (coroutineContext.isActive) {
            val c = cfg()
            t += dt
            phaseTime += dt
            val ratio = c.gearRatios[gear - 1] * c.finalDrive
            val rpmPerKph = ratio / (c.tireCircumferenceM * 3.6f) * 60f
            var throttle = 0f
            var lonG = 0f
            when (phase) {
                0 -> {
                    throttle = 100f
                    val accel = 9f * (c.gearRatios[gear - 1] / c.gearRatios[0]) * (0.6f + 0.4f * min(rpm, 6000f) / 6000f)
                    speed += accel * dt * 3.6f
                    lonG = accel / 9.81f
                    if (rpm >= c.shiftRpm + 150 && gear < c.gearRatios.size) gear++
                    if (speed > 210f || (gear >= 5 && phaseTime > 14f)) { phase = 1; phaseTime = 0f }
                }
                1 -> {
                    speed = max(0f, speed - 32f * dt * 3.6f)
                    lonG = -1.05f
                    while (gear > 1 && speed * (c.gearRatios[gear - 2] * c.finalDrive) /
                        (c.tireCircumferenceM * 3.6f) * 60f < c.shiftRpm - 1500) gear--
                    if (speed < 40f) { phase = 2; phaseTime = 0f }
                }
                else -> {
                    throttle = 18f
                    if (phaseTime > 5f) { phase = 0; phaseTime = 0f; gear = max(1, gear - 1) }
                }
            }
            val wheelRpm = speed * (c.gearRatios[gear - 1] * c.finalDrive) / (c.tireCircumferenceM * 3.6f) * 60f
            rpm = if (speed < 8f && phase != 0) 850f + 30f * sin(t * 7) else max(900f, wheelRpm)
            if (phase == 0 && speed < 15f) { rpm = max(rpm, 3800f); speed += 0.6f }
            rpm = min(rpm, c.maxRpm.toFloat())
            coolant = min(91f, coolant + dt * 0.4f)
            oil = min(108f, oil + dt * 0.25f)
            val latG = 0.85f * sin(t * 0.45f) * if (phase == 2) 1.2f else 0.3f

            VehicleData.set(Channel.RPM, rpm)
            VehicleData.set(Channel.SPEED, speed)
            VehicleData.set(Channel.THROTTLE, throttle)
            VehicleData.set(Channel.COOLANT, coolant)
            VehicleData.set(Channel.OIL_TEMP, oil)
            VehicleData.set(Channel.INTAKE_TEMP, 31f)
            VehicleData.set(Channel.BATTERY, 14.1f + 0.1f * sin(t))
            VehicleData.set(Channel.FUEL, 64f)
            VehicleData.set(Channel.LAT_G, latG)
            VehicleData.set(Channel.LON_G, lonG)
            VehicleData.set(Channel.STEERING, latG * 90f)
            VehicleData.countFrame()
            delay((dt * 1000).toLong())
        }
    }
}
