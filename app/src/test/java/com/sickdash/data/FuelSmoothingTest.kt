package com.sickdash.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class FuelSmoothingTest {
    @Test
    fun sloshIsDampedLikeTheFactoryGauge() {
        VehicleData.clearAll()
        var now = 1_000_000L
        VehicleData.set(Channel.FUEL, 50f, now)
        // 30 s of cornering: the raw sender swings ±6 % at 0.5 Hz, 10 readings a second
        var worst = 0f
        repeat(300) { i ->
            now += 100
            VehicleData.set(Channel.FUEL, 50f + if ((i / 10) % 2 == 0) 6f else -6f, now)
            worst = maxOf(worst, abs(VehicleData.snapshot(now)[Channel.FUEL]!! - 50f))
        }
        assertTrue("shown level wandered $worst %", worst < 1f)
    }

    @Test
    fun aReadingAfterAGapIsTakenStraightAway() {
        VehicleData.clearAll()
        var now = 2_000_000L
        VehicleData.set(Channel.FUEL, 20f, now)
        now += 60_000 // car off, filled up
        VehicleData.set(Channel.FUEL, 95f, now)
        assertEquals(95f, VehicleData.snapshot(now)[Channel.FUEL]!!, 0.01f)
    }

    @Test
    fun otherChannelsAreNotSmoothed() {
        VehicleData.clearAll()
        VehicleData.set(Channel.RPM, 1000f, 3_000_000L)
        VehicleData.set(Channel.RPM, 6000f, 3_000_050L)
        assertEquals(6000f, VehicleData.snapshot(3_000_050L)[Channel.RPM]!!, 0.01f)
    }
}
