package com.sickdash.can

import com.sickdash.data.Channel
import com.sickdash.data.LinkState
import com.sickdash.data.VehicleData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.io.IOException
import kotlin.coroutines.coroutineContext

enum class ElmMode { AUTO, OBD, RAW }

/**
 * Talks to an ELM327-compatible adapter: genuine/clone ELM327, OBDLink (STN), WiCAN in ELM mode,
 * and DauntlessOBD, whose "DD" extensions (multi-ID filters, overflow-tolerant monitoring, fast
 * LE) are used when it identifies itself.
 */
class Elm327Session(
    private val transport: Transport,
    private val scope: CoroutineScope,
    private val profile: CanProfile?,
) {
    private lateinit var reader: LineReader
    private var name = "ELM327"
    private var dauntless = false

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

    private suspend fun init() {
        reader = LineReader(scope, transport.input)
        transport.output.write("\r".toByteArray())
        delay(300)
        val id = runCatching { command("ATZ", 4000) }.getOrDefault(emptyList())
        for (c in listOf("ATE0", "ATL0", "ATS0", "ATH0", "ATAT2")) command(c)
        name = id.lastOrNull { it.contains("ELM", true) || it.contains("STN", true) } ?: "ELM327"
        val dd = runCatching { command("DD I") }.getOrDefault(emptyList())
        dd.firstOrNull { it.contains("Dauntless", true) }?.let {
            dauntless = true
            name = it.trim()
            runCatching { command("DD LEFAST 1") }
            runCatching { command("DD MOF 1") }
        }
        VehicleData.linkMode = "ELM"
    }

    suspend fun run(mode: ElmMode) {
        init()
        when (mode) {
            ElmMode.OBD -> obd()
            ElmMode.RAW -> monitor(probeMs = null)
            ElmMode.AUTO -> {
                // Broadcast traffic means we are on a body/chassis CAN (ASC, DCM, older cars' OBD port).
                VehicleData.setStatus(LinkState.CONNECTING, "$name · listening for CAN traffic…")
                if (profile == null || !monitor(probeMs = 2500)) {
                    // Nothing broadcast here (e.g. behind the gateway on the factory OBD port): poll instead.
                    if (dauntless) runCatching { command("DD CRA") }
                    runCatching { command("ATCRA") }
                    runCatching { command("ATD") }
                    runCatching { command("ATCAF1") }
                    for (c in listOf("ATE0", "ATL0", "ATS0", "ATH0", "ATAT2")) command(c)
                    obd()
                }
            }
        }
    }

    /** Polls standard OBD-II PIDs. Works on any 2008+ car through the factory OBD port. */
    private suspend fun obd() {
        command("ATSP6") // ISO 15765-4 CAN 11-bit 500k
        var supported: Set<Int>? = null
        val probe = runCatching { command("0100", 6000) }.getOrNull()
        if (probe == null || probe.any { it.contains("UNABLE") || it.contains("NO DATA") || it.contains("ERROR") }) {
            command("ATSP0")
            val retry = command("0100", 10_000)
            if (retry.none { it.replace(" ", "").contains("4100") }) {
                throw IOException("No OBD-II response. On a GR86/BRZ ASC or DCM connector use Read mode: Auto or Raw CAN")
            }
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
        VehicleData.linkMode = "OBD"
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
                command("ATRV").firstOrNull()?.removeSuffix("V")?.toFloatOrNull()?.let {
                    VehicleData.set(Channel.BATTERY, it)
                }
            }
        }
    }

    /**
     * Listens to the bus and decodes it with [profile]. With [probeMs] set, gives up and returns
     * false if no frame for the profile arrives in that time; otherwise runs until cancelled.
     */
    private suspend fun monitor(probeMs: Long?): Boolean {
        val profile = profile ?: throw IOException("No CAN profile loaded")
        command("ATH1")
        command("ATS0")
        val monitorCmd: String
        if (dauntless && profile.bitrate == 500_000) {
            command("ATTP 81") // raw CAN, 500k
            command("DD CRA")
            for (id in profile.ids.sorted()) command("DD CRA %03X".format(id))
            monitorCmd = "DD MF"
        } else {
            command("ATSP6")
            command("ATCAF0")
            LineParsers.elmFilterFor(profile.ids)?.let { (filter, mask) ->
                command("ATCF%03X".format(filter))
                command("ATCM%03X".format(mask))
            }
            monitorCmd = "ATMA"
        }
        var announced = false
        val probeUntil = probeMs?.let { System.currentTimeMillis() + it }
        while (coroutineContext.isActive) {
            reader.drain()
            transport.output.write((monitorCmd + "\r").toByteArray())
            transport.output.flush()
            while (coroutineContext.isActive) {
                if (!announced && probeUntil != null && System.currentTimeMillis() > probeUntil) {
                    stopMonitor()
                    return false
                }
                val line = reader.next(if (probeUntil != null && !announced) 500 else 3000)
                if (line == null) {
                    reader.closedReason?.let { throw IOException(it.message, it) }
                    if (probeUntil != null && !announced) continue
                    break // silence: restart monitoring
                }
                if (line.contains("BUFFER FULL") && dauntless) continue
                if (line == LineReader.PROMPT || line.contains("BUFFER FULL") || line.contains("STOPPED")) break
                val frame = LineParsers.parseElmMonitor(line) ?: continue
                if (!announced && frame.id in profile.ids) {
                    announced = true
                    VehicleData.linkMode = "CAN"
                    VehicleData.setStatus(LinkState.CONNECTED, "$name · raw CAN · ${transport.label}")
                }
                VehicleData.onFrame(frame)
                profile.decode(frame) { c, v -> VehicleData.set(c, v) }
            }
            stopMonitor()
        }
        return true
    }

    /** Any byte stops monitoring; wait for the prompt so the next command lands cleanly. */
    private suspend fun stopMonitor() {
        transport.output.write("\r".toByteArray())
        transport.output.flush()
        val until = System.currentTimeMillis() + 1000
        while (System.currentTimeMillis() < until) {
            if (reader.next(200) == LineReader.PROMPT) break
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
        VehicleData.linkMode = "CAN"
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
