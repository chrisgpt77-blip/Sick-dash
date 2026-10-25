package com.sickdash.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NavigationTest {
    @Test
    fun distanceInTheTitle() {
        val t = Navigation.parse("0.3 mi", "Turn right onto Main St", "12 min · 5.1 mi · 6:42 PM", null, 0L)!!
        assertEquals("0.3 mi", t.distance)
        assertEquals("Turn right onto Main St", t.instruction)
        assertEquals("12 min · 5.1 mi · 6:42 PM", t.eta)
    }

    @Test
    fun distanceInTheText() {
        val t = Navigation.parse("Turn left onto Ridge Rd", "500 ft", null, null, 0L)!!
        assertEquals("500 ft", t.distance)
        assertEquals("Turn left onto Ridge Rd", t.instruction)
        assertNull(t.eta)
    }

    @Test
    fun metricAndCommas() {
        assertEquals("1,2 km", Navigation.parse("1,2 km", "Keep left", null, null, 0L)!!.distance)
        assertEquals("200 m", Navigation.parse("Take exit 4", "200 m", null, null, 0L)!!.distance)
    }

    @Test
    fun noDistanceKeepsTheInstruction() {
        val t = Navigation.parse("Head north on Canyon Dr", "toward Ridge Rd", null, null, 0L)!!
        assertNull(t.distance)
        assertEquals("Head north on Canyon Dr", t.instruction)
    }

    @Test
    fun nothingUsefulIsNull() {
        assertNull(Navigation.parse(null, "  ", null, null, 0L))
        assertNull(Navigation.parse("2 mi", null, null, null, 0L))
    }

    @Test
    fun wordsThatStartWithAUnitAreNotDistances() {
        // "Main" starts with "m" but isn't a number + unit
        assertEquals(false, Navigation.looksLikeDistance("Main St"))
        assertEquals(false, Navigation.looksLikeDistance("12 Mile Rd ahead".replace("12 ", "Twelve ")))
        assertEquals(true, Navigation.looksLikeDistance("12 mi"))
    }
}
