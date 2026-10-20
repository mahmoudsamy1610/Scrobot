package com.mahmoud.scrobot

import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A small log file inside the app, so a crash can be read on the phone without adb.
 *
 * It keeps uncaught exceptions, server errors and service connect/disconnect events. It also
 * installs the default uncaught-exception handler: a background thread that throws must never take
 * the accessibility service down with it, because Android then puts the service on its "crashed"
 * list and refuses to reconnect it until the user toggles it off and on again.
 *
 * No Android imports, so all of this is unit tested.
 */
object CrashLog {

    /** Roughly a quarter of a megabyte of history, then the file rolls over. */
    const val MAX_BYTES = 192 * 1024

    private val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    @Volatile
    private var file: File? = null

    @Volatile
    private var installed = false

    fun start(target: File) {
        synchronized(this) {
            target.parentFile?.mkdirs()
            file = target
        }
    }

    fun path(): String? = file?.absolutePath

    /**
     * Logs an uncaught exception and then, for background threads, swallows it.
     *
     * [mainThread] is the one thread that cannot carry on after an exception; it is handed to
     * [fallback] (normally Android's own handler, which ends the process). Pass null to treat
     * every thread as recoverable, which is what the tests do.
     */
    fun installHandler(mainThread: Thread?, fallback: Thread.UncaughtExceptionHandler?) {
        synchronized(this) {
            if (installed) return
            installed = true
        }
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                error("uncaught exception on thread \"${thread.name}\"", error)
            } catch (_: Throwable) {
                // Logging must never make things worse.
            }
            if (mainThread != null && thread === mainThread) {
                fallback?.uncaughtException(thread, error)
            }
            // Any other thread simply ends. The process, and with it the accessibility
            // service, stays alive.
        }
    }

    fun note(line: String) {
        append(stamp.format(Date()) + "  " + line)
    }

    fun error(what: String, error: Throwable) {
        val trace = StringWriter()
        error.printStackTrace(PrintWriter(trace))
        append(stamp.format(Date()) + "  " + what + "\n" + trace.toString().trimEnd())
        ApiLog.note("error: $what: $error")
    }

    @Synchronized
    private fun append(text: String) {
        val target = file ?: return
        try {
            if (target.length() > MAX_BYTES) {
                val old = File(target.parentFile, target.name + ".old")
                old.delete()
                target.renameTo(old)
            }
            target.appendText(text + "\n")
        } catch (_: Throwable) {
            // A log that cannot be written is not worth crashing over.
        }
    }

    /** The newest part of the log, for the screen and for sharing. */
    @Synchronized
    fun text(maxChars: Int = 60_000): String {
        val target = file ?: return "No log file yet."
        if (!target.isFile || target.length() == 0L) return "The log is empty."
        return try {
            val whole = target.readText()
            if (whole.length <= maxChars) whole else "…\n" + whole.substring(whole.length - maxChars)
        } catch (e: Throwable) {
            "Could not read the log: $e"
        }
    }

    @Synchronized
    fun clear() {
        val target = file ?: return
        try {
            target.delete()
            File(target.parentFile, target.name + ".old").delete()
        } catch (_: Throwable) {
        }
    }

    /** Runs [body], logging anything it throws instead of letting it escape the thread. */
    fun guard(what: String, body: () -> Unit) {
        try {
            body()
        } catch (e: Throwable) {
            error(what, e)
        }
    }
}
