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

    private fun frame(id: Int, hex: String) = CanFrame(id, hexToBytes(hex.replace(" ", ""))!!)

    /** Example frames and expected values from github.com/timurrrr/ft86 can_bus/gen2.md. */
    @Test
    fun gr86Gen2ProfileDecodesDocumentedFrames() {
        val profile = CanProfile.parse(File("src/main/assets/profiles/toyota_gr86_brz_gen2.json").readText())
        val out = HashMap<Channel, Float>()
        val sink: (Channel, Float) -> Unit = { c, v -> out[c] = v }

        profile.decode(frame(0x40, "5D 0B C9 82 00 00 00 C7"), sink)      // parked
        assertEquals(713f, out[Channel.RPM])
        profile.decode(frame(0x40, "10 0D FA 06 33 00 00 C3"), sink)      // moving slowly
        assertEquals(1786f, out[Channel.RPM])
        assertEquals(20f, out[Channel.THROTTLE]!!, 0.01f)

        profile.decode(frame(0x139, "72 5A 00 E0 08 00 DA 1C"), sink)     // parked
        assertEquals(0f, out[Channel.SPEED])
        profile.decode(frame(0x139, "62 5B FC E0 08 00 CD 1C"), sink)     // moving slowly
        assertEquals(3.955f, out[Channel.SPEED]!!, 0.01f)

        profile.decode(frame(0x138, "90 0D 13 FA 3F 00 00 00"), sink)     // 0xFA13 = -1517 -> 151.7 deg
        assertEquals(151.7f, out[Channel.STEERING]!!, 0.01f)

        profile.decode(frame(0x345, "00 00 00 82 7D 00 00 00"), sink)
        assertEquals(90f, out[Channel.OIL_TEMP])
        assertEquals(85f, out[Channel.COOLANT])

        // gear lives in bits 35..37: byte 4 = 0b0001_1000 -> 3
        profile.decode(frame(0x241, "00 00 00 00 18 80 00 00"), sink)
        assertEquals(3f, out[Channel.GEAR])
        assertEquals(100f, out[Channel.CLUTCH])

        // US cluster: 36/33/35/34 psi; 0xFE means "not shown yet" and must be ignored
        profile.decode(frame(0x6E2, "00 00 04 48 42 46 44 FE"), sink)
        assertEquals(36f * 6.8948f, out[Channel.TIRE_FL]!!, 0.01f)
        assertEquals(34f * 6.8948f, out[Channel.TIRE_RR]!!, 0.01f)
        out.remove(Channel.TIRE_FL)
        profile.decode(frame(0x6E2, "00 00 04 FE FE FE FE FE"), sink)
        assertNull(out[Channel.TIRE_FL])

        profile.decode(frame(0x139, "00 00 00 00 00 FF 00 00"), sink)     // full brake pressure clamps to 100 %
        assertEquals(100f, out[Channel.BRAKE])
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
