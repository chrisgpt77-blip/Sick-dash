package com.sickdash.can

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.InputStream

/**
 * Splits the adapter byte stream into lines on CR/LF. The ELM327 '>' prompt is
 * delivered as its own [PROMPT] token so the caller knows a command finished.
 */
class LineReader(scope: CoroutineScope, input: InputStream) {
    private val lines = Channel<String>(8192, BufferOverflow.DROP_OLDEST)

    @Volatile var closedReason: Throwable? = null
        private set

    init {
        scope.launch(Dispatchers.IO) {
            val buf = ByteArray(1024)
            val sb = StringBuilder()
            try {
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    for (i in 0 until n) {
                        when (val c = (buf[i].toInt() and 0xFF).toChar()) {
                            '\r', '\n' -> if (sb.isNotEmpty()) { lines.trySend(sb.toString()); sb.setLength(0) }
                            '>' -> {
                                if (sb.isNotEmpty()) { lines.trySend(sb.toString()); sb.setLength(0) }
                                lines.trySend(PROMPT)
                            }
                            '\u0007' -> lines.trySend(BELL)
                            '\u0000' -> Unit
                            else -> sb.append(c)
                        }
                    }
                }
                closedReason = java.io.EOFException("Adapter closed the connection")
            } catch (e: Exception) {
                closedReason = e
            } finally {
                lines.close(closedReason)
            }
        }
    }

    /** Next line, or null on timeout. Throws if the connection dropped. */
    suspend fun next(timeoutMs: Long): String? = withTimeoutOrNull(timeoutMs) { lines.receive() }

    fun drain() {
        while (lines.tryReceive().isSuccess) Unit
    }

    companion object {
        const val PROMPT = ">"
        const val BELL = "\u0007"
    }
}
