package com.sickdash.data

import android.content.Context
import java.io.BufferedWriter
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Logs every channel to CSV at 20 Hz while recording; files land in the app's "logs" folder. */
object Recorder {
    @Volatile var dir: File? = null
        private set
    @Volatile var recording = false
        private set
    @Volatile var file: File? = null
        private set
    @Volatile var rows = 0
        private set
    private var out: BufferedWriter? = null
    private var lastRow = 0L
    private var startedAt = 0L

    fun init(context: Context) {
        if (dir == null) dir = (context.getExternalFilesDir("logs") ?: File(context.filesDir, "logs")).also { it.mkdirs() }
    }

    @Synchronized
    fun toggle(now: Long = System.currentTimeMillis()) = if (recording) stop() else start(now)

    @Synchronized
    fun start(now: Long = System.currentTimeMillis()) {
        val d = dir ?: return
        val name = "drive_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(now)) + ".csv"
        val f = File(d, name)
        out = f.bufferedWriter().also { w ->
            w.write("time_s," + Channel.entries.joinToString(",") { it.key })
            w.newLine()
        }
        file = f
        rows = 0
        startedAt = now
        recording = true
        Events.emit(Events.Kind.RECORD, "RECORDING", name, now = now)
    }

    @Synchronized
    fun stop() {
        runCatching { out?.flush(); out?.close() }
        out = null
        if (recording) Events.emit(Events.Kind.RECORD, "SAVED", "$rows ROWS · ${file?.name ?: ""}")
        recording = false
    }

    @Synchronized
    fun sample(s: Snapshot) {
        val w = out ?: return
        if (s.now - lastRow < 50) return
        lastRow = s.now
        val sb = StringBuilder()
        sb.append("%.2f".format(Locale.US, (s.now - startedAt) / 1000.0))
        for (c in Channel.entries) {
            sb.append(',')
            s[c, 1000]?.let { sb.append("%.3f".format(Locale.US, it)) }
        }
        runCatching { w.write(sb.toString()); w.newLine() }
        rows++
        if (rows % 100 == 0) runCatching { w.flush() }
    }

    /** Most recent log file, for sharing from Settings. */
    fun latest(): File? = dir?.listFiles { f -> f.name.endsWith(".csv") }?.maxByOrNull { it.lastModified() }
}
