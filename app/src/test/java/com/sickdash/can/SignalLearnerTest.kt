package com.sickdash.can

import com.sickdash.data.Channel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Feeds the learner a made-up bus with noise, a slow drift, and the control under test. */
class SignalLearnerTest {
    private val rnd = Random(7)
    private var counter = 0
    private var drift = 40

    /** One capture's worth of traffic with the control in [state], drawn into frames by [control]. */
    private fun capture(l: SignalLearner, state: Float, control: (frame: Int, data: ByteArray) -> Unit, controlId: Int) {
        l.begin(state)
        drift += 1   // a temperature creeping up between captures
        repeat(60) { f ->
            l.onFrame(CanFrame(0x40, ByteArray(8) { i -> if (i == 0) (counter++).toByte() else if (i < 4) rnd.nextInt(256).toByte() else 0x11 }))
            l.onFrame(CanFrame(0x345, ByteArray(8) { i -> if (i == 3) drift.toByte() else 0x22 }))
            if (f % 3 == 0) l.onFrame(CanFrame(0x139, ByteArray(8) { rnd.nextInt(256).toByte() }))
            val d = ByteArray(8) { 0x05 }
            control(f, d)
            l.onFrame(CanFrame(controlId, d))
        }
        l.end()
    }

    private fun setBit(d: ByteArray, bit: Int, on: Boolean) {
        val m = 1 shl (bit % 8)
        d[bit / 8] = (if (on) d[bit / 8].toInt() or m else d[bit / 8].toInt() and m.inv()).toByte()
    }

    @Test
    fun findsADoorSwitch() {
        val l = SignalLearner()
        for (s in SignalLearner.order(2).map { it.toFloat() }) capture(l, s, { _, d -> setBit(d, 13, s == 1f) }, 0x3AC)
        val best = l.analyse().first()
        assertEquals(0x3AC, best.id)
        assertEquals(13, best.bit)
        assertEquals(1, best.bits)
        assertNull(best.map)
        val open = ByteArray(8) { 0x05 }.also { setBit(it, 13, true) }
        assertEquals(1f, best.decode(CanFrame(0x3AC, open)))
        assertEquals(0f, best.decode(CanFrame(0x3AC, ByteArray(8) { 0x05 })))
    }

    @Test
    fun anInvertedSwitchGetsAMap() {
        val l = SignalLearner()
        // belt: the bit is set while unbuckled
        for (s in SignalLearner.order(2).map { it.toFloat() }) capture(l, s, { _, d -> setBit(d, 40, s == 0f) }, 0x6E1)
        val best = l.analyse().first()
        assertEquals(0x6E1, best.id)
        assertEquals(40, best.bit)
        assertEquals(mapOf(1L to 0f, 0L to 1f), best.map)
    }

    @Test
    fun findsAMultiPositionSwitch() {
        val l = SignalLearner()
        // headlights in two bits, in an order that isn't 0-1-2-3
        val raw = mapOf(0f to 0, 1f to 1, 2f to 3, 3f to 2)
        for (s in SignalLearner.order(4).map { it.toFloat() }) capture(l, s, { _, d -> setBit(d, 20, raw[s]!! and 1 != 0); setBit(d, 21, raw[s]!! and 2 != 0) }, 0x3B0)
        val best = l.analyse().first()
        assertEquals(0x3B0, best.id)
        assertEquals(20, best.bit)
        assertEquals(2, best.bits)
        assertTrue(best.distinguishesAll)
        assertEquals(mapOf(0L to 0f, 1L to 1f, 3L to 2f, 2L to 3f), best.map)
    }

    @Test
    fun findsFlashingIndicators() {
        val l = SignalLearner()
        // left lamp bit 8, right lamp bit 9, flashing; hazards flash both
        for (s in SignalLearner.order(4).map { it.toFloat() }) capture(l, s, { f, d ->
            val lit = (f / 5) % 2 == 0
            setBit(d, 8, lit && (s == 1f || s == 3f))
            setBit(d, 9, lit && (s == 2f || s == 3f))
        }, 0x652)
        val best = l.analyse().first()
        assertEquals(0x652, best.id)
        assertEquals(8, best.bit)
        assertEquals(2, best.bits)
        assertTrue(best.blinks)
        assertEquals(mapOf(0L to 0f, 1L to 1f, 2L to 2f, 3L to 3f), best.map)
    }

    @Test
    fun aCreepingValueIsNotMistakenForTheSwitch() {
        // the only thing changing is the drifting temperature; in a regular order its low bits would match
        val l = SignalLearner()
        for (s in SignalLearner.order(4).map { it.toFloat() }) capture(l, s, { _, _ -> }, 0x3AC)
        assertTrue(l.analyse().isEmpty())
    }

    @Test
    fun nothingFollowsIsEmpty() {
        val l = SignalLearner()
        for (s in SignalLearner.order(2).map { it.toFloat() }) capture(l, s, { _, _ -> }, 0x3AC)
        assertTrue(l.analyse().isEmpty())
    }

    @Test
    fun savesIntoTheProfile() {
        val json = """{"name":"t","signals":[{"channel":"rpm","id":"0x40","bit":16,"bits":14},
            {"channel":"door_lock","id":"0x100","start":0}],"nodes":{"ECM":["0x40"]}}"""
        val c = SignalLearner.Candidate(0x3AC, 40, 1, mapOf(1L to 0f, 0L to 1f), true, 0, false, 1000)
        val out = SignalLearner.withSignal(json, Channel.DOOR_LOCK, c, "learned")
        val p = CanProfile.parse(out)
        assertEquals(2, p.signals.size)
        val lock = p.signals.single { it.channel == Channel.DOOR_LOCK }
        assertEquals(0x3AC, lock.id)
        assertEquals(1f, lock.decode(CanFrame(0x3AC, ByteArray(8))))
        assertEquals(listOf(0x3AC), p.nodes["BODY"])
        // learning the same channel again replaces it rather than adding a second
        val again = CanProfile.parse(SignalLearner.withSignal(out, Channel.DOOR_LOCK, c, "again"))
        assertEquals(1, again.signals.count { it.channel == Channel.DOOR_LOCK })
        assertEquals(listOf(0x3AC), again.nodes["BODY"])
    }
}
