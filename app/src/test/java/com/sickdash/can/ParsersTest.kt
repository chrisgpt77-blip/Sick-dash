package com.sickdash.can

import com.sickdash.data.Channel
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class ParsersTest {
    @Test
    fun elmMonitorLine() {
        val f = LineParsers.parseElmMonitor("180154A000033000000")!!
        assertEquals(0x180, f.id)
        assertArrayEquals(byteArrayOf(0x15, 0x4A, 0, 0, 0x33, 0, 0, 0), f.data)
        assertNull(LineParsers.parseElmMonitor("SEARCHING..."))
        assertNull(LineParsers.parseElmMonitor("1801"))
    }

    @Test
    fun slcanLines() {
        val f = LineParsers.parseSlcan("t35482710000000001400")!!
        assertEquals(0x354, f.id)
        assertEquals(8, f.data.size)
        assertEquals(0x27, f.u8(0))
        val ext = LineParsers.parseSlcan("T18DAF110203410C")!!
        assertEquals(0x18DAF110, ext.id)
        assertEquals(2, ext.data.size)
        assertNull(LineParsers.parseSlcan("z"))
        assertNull(LineParsers.parseSlcan("t35482710"))
        assertEquals("S6", LineParsers.slcanBitrateCommand(500_000))
    }

    @Test
    fun elmFilterCoversAllIds() {
        val ids = listOf(0x180, 0x354, 0x551, 0x421, 0x002)
        val (filter, mask) = LineParsers.elmFilterFor(ids)!!
        for (id in ids) assertEquals(filter, id and mask)
    }

    @Test
    fun obdReplies() {
        val rpm = ObdPid.parseReply(listOf("410C1AF8"), 0x0C, 2)!!
        assertEquals(1726f, ObdPid.RPM.decode(rpm))
        val spaced = ObdPid.parseReply(listOf("41 0D 64"), 0x0D, 1)!!
        assertEquals(100f, ObdPid.SPEED.decode(spaced))
        assertEquals(50f, ObdPid.COOLANT.decode(ObdPid.parseReply(listOf("7E8 03 41 05 5A"), 0x05, 1)!!))
        assertNull(ObdPid.parseReply(listOf("NO DATA"), 0x0C, 2))
        val sup = ObdPid.parseSupported(listOf("4100BE1FA813"), 0x00)!!
        assertEquals(true, 0x0C in sup)
        assertEquals(true, 0x0D in sup)
        assertEquals(true, 0x20 in sup)
    }

    @Test
    fun builtInProfileDecodes370zFrames() {
        val json = File("src/main/assets/profiles/nissan_370z.json").readText()
        val profile = CanProfile.parse(json)
        assertEquals(500_000, profile.bitrate)
        val out = HashMap<Channel, Float>()
        val sink: (Channel, Float) -> Unit = { c, v -> out[c] = v }
        profile.decode(CanFrame(0x180, byteArrayOf(0x15, 0x4A, 0, 0, 0, 0x80.toByte(), 0, 0)), sink)
        assertEquals(681.25f, out[Channel.RPM]!!, 0.01f)
        assertEquals(50.2f, out[Channel.THROTTLE]!!, 0.1f)
        profile.decode(CanFrame(0x354, byteArrayOf(0x27, 0x10, 0, 0, 0, 0, 20, 0)), sink)
        assertEquals(100f, out[Channel.SPEED]!!, 0.01f)
        assertEquals(1f, out[Channel.BRAKE])
        profile.decode(CanFrame(0x421, byteArrayOf(0x88.toByte(), 0, 0)), sink)
        assertEquals(2f, out[Channel.GEAR])
        profile.decode(CanFrame(0x421, byteArrayOf(24)), sink)
        assertEquals(0f, out[Channel.GEAR])
        profile.decode(CanFrame(0x002, byteArrayOf(0x2C, 0xFF.toByte(), 0, 0, 0)), sink)
        assertEquals(-21.2f, out[Channel.STEERING]!!, 0.01f)
        profile.decode(CanFrame(0x551, byteArrayOf(130.toByte(), 0)), sink)
        assertEquals(90f, out[Channel.COOLANT])
    }

    @Test
    fun shortFrameIsIgnored() {
        val profile = CanProfile.parse("""{"signals":[{"channel":"rpm","id":"0x180","start":6,"length":2}]}""")
        var called = false
        profile.decode(CanFrame(0x180, byteArrayOf(1, 2, 3))) { _, _ -> called = true }
        assertEquals(false, called)
        assertNotNull(profile)
    }
}
