package com.sickdash.can

import com.sickdash.data.Channel
import com.sickdash.data.VehicleData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import kotlin.concurrent.thread

/**
 * Runs the ELM327 session against a scripted fake adapter, standing in for a DauntlessOBD on
 * the GR86's ASC (body CAN) connector, and for a plain ELM327 on the gatewayed OBD port.
 */
class Elm327SessionTest {
    private class FakeAdapter(
        private val dauntless: Boolean,
        private val broadcast: List<String>,      // monitor lines ("<id><data>") the bus carries
        private val obd: Map<String, String>,      // OBD request -> reply
    ) : Transport {
        override val label = "fake"
        private val toApp = PipedOutputStream()
        override val input: InputStream = PipedInputStream(toApp, 1 shl 16)
        private val fromApp = PipedInputStream(1 shl 16)
        override val output: OutputStream = PipedOutputStream(fromApp)
        val commands = ArrayList<String>()
        @Volatile private var monitoring = false
        @Volatile private var closed = false

        override fun open() {
            thread(isDaemon = true) {
                val sb = StringBuilder()
                while (!closed) {
                    val b = runCatching { fromApp.read() }.getOrDefault(-1)
                    if (b < 0) break
                    if (monitoring) { monitoring = false; Thread.sleep(20); send(">"); continue }
                    if (b.toChar() != '\r') { sb.append(b.toChar()); continue }
                    val cmd = sb.toString().trim().uppercase().replace(" ", ""); sb.setLength(0)
                    if (cmd.isEmpty()) { send(">"); continue }
                    synchronized(commands) { commands += cmd }
                    when {
                        cmd == "ATZ" -> send("\r\rELM327 v1.5\r\r>")
                        cmd == "DDI" -> send(if (dauntless) "DauntlessOBD v1.16\r\r>" else "?\r\r>")
                        cmd == "DDMF" || cmd == "ATMA" -> {
                            monitoring = true
                            thread(isDaemon = true) {
                                while (monitoring && !closed) {
                                    for (l in broadcast) send("$l\r")
                                    Thread.sleep(10)
                                }
                            }
                        }
                        obd.containsKey(cmd) -> send("${obd[cmd]}\r\r>")
                        cmd.startsWith("01") -> send("NO DATA\r\r>")
                        else -> send("OK\r\r>")
                    }
                }
            }
        }

        @Synchronized private fun send(s: String) {
            runCatching { toApp.write(s.toByteArray()); toApp.flush() }
        }

        override fun close() {
            closed = true
            monitoring = false
            runCatching { toApp.close() }
            runCatching { fromApp.close() }
        }
    }

    private val profile = CanProfile.parse(File("src/main/assets/profiles/toyota_gr86_brz_gen2.json").readText())

    private fun runSession(adapter: FakeAdapter, mode: ElmMode, untilMs: Long = 6000, done: () -> Boolean) = runBlocking {
        VehicleData.clearAll()
        adapter.open()
        val job = launch(Dispatchers.IO) { runCatching { Elm327Session(adapter, this, profile).run(mode) } }
        withTimeout(untilMs) { while (!done()) delay(20) }
        adapter.close()
        job.cancelAndJoin()
    }

    @Test
    fun dauntlessOnAscConnectorStreamsRawCan() {
        val adapter = FakeAdapter(
            dauntless = true,
            broadcast = listOf(
                "040" + "100DFA06330000C3",   // 0x40: 1786 rpm, pedal 0x33 (documented "moving slowly" frame)
                "139" + "625BFCE00800CD1C",   // 0x139: ~4 km/h
                "241" + "0000000018800000",   // 0x241: gear 3, clutch down
            ),
            obd = emptyMap(),
        )
        runSession(adapter, ElmMode.AUTO) { VehicleData.snapshot()[Channel.GEAR] != null && VehicleData.snapshot()[Channel.RPM] != null }
        val s = VehicleData.snapshot()
        assertEquals(1786f, s[Channel.RPM])
        assertEquals(3f, s[Channel.GEAR])
        assertEquals("CAN", VehicleData.linkMode)
        val cmds = synchronized(adapter.commands) { adapter.commands.toList() }
        assertTrue("uses raw CAN protocol", "ATTP81" in cmds)
        assertTrue("filters to the profile's ids", "DDCRA040" in cmds && "DDCRA241" in cmds && "DDCRA6E2" in cmds)
        assertTrue("monitors filtered traffic", "DDMF" in cmds)
    }

    @Test
    fun silentBusFallsBackToObdPolling() {
        val adapter = FakeAdapter(
            dauntless = false,
            broadcast = emptyList(),
            obd = mapOf("0100" to "4100BE1FA813", "010C" to "410C1BE8", "010D" to "410D3C"),
        )
        runSession(adapter, ElmMode.AUTO, untilMs = 10_000) { VehicleData.snapshot()[Channel.SPEED] != null }
        val s = VehicleData.snapshot()
        assertNotNull(s[Channel.RPM])
        assertEquals(1786f, s[Channel.RPM])
        assertEquals(60f, s[Channel.SPEED])
        assertEquals("OBD", VehicleData.linkMode)
    }
}
