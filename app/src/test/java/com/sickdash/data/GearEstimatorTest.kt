package com.sickdash.data

import org.junit.Assert.assertEquals
import org.junit.Test

class GearEstimatorTest {
    private val cfg = DashConfig(
        source = Source.DEMO, btAddress = "", host = "", port = 0, usbBaud = 0, elmMode = com.sickdash.can.ElmMode.AUTO,
        imperial = false, themeKey = "akasaka", redline = 7500, shiftRpm = 7000, maxRpm = 8000,
        gearRatios = DashConfig.parseRatios(DashConfig.DEFAULT_RATIOS), finalDrive = 4.1f,
        tireCircumferenceM = DashConfig.tireCircumference("215/40R18"), phoneGSensor = false,
    )

    private fun rpmFor(gear: Int, kph: Float): Float {
        val wheelRpm = kph / 3.6f / cfg.tireCircumferenceM * 60f
        return wheelRpm * cfg.gearRatios[gear - 1] * cfg.finalDrive
    }

    @Test
    fun picksTheRightGear() {
        for (g in 1..6) assertEquals(g, GearEstimator.estimate(rpmFor(g, 60f), 60f, cfg))
    }

    @Test
    fun neutralWhenStoppedOrMismatched() {
        assertEquals(0, GearEstimator.estimate(800f, 0f, cfg))
        assertEquals(0, GearEstimator.estimate(800f, 100f, cfg))
    }

    @Test
    fun tyreMaths() {
        assertEquals(2.06f, DashConfig.tireCircumference("275/35R19"), 0.02f)
        assertEquals(1.93f, DashConfig.tireCircumference("215/40R18"), 0.02f)
        assertEquals(1.93f, DashConfig.tireCircumference("garbage"), 0.001f)
    }
}
