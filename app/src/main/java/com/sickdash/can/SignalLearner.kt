package com.sickdash.can

import com.sickdash.data.Channel
import org.json.JSONArray
import org.json.JSONObject

/**
 * Finds where an on/off or multi-position control lives on the bus, by watching it change.
 *
 * The user puts the control in each state in turn (twice or three times round), and for each
 * state we record every bit of every id: always 0, always 1, or toggling. A bit belongs to the
 * control when it reads the same every time the control is in the same state, and differently
 * in different states. Bits that drift for other reasons (temperatures, counters) don't keep
 * that pattern over the repeats, so they drop out. Toggling bits count as "on", which is what a
 * flashing indicator lamp looks like.
 *
 * Bit numbers are the profile's: the frame read as one little-endian number, so bit 0 is the
 * low bit of byte 0 and bit 13 is bit 5 of byte 1.
 */
class SignalLearner {
    /** One state's recording: per id, which bits were ever 0 / ever 1. */
    private class Capture(val state: Float) {
        val ever0 = HashMap<Int, Long>()
        val ever1 = HashMap<Int, Long>()
        val bits = HashMap<Int, Int>()
        val frames = HashMap<Int, Int>()
    }

    class Candidate(
        val id: Int,
        val bit: Int,
        val bits: Int,
        /** raw → state value; null when the field reads the state directly (0 / 1). */
        val map: Map<Long, Float>?,
        val distinguishesAll: Boolean,
        val noise: Int,
        val blinks: Boolean,
        val score: Int,
    ) {
        fun decode(frame: CanFrame): Float? = toSignal(Channel.RPM).decode(frame)

        fun toSignal(channel: Channel) = SignalDef(
            channel = channel, id = id, start = bit, length = 1, bigEndian = false, signed = false,
            scale = 1f, offset = 0f, shift = 0, mask = -1L, map = map, bitLength = bits,
        )

        val describe: String get() = "0x%03X · bit %d%s".format(id, bit, if (bits > 1) "–${bit + bits - 1}" else "")
    }

    private val captures = ArrayList<Capture>()
    @Volatile private var active: Capture? = null

    val captureCount: Int get() = captures.size

    fun clear() = synchronized(this) { captures.clear(); active = null }

    fun begin(state: Float) = synchronized(this) { active = Capture(state).also { captures += it } }

    fun end() { active = null }

    fun onFrame(frame: CanFrame) {
        val c = active ?: return
        var v = 0L
        for (i in frame.data.indices.reversed()) v = (v shl 8) or frame.u8(i).toLong()
        val n = frame.data.size * 8
        val all = if (n >= 64) -1L else (1L shl n) - 1
        synchronized(this) {
            c.ever1[frame.id] = (c.ever1[frame.id] ?: 0L) or v
            c.ever0[frame.id] = (c.ever0[frame.id] ?: 0L) or (v.inv() and all)
            c.bits[frame.id] = minOf(c.bits[frame.id] ?: n, n)
            c.frames[frame.id] = (c.frames[frame.id] ?: 0) + 1
        }
    }

    /** Best matches first; empty when nothing on the bus follows the control. */
    fun analyse(maxResults: Int = 5): List<Candidate> = synchronized(this) {
        if (captures.size < 2 || captures.map { it.state }.distinct().size < 2) return emptyList()
        val ids = captures.map { it.frames.keys.toSet() }.reduce { a, b -> a intersect b }
        val states = captures.map { it.state }
        val out = ArrayList<Candidate>()
        for (id in ids) {
            val nBits = captures.minOf { it.bits[id] ?: 0 }
            // per capture, per bit: 0, 1 or 2 (toggling)
            val obs = Array(captures.size) { k ->
                val c = captures[k]
                val e0 = c.ever0[id] ?: 0L
                val e1 = c.ever1[id] ?: 0L
                IntArray(nBits) { b ->
                    val z = (e0 ushr b) and 1L == 1L
                    val o = (e1 ushr b) and 1L == 1L
                    when { z && o -> 2; o -> 1; else -> 0 }
                }
            }
            val follows = ArrayList<Int>()
            var noise = 0
            for (b in 0 until nBits) {
                val col = IntArray(captures.size) { obs[it][b] }
                if (col.all { it == col[0] }) continue                 // never changes between states
                var consistent = true
                val seen = HashMap<Float, Int>()
                for (k in col.indices) {
                    val prev = seen.put(states[k], col[k])
                    if (prev != null && prev != col[k]) { consistent = false; break }
                }
                if (consistent) follows += b else if (col.none { it == 2 }) noise++
            }
            if (follows.isEmpty()) continue
            fieldFrom(id, follows, obs, states, noise)?.let { out += it }
            // each following bit on its own too, for when the span picks up an unrelated neighbour
            if (follows.size > 1) for (b in follows) fieldFrom(id, listOf(b), obs, states, noise)?.let { out += it }
        }
        return out.sortedByDescending { it.score }.distinctBy { it.id to it.bit to it.bits }.take(maxResults)
    }

