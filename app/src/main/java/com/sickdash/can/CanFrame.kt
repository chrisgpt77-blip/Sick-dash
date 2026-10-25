package com.sickdash.can

class CanFrame(val id: Int, val data: ByteArray, val extended: Boolean = false) {
    fun u8(i: Int): Int = data[i].toInt() and 0xFF

    override fun toString(): String =
        "%03X [%d] %s".format(id, data.size, data.joinToString(" ") { "%02X".format(it) })
}

internal fun hexToBytes(hex: String): ByteArray? {
    if (hex.length % 2 != 0) return null
    val out = ByteArray(hex.length / 2)
    for (i in out.indices) {
        val hi = Character.digit(hex[i * 2], 16)
        val lo = Character.digit(hex[i * 2 + 1], 16)
        if (hi < 0 || lo < 0) return null
        out[i] = ((hi shl 4) or lo).toByte()
    }
    return out
}
