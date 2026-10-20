package com.mahmoud.scrobot

import org.json.JSONObject
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The stability work: honest status, a log file that survives a crash, threads that cannot take
 * the process down, and a server that reopens its port.
 *
 * All of it runs on a plain JVM. What cannot be checked here is Android's own behaviour — whether
 * it reconnects the accessibility service, and whether Samsung leaves the process alone.
 */
object StabilityTest {

    private var failures = 0

    fun run(): Int {
        failures = 0
        status()
        serviceList()
        crashLog()
        uncaughtExceptions()
        serverRestart()
        serverSurvivesBadHandlers()
        println(if (failures == 0) "stability: all checks passed" else "stability: $failures FAILED")
        return failures
    }

    private fun check(name: String, condition: Boolean, detail: String = "") {
        if (condition) {
            println("PASS  $name")
        } else {
            println("FAIL  $name ${if (detail.isEmpty()) "" else "- $detail"}")
            failures++
        }
    }

    private fun <T> equal(name: String, actual: T, expected: T) =
        check(name, actual == expected, "got <$actual>, wanted <$expected>")

    // ---------------------------------------------------------------- status

    private fun status() {
        val working = Status.of(enabledInSettings = true, connected = true, serverRunning = true)
        equal("working is OK", working.level, Status.Level.OK)
        check("working has no reason", working.isWorking)
        check("working says where to reach it", working.detail.contains("127.0.0.1:8765"), working.detail)

        // The case this release is about: Settings shows ON, Android never reconnected it.
        val stuck = Status.of(enabledInSettings = true, connected = false, serverRunning = true)
        equal("switched on but not connected is an error", stuck.level, Status.Level.ERROR)
        check("it is not reported as working", !stuck.isWorking)
        check(
            "it tells the user to toggle the switch",
            stuck.detail.contains("Turn Scrobot off and on"),
            stuck.detail,
        )
        equal("ping explains the same thing", stuck.reason, Status.NOT_CONNECTED)

        val off = Status.of(enabledInSettings = false, connected = false, serverRunning = true)
        equal("switched off is an error", off.level, Status.Level.ERROR)
        check("switched off is not the same message", off.reason != stuck.reason, off.reason ?: "")
        check("switched off says to turn it on", off.detail.contains("Turn on Scrobot"), off.detail)

        val noPort = Status.of(true, connected = true, serverRunning = false, serverError = "Address in use")
        equal("a port that will not open is a warning", noPort.level, Status.Level.WARNING)
        check("the port error is shown", noPort.detail.contains("Address in use"), noPort.detail)
        check("a broken port is not working", !noPort.isWorking)
    }

    // ---------------------------------------------------------------- the settings list

    private fun serviceList() {
        val others = "com.other/.Service:com.third/com.third.Service"
        val withUs = "$others:${AccessibilityList.FORMS[0]}"

        check("finds itself in the list", AccessibilityList.contains(withUs))
        check("finds the short form too", AccessibilityList.contains("com.mahmoud.scrobot/.HandsService"))
        check("is absent from a list without it", !AccessibilityList.contains(others))
        check("an empty list contains nothing", !AccessibilityList.contains(""))
        check("a missing list contains nothing", !AccessibilityList.contains(null))

        equal("removing keeps the other services", AccessibilityList.without(withUs), others)
        equal("removing twice is the same", AccessibilityList.without(others), others)
        check("adding puts it back", AccessibilityList.contains(AccessibilityList.with(others)))
        equal("adding keeps the others", AccessibilityList.with(others).substringBeforeLast(':'), others)
        equal(
            "adding never duplicates",
            AccessibilityList.entries(AccessibilityList.with(withUs)).count { it in AccessibilityList.FORMS },
            1,
        )
        equal("the off-then-on round trip is clean", AccessibilityList.with(AccessibilityList.without(withUs)), withUs)
    }

    // ---------------------------------------------------------------- the log file

    private fun crashLog() {
        val dir = File(System.getProperty("java.io.tmpdir"), "scrobot-log-${System.nanoTime()}")
        val file = File(dir, "scrobot.log")
        CrashLog.start(file)
        CrashLog.clear()

        CrashLog.note("service connected")
        CrashLog.error("something broke", IllegalStateException("on purpose"))
        val text = CrashLog.text()
        check("notes are kept", text.contains("service connected"), text)
        check("errors are kept", text.contains("something broke"), text)
        check("the stack trace is kept", text.contains("IllegalStateException: on purpose"), text)
        check("entries are timestamped", Regex("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}").containsMatchIn(text))

        // guard() is what every thread body is wrapped in.
        var reached = false
        CrashLog.guard("a thread that throws") {
            throw RuntimeException("thrown inside guard")
        }
        reached = true
        check("guard swallows the error", reached)
        check("guard logs it", CrashLog.text().contains("thrown inside guard"), CrashLog.text())

        // The log must not grow without end.
        repeat(400) { CrashLog.note("x".repeat(600)) }
        check("the log rolls over instead of growing", file.length() < CrashLog.MAX_BYTES * 2L, "${file.length()} bytes")
        check("the previous log is kept once", File(dir, "scrobot.log.old").isFile)
        check("reading it back is bounded", CrashLog.text(1000).length <= 1002, CrashLog.text(1000).length.toString())

        CrashLog.clear()
        check("clearing empties it", CrashLog.text().contains("empty"), CrashLog.text())
        dir.deleteRecursively()
    }

