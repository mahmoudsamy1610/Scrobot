package com.mahmoud.scrobot

import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/** In-memory log of the last 50 API calls, shown on the main screen. */
object ApiLog {
    private const val MAX = 50
    private val entries = ArrayDeque<String>()
    private val listeners = LinkedHashSet<() -> Unit>()
    private val time = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun add(method: String, path: String, ok: Boolean, error: String?, ms: Long) {
        val line = buildString {
            append(time.format(Date())).append("  ").append(method).append(' ').append(path)
            append(if (ok) "  ok" else "  ERROR: ${error ?: "?"}")
            append("  (").append(ms).append(" ms)")
        }
        note(line)
    }

    fun note(line: String) {
        val snapshot: List<() -> Unit>
        synchronized(this) {
            entries.addFirst(line)
            while (entries.size > MAX) entries.removeLast()
            snapshot = listeners.toList()
        }
        snapshot.forEach { it() }
    }

    @Synchronized
    fun lines(): List<String> = entries.toList()

    @Synchronized
    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    @Synchronized
    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }
}
