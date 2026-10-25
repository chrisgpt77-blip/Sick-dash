package com.sickdash.can

/** Parsers for the text formats coming out of the adapters. Pure functions so they can be unit tested. */
object LineParsers {
    /**
     * ELM327 "ATMA" monitor output with ATH1 ATS0 (headers on, spaces off):
     * 11-bit: "23D0A1B2C3D4E5F607" -> id 0x23D, data 0A..07.
     * 29-bit: "18DAF1100341..." (8 hex id chars) when [extended] is set.
     */
    fun parseElmMonitor(line: String, extended: Boolean = false): CanFrame? {
        val s = line.replace(" ", "").uppercase()
        val idLen = if (extended) 8 else 3
        if (s.length < idLen || (s.length - idLen) % 2 != 0) return null
        if ((s.length - idLen) / 2 > 8) return null
        val id = s.substring(0, idLen).toIntOrNull(16) ?: return null
        val data = hexToBytes(s.substring(idLen)) ?: return null
        return CanFrame(id, data, extended)
    }

    /**
     * Lawicel SLCAN: "tIIILDD.." (11-bit) or "TIIIIIIIILDD.." (29-bit). An optional
     * 4-char timestamp after the data is ignored.
     */
    fun parseSlcan(line: String): CanFrame? {
        if (line.isEmpty()) return null
        val ext = when (line[0]) {
            't' -> false
            'T' -> true
            else -> return null
        }
        val idLen = if (ext) 8 else 3
        if (line.length < 1 + idLen + 1) return null
        val id = line.substring(1, 1 + idLen).toIntOrNull(16) ?: return null
        val dlc = Character.digit(line[1 + idLen], 16)
        if (dlc !in 0..8) return null
        val dataStart = 2 + idLen
        if (line.length < dataStart + dlc * 2) return null
        val data = hexToBytes(line.substring(dataStart, dataStart + dlc * 2)) ?: return null
        return CanFrame(id, data, ext)
    }

    /** SLCAN "Sn" command for a bitrate, or null if the adapter has no preset for it. */
    fun slcanBitrateCommand(bitrate: Int): String? = when (bitrate) {
        10_000 -> "S0"; 20_000 -> "S1"; 50_000 -> "S2"; 100_000 -> "S3"
        125_000 -> "S4"; 250_000 -> "S5"; 500_000 -> "S6"; 800_000 -> "S7"
        1_000_000 -> "S8"; else -> null
    }

    /**
     * ELM327 has a single hardware filter (ATCF) + mask (ATCM). Returns the tightest
     * filter/mask pair that still lets every id in [ids] through (11-bit).
     */
    fun elmFilterFor(ids: Collection<Int>): Pair<Int, Int>? {
        if (ids.isEmpty()) return null
        var mask = 0x7FF
        val first = ids.first()
        for (id in ids) mask = mask and (first xor id).inv()
        mask = mask and 0x7FF
        return Pair(first and mask, mask)
    }
}