    private fun uncaughtExceptions() {
        val dir = File(System.getProperty("java.io.tmpdir"), "scrobot-uncaught-${System.nanoTime()}")
        CrashLog.start(File(dir, "scrobot.log"))
        CrashLog.clear()
        CrashLog.installHandler(mainThread = null, fallback = null)

        // A thread that throws with nothing catching it: the handler has to log it, and the rest
        // of the program has to carry on. On the phone this is the difference between a logged
        // problem and Android marking the accessibility service as crashed.
        val done = CountDownLatch(1)
        val thread = Thread({
            try {
                throw IllegalArgumentException("escaped the thread")
            } finally {
                done.countDown()
            }
        }, "scrobot-test-thread")
        thread.start()
        done.await(5, TimeUnit.SECONDS)
        thread.join(5000)

        check("the program is still running", true)
        check(
            "an uncaught exception is logged",
            CrashLog.text().contains("escaped the thread"),
            CrashLog.text(),
        )
        check(
            "the thread it came from is named",
            CrashLog.text().contains("scrobot-test-thread"),
            CrashLog.text(),
        )
        CrashLog.clear()
        dir.deleteRecursively()
    }

    // ---------------------------------------------------------------- the server

    private fun server(port: Int, handler: (HttpRequest) -> HttpResponse) =
        HttpServer(port, handler, onError = { what, e -> CrashLog.error(what, e) })

    private fun ok(request: HttpRequest) = HttpServer.textResponse(200, "ok ${request.path}")

    private fun get(port: Int, path: String = "/x"): String {
        java.net.Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 5000
            socket.getOutputStream().write("GET $path HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n".toByteArray())
            socket.getOutputStream().flush()
            return socket.getInputStream().readBytes().toString(Charsets.UTF_8)
        }
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun serverRestart() {
        val port = freePort()
        val first = server(port, ::ok)
        first.start()
        check("the server answers", get(port).contains("200"), "")
        first.stop()

        // Without SO_REUSEADDR this is where a restart fails with "Address already in use",
        // which is what left the app dead after a crash.
        val second = server(port, ::ok)
        var rebound = true
        try {
            second.start()
        } catch (e: Exception) {
            rebound = false
            check("the port can be reopened at once", false, e.toString())
        }
        if (rebound) {
            check("the port can be reopened at once", true)
            check("the new server answers", get(port).contains("200"))
            second.stop()
        }

        // A port someone else holds must fail with a readable error, and work once it is free.
        val taken = ServerSocket(port)
        val third = server(port, ::ok)
        var message: String? = null
        try {
            third.start()
        } catch (e: Exception) {
            message = e.message ?: e.toString()
        }
        check("a busy port reports why", message != null, "no error was raised")
        taken.close()
        try {
            third.start()
            check("it starts once the port is free", third.isRunning)
            third.stop()
        } catch (e: Exception) {
            check("it starts once the port is free", false, e.toString())
        }
    }

    private fun serverSurvivesBadHandlers() {
        val port = freePort()
        // A handler that throws an Error, not an Exception: the kind that normally ends a process.
        val router = Router({ "T" }, { _, _, _, _, _ -> })
        router.route("/boom") { throw OutOfMemoryError("pretend") }
        router.route("/fine") { JSONObject().put("alive", true) }
        val running = server(port, router::handle)
        running.start()

        val boom = try {
            java.net.Socket("127.0.0.1", port).use { socket ->
                socket.soTimeout = 5000
                socket.getOutputStream().write("GET /boom HTTP/1.1\r\nX-Token: T\r\nConnection: close\r\n\r\n".toByteArray())
                socket.getInputStream().readBytes().toString(Charsets.UTF_8)
            }
        } catch (e: Exception) {
            "connection failed: $e"
        }
        check("a handler that throws gives an error response", boom.contains("500"), boom.lines().firstOrNull() ?: "")

        val after = java.net.Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 5000
            socket.getOutputStream().write("GET /fine HTTP/1.1\r\nX-Token: T\r\nConnection: close\r\n\r\n".toByteArray())
            socket.getInputStream().readBytes().toString(Charsets.UTF_8)
        }
        check("the server is still serving afterwards", after.contains("\"alive\":true"), after.lines().lastOrNull() ?: "")
        check("and still running", running.isRunning)
        running.stop()
    }
}