    private fun fieldFrom(id: Int, follows: List<Int>, obs: Array<IntArray>, states: List<Float>, noise: Int): Candidate? {
        val lo = follows.min()
        val hi = follows.max()
        val width = hi - lo + 1
        if (width > 16) return null
        // bits inside the span that don't follow the control must at least hold still
        for (b in lo..hi) if (b !in follows) {
            val col = obs.map { it[b] }
            if (col.any { it == 2 } || col.distinct().size > 1) return null
        }
        val raws = obs.map { o -> (0 until width).fold(0L) { acc, i -> acc or ((if (o[lo + i] != 0) 1L else 0L) shl i) } }
        val byState = LinkedHashMap<Float, Long>()
        for ((k, s) in states.withIndex()) byState[s] = raws[k]
        val distinctStates = states.distinct().size
        val all = byState.values.distinct().size == distinctStates
        if (!all && distinctStates == 2) return null
        val blinks = obs.any { o -> (lo..hi).any { o[it] == 2 } }
        val direct = width == 1 && byState.all { (s, r) -> s == r.toFloat() }
        val map = if (direct) null else byState.entries.associate { (s, r) -> r to s }
        val score = (if (all) 1000 else 0) - noise * 3 - (width - 1) * 2 - (if (blinks) 40 else 0) - (if (direct) 0 else 1)
        return Candidate(id, lo, width, map, all, noise, blinks, score)
    }

    companion object {
        /**
         * The order to visit [n] positions in. Deliberately uneven: a value that creeps up between
         * captures (a temperature, a counter) has bits that flip in a regular rhythm, and in a
         * regular order like off-on-off-on one of them would look exactly like the switch.
         */
        fun order(n: Int): List<Int> = when (n) {
            2 -> listOf(0, 1, 0, 0, 1, 1)
            4 -> listOf(0, 1, 2, 3, 2, 0, 3, 1)
            else -> (0 until n) + (0 until n).reversed()
        }

        /**
         * The profile [json] with [channel] decoded by [c]: any old definition of the channel is
         * replaced, and the id joins the BODY node if no node lists it yet.
         */
        fun withSignal(json: String, channel: Channel, c: Candidate, note: String): String {
            val root = JSONObject(json)
            val old = root.getJSONArray("signals")
            val signals = JSONArray()
            for (i in 0 until old.length()) {
                val o = old.getJSONObject(i)
                if (o.optString("channel") != channel.key) signals.put(o)
            }
            signals.put(JSONObject().apply {
                put("channel", channel.key)
                put("id", "0x%03X".format(c.id))
                put("bit", c.bit)
                put("bits", c.bits)
                c.map?.let { m -> put("map", JSONObject().apply { for ((raw, v) in m) put(raw.toString(), if (v % 1f == 0f) v.toInt() else v) }) }
                put("note", note)
            })
            root.put("signals", signals)
            val nodes = root.optJSONObject("nodes") ?: JSONObject().also { root.put("nodes", it) }
            val listed = nodes.keys().asSequence().any { k ->
                val a = nodes.getJSONArray(k)
                (0 until a.length()).any { idOf(a.get(it)) == c.id }
            }
            if (!listed) {
                val body = nodes.optJSONArray("BODY") ?: JSONArray().also { nodes.put("BODY", it) }
                body.put("0x%03X".format(c.id))
            }
            return root.toString(2)
        }

        private fun idOf(v: Any): Int = when (v) {
            is Number -> v.toInt()
            else -> v.toString().trim().lowercase().let { if (it.startsWith("0x")) it.substring(2).toInt(16) else it.toInt() }
        }
    }
}
