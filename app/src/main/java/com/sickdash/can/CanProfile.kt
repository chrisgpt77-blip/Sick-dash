package com.sickdash.can

import com.sickdash.data.Channel
import org.json.JSONArray
import org.json.JSONObject

/**
 * How to pull one value out of a CAN frame.
 *
 * raw = unsigned (or signed) integer built from [length] bytes starting at [start],
 * then optionally `(raw >> shift) & mask`, and finally `value = raw * scale + offset`.
 * When [bitLength] > 0 the raw value is instead [bitLength] bits starting at bit [start] of the
 * frame read as one little-endian number (RaceChrono's `bitsToUIntLe(raw, start, len)`).
 * When [map] is set the raw integer is looked up in it instead (unlisted values are ignored).
 * Raw values above [rawMax] are treated as "not available"; the result is clamped to [min]..[max].
 */
data class SignalDef(
    val channel: Channel,
    val id: Int,
    val start: Int,
    val length: Int,
    val bigEndian: Boolean,
    val signed: Boolean,
    val scale: Float,
    val offset: Float,
    val shift: Int,
    val mask: Long,
    val map: Map<Long, Float>? = null,
    val bitLength: Int = 0,
    val rawMax: Long = Long.MAX_VALUE,
    val min: Float = Float.NEGATIVE_INFINITY,
    val max: Float = Float.POSITIVE_INFINITY,
) {
    fun decode(frame: CanFrame): Float? {
        var raw: Long
        if (bitLength > 0) {
            if ((start + bitLength + 7) / 8 > frame.data.size) return null
            var le = 0L
            for (i in frame.data.indices.reversed()) le = (le shl 8) or frame.u8(i).toLong()
            raw = (le ushr start) and ((1L shl bitLength) - 1)
            if (signed && raw and (1L shl (bitLength - 1)) != 0L) raw -= 1L shl bitLength
        } else {
            if (start + length > frame.data.size) return null
            raw = 0L
            for (i in 0 until length) {
                val b = frame.u8(if (bigEndian) start + i else start + length - 1 - i).toLong()
                raw = (raw shl 8) or b
            }
            if (signed && mask < 0) {
                val bits = length * 8
                if (raw and (1L shl (bits - 1)) != 0L) raw -= 1L shl bits
            }
        }
        if (shift > 0) raw = raw shr shift
        if (mask >= 0) raw = raw and mask
        if (raw > rawMax) return null
        if (map != null) return map[raw]
        return (raw * scale + offset).coerceIn(min, max)
    }
}

class CanProfile(
    val name: String,
    val bitrate: Int,
    val signals: List<SignalDef>,
    /** Which module sends which ids, for the boot-time "CAN nodes" check. */
    val nodes: Map<String, List<Int>> = emptyMap(),
) {
    private val byId: Map<Int, List<SignalDef>> = signals.groupBy { it.id }

    val ids: Set<Int> get() = byId.keys

    fun decode(frame: CanFrame, sink: (Channel, Float) -> Unit) {
        val defs = byId[frame.id] ?: return
        for (d in defs) d.decode(frame)?.let { sink(d.channel, it) }
    }

    companion object {
        /** Parses the JSON profile format documented in assets/profiles/nissan_370z.json. */
        fun parse(json: String): CanProfile {
            val root = JSONObject(json)
            val arr: JSONArray = root.getJSONArray("signals")
            val list = ArrayList<SignalDef>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                if (o.optBoolean("disabled", false)) continue
                val key = o.getString("channel")
                val channel = Channel.fromKey(key)
                    ?: throw IllegalArgumentException("Unknown channel '$key' in signal #${i + 1}")
                val length = o.optInt("length", 1)
                require(length in 1..8) { "length must be 1..8 in signal #${i + 1}" }
                val bits = o.optInt("bits", 0)
                require(bits in 0..63) { "bits must be 1..63 in signal #${i + 1}" }
                list += SignalDef(
                    channel = channel,
                    id = parseInt(o.get("id")),
                    start = if (bits > 0) o.getInt("bit") else o.getInt("start"),
                    length = length,
                    bigEndian = o.optString("endian", "big").lowercase() != "little",
                    signed = o.optBoolean("signed", false),
                    scale = o.optDouble("scale", 1.0).toFloat(),
                    offset = o.optDouble("offset", 0.0).toFloat(),
                    shift = o.optInt("shift", 0),
                    mask = if (o.has("mask")) parseInt(o.get("mask")).toLong() else -1L,
                    map = o.optJSONObject("map")?.let { m ->
                        m.keys().asSequence().associate { k -> parseInt(k).toLong() to m.getDouble(k).toFloat() }
                    },
                    bitLength = bits,
                    rawMax = if (o.has("rawMax")) parseInt(o.get("rawMax")).toLong() else Long.MAX_VALUE,
                    min = o.optDouble("min", Double.NEGATIVE_INFINITY).toFloat(),
                    max = o.optDouble("max", Double.POSITIVE_INFINITY).toFloat(),
                )
            }
            val nodes = LinkedHashMap<String, List<Int>>()
            root.optJSONObject("nodes")?.let { n ->
                for (k in n.keys()) {
                    val ids = n.getJSONArray(k)
                    nodes[k] = (0 until ids.length()).map { parseInt(ids.get(it)) }
                }
            }
            return CanProfile(
                name = root.optString("name", "Custom profile"),
                bitrate = root.optInt("bitrate", 500_000),
                signals = list,
                nodes = nodes,
            )
        }

        private fun parseInt(v: Any): Int = when (v) {
            is Number -> v.toInt()
            is String -> {
                val s = v.trim().lowercase()
                if (s.startsWith("0x")) s.substring(2).toInt(16) else s.toInt()
            }
            else -> throw IllegalArgumentException("Bad number: $v")
        }
    }
}
