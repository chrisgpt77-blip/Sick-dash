package com.sickdash.data

/** Things worth a pop-up on the HUD: engine start, a scored shift, a launch result, clutch slip… */
object Events {
    enum class Kind { BOOT, START, SHIFT, LAUNCH, RESULT, SLIP, OVER_REV, WARNING, LOCK, RECORD, MEDIA }

    class Event(val kind: Kind, val title: String, val sub: String, val at: Long, val strong: Boolean = false)

    @Volatile var latest: Event? = null
        private set
    private val log = ArrayDeque<Event>()

    fun emit(kind: Kind, title: String, sub: String = "", strong: Boolean = false, now: Long = System.currentTimeMillis()) {
        val e = Event(kind, title, sub, now, strong)
        latest = e
        // previews from the Style page aren't things that happened, so they stay out of the log
        if (sub != "PREVIEW") synchronized(log) { log.addLast(e); while (log.size > 30) log.removeFirst() }
    }

    /** The most recent events, newest first. */
    fun recent(n: Int): List<Event> = synchronized(log) { log.reversed().take(n) }

    /** The event to show now, if one fired within [durationMs]. */
    fun current(now: Long, durationMs: Long = 2200): Event? = latest?.takeIf { now - it.at in 0..durationMs }
}
