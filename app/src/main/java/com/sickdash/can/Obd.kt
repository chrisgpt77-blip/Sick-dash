package com.sickdash.can

import com.sickdash.data.Channel

/** Standard SAE J1979 mode 01 PIDs the dash polls through an ELM327. */
enum class ObdPid(val pid: Int, val channel: Channel, val bytes: Int, val fast: Boolean, val decode: (IntArray) -> Float) {
    RPM(0x0C, Channel.RPM, 2, true, { (it[0] * 256 + it[1]) / 4f }),
    SPEED(0x0D, Channel.SPEED, 1, true, { it[0].toFloat() }),
    THROTTLE(0x11, Channel.THROTTLE, 1, true, { it[0] * 100f / 255f }),
    COOLANT(0x05, Channel.COOLANT, 1, false, { it[0] - 40f }),
    INTAKE(0x0F, Channel.INTAKE_TEMP, 1, false, { it[0] - 40f }),
    OIL(0x5C, Channel.OIL_TEMP, 1, false, { it[0] - 40f }),
    VOLTAGE(0x42, Channel.BATTERY, 2, false, { (it[0] * 256 + it[1]) / 1000f }),
    FUEL(0x2F, Channel.FUEL, 1, false, { it[0] * 100f / 255f });

    val request: String get() = "01%02X".format(pid)

    companion object {
        /**
         * Extracts the data bytes for [pid] from an ELM327 reply (spaces/headers off).
         * Accepts lines such as "410C1AF8" or "41 0C 1A F8"; returns null when absent.
         */
        fun parseReply(lines: List<String>, pid: Int, bytes: Int): IntArray? {
            val prefix = "41%02X".format(pid)
            for (raw in lines) {
                val line = raw.replace(" ", "").uppercase()
                val at = line.indexOf(prefix)
                if (at < 0) continue
                val hex = line.substring(at + prefix.length)
                if (hex.length < bytes * 2) continue
                val data = hexToBytes(hex.substring(0, bytes * 2)) ?: continue
                return IntArray(bytes) { data[it].toInt() and 0xFF }
            }
            return null
        }

        /** Decodes a "supported PIDs" bitmap reply (PIDs base+1 .. base+32). */
        fun parseSupported(lines: List<String>, base: Int): Set<Int>? {
            val bits = parseReply(lines, base, 4) ?: return null
            val out = HashSet<Int>()
            for (i in 0 until 32) {
                if (bits[i / 8] and (0x80 shr (i % 8)) != 0) out += base + i + 1
            }
            return out
        }
    }
}
