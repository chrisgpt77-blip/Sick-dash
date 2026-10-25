package com.sickdash.can

import com.sickdash.data.Channel
import com.sickdash.data.LinkState
import com.sickdash.data.VehicleData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.io.IOException
import kotlin.coroutines.coroutineContext

/** Talks to an ELM327 / STN11xx / WiCAN-in-ELM-mode adapter. */
class Elm327Session(
    private val transport: Transport,
    private val scope: CoroutineScope,
    private val profile: CanProfile?,
) {
    private lateinit var reader: LineReader

    private suspend fun command(cmd: String, timeoutMs: Long = 2000): List<String> {
        reader.drain()
        transport.output.write((cmd + "\r").toByteArray())
        transport.output.flush()
        val out = ArrayList<String>()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val left = deadline - System.currentTimeMillis()
            if (left <= 0) break
            val line = reader.next(left) ?: break
            if (line == LineReader.PROMPT) return out
            val t = line.trim()
            if (t.isEmpty() || t == cmd || t.startsWith("SEARCHING")) continue
            out += t
        }
        reader.closedReason?.let { throw IOException(it.message, it) }
        if (out.isEmpty()) throw IOException("Adapter did not answer '$cmd'")
        return out
    }

    private suspend fun init(): String {
        reader = LineReader(scope, transport.input)
        transport.output.write("\r".toByteArray())
        delay(300)
        val id = runCatching { command("ATZ", 4000) }.getOrDefault(emptyList())
        for (c in listOf("ATE0", "ATL0", "ATS0", "ATH0", "ATAT2")) command(c)
        return id.lastOrNull { it.contains("ELM", true) || it.contains("STN", true) } ?: "ELM327"
    }

    /** Polls standard OBD-II PIDs. Works on any 2008+ car, including the 370Z. */
    suspend fun runObd() {
        val name = init()
        command("ATSP6") // ISO 15765-4 CAN 11-bit 500k (Nissan 2008+)
        var supported: Set<Int>? = null
        val probe = runCatching { command("0100", 6000) }.getOrNull()
        if (probe == null || probe.any { it.contains("UNABLE") || it.contains("NO DATA") || it.contains("ERROR") }) {
            command("ATSP0")
            command("0100", 10_000)
        }
        runCatching {
            val all = HashSet<Int>()
            for (base in intArrayOf(0x00, 0x20, 0x40)) {
                val s = ObdPid.parseSupported(command("01%02X".format(base)), base) ?: break
                all += s
                if ((base + 0x20) !in s) break
            }
            if (all.isNotEmpty()) supported = all
        }
        val pids = ObdPid.entries.filter { supported?.contains(it.pid) ?: true }.toMutableList()
        val misses = HashMap<ObdPid, Int>()
        val fast = pids.filter { it.fast }
        var slowIdx = 0
        VehicleData.setStatus(LinkState.CONNECTED, "$name · OBD-II · ${transport.label}")

        while (coroutineContext.isActive) {
            val slow = pids.filter { !it.fast }
            val batch = ArrayList(fast.filter { it in pids })
            if (slow.isNotEmpty()) batch += slow[slowIdx++ % slow.size]
            for (p in batch) {
                val reply = command(p.request, 1500)
                val bytes = ObdPid.parseReply(reply, p.pid, p.bytes)
                if (bytes != null) {
                    misses.remove(p)
                    VehicleData.set(p.channel, p.decode(bytes))
                    VehicleData.countFrame()
                } else if (reply.any { it.contains("NO DATA") }) {
                    val m = (misses[p] ?: 0) + 1
                    misses[p] = m
                    if (m >= 3) pids.remove(p)
                }
            }
            if (ObdPid.VOLTAGE !in pids && slowIdx % 10 == 0) {
                // Fall back to the adapter's own pin-16 voltage reading.
                command("ATRV").firstOrNull()?.removeSuffix("V")?.toFloatOrNull()?.let {
                    VehicleData.set(Channel.BATTERY, it)
                }
            }
        }
    }

    /** Listens to every frame on the bus (ATMA) and decodes it with [profile]. */
    suspend fun runMonitor() {
        val profile = profile ?: throw IOException("No CAN profile loaded")
        val name = init()
        command("ATSP6")
        command("ATH1")
        command("ATCAF0")
        LineParsers.elmFilterFor(profile.ids)?.let { (filter, mask) ->
            command("ATCF%03X".format(filter))
            command("ATCM%03X".format(mask))
        }
        VehicleData.setStatus(LinkState.CONNECTED, "$name · raw CAN · ${transport.label}")
        while (coroutineContext.isActive) {
            reader.drain()
            transport.output.write("ATMA\r".toByteArray())
            transport.output.flush()
            while (coroutineContext.isActive) {
                val line = reader.next(3000)
                if (line == null) {
                    reader.closedReason?.let { throw IOException(it.message, it) }
                    break // silence: restart monitoring
                }
                if (line == LineReader.PROMPT || line.contains("BUFFER FULL") || line.contains("STOPPED")) break
                val frame = LineParsers.parseElmMonitor(line) ?: continue
                VehicleData.onFrame(frame)
                profile.decode(frame) { c, v -> VehicleData.set(c, v) }
            }
            // Interrupt ATMA (any byte stops it) and wait for the prompt.
            transport.output.write("\r".toByteArray())
            transport.output.flush()
            val until = System.currentTimeMillis() + 1000
            while (System.currentTimeMillis() < until) {
                if (reader.next(200) == LineReader.PROMPT) break
            }
        }
    }
}

/** Lawicel SLCAN adapters: CANable, CANtact, WiCAN (slcan mode), Arduino/ESP32 bridges. */
class SlcanSession(
    private val transport: Transport,
    private val scope: CoroutineScope,
    private val profile: CanProfile,
) {
    suspend fun run() {
        val reader = LineReader(scope, transport.input)
        val out = transport.output
        fun send(cmd: String) { out.write((cmd + "\r").toByteArray()); out.flush() }
        send("C")
        delay(100)
        val bitrateCmd = LineParsers.slcanBitrateCommand(profile.bitrate)
            ?: throw IOException("SLCAN has no preset for ${profile.bitrate} bit/s")
        send(bitrateCmd)
        delay(50)
        send("O")
        VehicleData.setStatus(LinkState.CONNECTED, "SLCAN · ${profile.bitrate / 1000}k · ${transport.label}")
        var silentFor = 0L
        while (coroutineContext.isActive) {
            val line = reader.next(1000)
            if (line == null) {
                reader.closedReason?.let { throw IOException(it.message, it) }
                silentFor += 1000
                if (silentFor >= 5000) {
                    VehicleData.setStatus(LinkState.CONNECTED, "SLCAN open, no traffic — ignition on?")
                }
                continue
            }
            val frame = LineParsers.parseSlcan(line) ?: continue
            if (silentFor >= 5000) {
                VehicleData.setStatus(LinkState.CONNECTED, "SLCAN · ${profile.bitrate / 1000}k · ${transport.label}")
            }
            silentFor = 0
            VehicleData.onFrame(frame)
            profile.decode(frame) { c, v -> VehicleData.set(c, v) }
        }
        runCatching { send("C") }
    }
}
