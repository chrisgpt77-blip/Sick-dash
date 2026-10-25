package com.sickdash.data

import com.sickdash.can.CanFrame
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentHashMap

enum class LinkState { IDLE, CONNECTING, CONNECTED, ERROR }

data class LinkStatus(val state: LinkState, val message: String)

/** Immutable copy of the latest values, handed to the renderers once per frame. */
class Snapshot(
    private val values: FloatArray,
    private val stamps: LongArray,
    val now: Long,
    val link: LinkStatus,
    val framesPerSecond: Int,
) {
    /** Value of [c], or null when it was never received or has gone stale. */
    operator fun get(c: Channel, maxAgeMs: Long = STALE_MS): Float? {
        val t = stamps[c.ordinal]
        return if (t == 0L || now - t > maxAgeMs) null else values[c.ordinal]
    }

    companion object {
        const val STALE_MS = 5_000L
    }
}

/** One row of the CAN sniffer. */
class SniffEntry(val id: Int) {
    var data: ByteArray = ByteArray(0)
    var previous: ByteArray = ByteArray(0)
    var changedAt = LongArray(8)
    var count = 0L
    var lastSeen = 0L
    var periodMs = 0f
}

/** Process-wide store that the CAN/OBD readers write into and the dashboards read from. */
object VehicleData {
    private val lock = Any()
    private val values = FloatArray(Channel.entries.size)
    private val stamps = LongArray(Channel.entries.size)

    /** Where each channel last came from: [SRC_CAN] + CAN id, [SRC_OBD] + PID, phone, demo or derived. */
    val sourceKind = IntArray(Channel.entries.size)
    val sourceId = IntArray(Channel.entries.size)

    /** Total updates per channel, for the Systems page's update rates. */
    val updates = LongArray(Channel.entries.size)

    const val SRC_CAN = 1
    const val SRC_OBD = 2
    const val SRC_PHONE = 3
    const val SRC_DEMO = 4
    const val SRC_DERIVED = 5

    private val _status = MutableStateFlow(LinkStatus(LinkState.IDLE, "Not connected"))
    val status: StateFlow<LinkStatus> = _status

    val sniffer = ConcurrentHashMap<Int, SniffEntry>()

    /** Which module sends which CAN ids (from the CAN profile), for the boot-time node check. */
    @Volatile var nodes: Map<String, List<Int>> = emptyMap()

    /** What the live link turned out to be: "CAN", "OBD", "DEMO" or "ELM" while it is still deciding. */
    @Volatile var linkMode = "—"

    @Volatile private var frameCounter = 0
    @Volatile private var fps = 0
    @Volatile private var fpsWindowStart = 0L

    fun set(c: Channel, value: Float, now: Long = System.currentTimeMillis(), kind: Int = 0, id: Int = 0) {
        if (value.isNaN() || value.isInfinite()) return
        synchronized(lock) {
            values[c.ordinal] = value
            stamps[c.ordinal] = now
            updates[c.ordinal]++
            if (kind != 0) { sourceKind[c.ordinal] = kind; sourceId[c.ordinal] = id }
        }
    }

    /** Human-readable source of a channel, e.g. "CAN 0x40" or "OBD 0C". */
    fun sourceOf(c: Channel): String = when (sourceKind[c.ordinal]) {
        SRC_CAN -> "CAN 0x%X".format(sourceId[c.ordinal])
        SRC_OBD -> "OBD %02X".format(sourceId[c.ordinal])
        SRC_PHONE -> "PHONE IMU"
        SRC_DEMO -> "DEMO"
        SRC_DERIVED -> "DERIVED"
        else -> "—"
    }

    fun clear(c: Channel) = synchronized(lock) { stamps[c.ordinal] = 0L }

    fun clearAll() = synchronized(lock) {
        stamps.fill(0L)
        sourceKind.fill(0)
    }

    fun setStatus(state: LinkState, message: String) {
        _status.value = LinkStatus(state, message)
    }

    /** Called for every raw frame so the sniffer and the frame-rate counter stay current. */
    fun onFrame(frame: CanFrame, now: Long = System.currentTimeMillis()) {
        val e = sniffer.getOrPut(frame.id) { SniffEntry(frame.id) }
        synchronized(e) {
            if (e.lastSeen != 0L) {
                val dt = (now - e.lastSeen).toFloat()
                e.periodMs = if (e.periodMs == 0f) dt else e.periodMs * 0.9f + dt * 0.1f
            }
            for (i in frame.data.indices) {
                if (i >= e.data.size || e.data[i] != frame.data[i]) e.changedAt[i] = now
            }
            e.previous = e.data
            e.data = frame.data
            e.count++
            e.lastSeen = now
        }
        countFrame(now)
    }

    /** Counts a received message (raw frame or OBD reply) for the "msgs/s" readout. */
    fun countFrame(now: Long = System.currentTimeMillis()) {
        frameCounter++
        if (now - fpsWindowStart >= 1000) {
            fps = frameCounter
            frameCounter = 0
            fpsWindowStart = now
        }
    }

    fun snapshot(now: Long = System.currentTimeMillis()): Snapshot {
        val v: FloatArray
        val s: LongArray
        synchronized(lock) {
            v = values.copyOf()
            s = stamps.copyOf()
        }
        val rate = if (now - fpsWindowStart > 2000) 0 else fps
        return Snapshot(v, s, now, _status.value, rate)
    }
}
