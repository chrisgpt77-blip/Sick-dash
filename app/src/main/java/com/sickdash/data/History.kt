package com.sickdash.data

/** Rolling 10 s of samples per channel at 20 Hz, for the live graphs and sparklines. */
object History {
    const val HZ = 20
    const val SIZE = HZ * 10
    private val buffers = Array(Channel.entries.size) { FloatArray(SIZE) { Float.NaN } }
    private var head = 0
    private var lastSample = 0L

    /** Gear-change markers: sample index at which the gear changed, and to what. */
    val gearMarks = ArrayDeque<Pair<Long, Int>>()
    private var lastGear = -99

    @Synchronized
    fun update(s: Snapshot, gear: Int?) {
        if (s.now - lastSample < 1000 / HZ) return
        lastSample = s.now
        for (c in Channel.entries) buffers[c.ordinal][head] = s[c, 1000] ?: Float.NaN
        head = (head + 1) % SIZE
        if (gear != null && gear != lastGear) {
            if (lastGear != -99 && gear > 0) gearMarks.addLast(s.now to gear)
            lastGear = gear
        }
        while (gearMarks.isNotEmpty() && s.now - gearMarks.first().first > 10_000) gearMarks.removeFirst()
    }

    /** Oldest-first copy of the last [n] samples of [c]. */
    @Synchronized
    fun series(c: Channel, n: Int = SIZE): FloatArray {
        val out = FloatArray(n)
        val b = buffers[c.ordinal]
        for (i in 0 until n) out[i] = b[((head - n + i) % SIZE + SIZE) % SIZE]
        return out
    }
}
