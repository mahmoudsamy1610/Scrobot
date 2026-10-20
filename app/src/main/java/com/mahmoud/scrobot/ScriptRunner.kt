package com.mahmoud.scrobot

import android.content.Context
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Runs imported scripts: action scripts and JSON step lists here, JavaScript in [JsRunner] and
 * shell scripts in [ShellRunner]. One script runs at a time.
 */
class ScriptRunner(private val context: Context, private val api: Api) {

    companion object {
        const val DEFAULT_TIMEOUT = 120_000L
        private const val MAX_LOG_LINES = 2000
        private val ids = AtomicInteger(0)
    }

    /** A single run: its log, how it ended, and the flag that asks it to stop. */
    class Job(val id: Int, val name: String, val kind: ScriptStore.Kind) {
        val startedAt = System.currentTimeMillis()
        private val lines = ArrayList<String>()
        @Volatile var finishedAt = 0L
        @Volatile var ok = false
        @Volatile var error: String? = null
        @Volatile var exit = 0
        @Volatile var stopRequested = false
        val done = CountDownLatch(1)

        val running: Boolean get() = done.count > 0L

        fun log(line: String) {
            synchronized(lines) {
                if (lines.size < MAX_LOG_LINES) lines.add(line)
                else if (lines.size == MAX_LOG_LINES) lines.add("... output truncated at $MAX_LOG_LINES lines")
            }
        }

        fun output(): List<String> = synchronized(lines) { ArrayList(lines) }

        fun toJson(): JSONObject {
            val out = JSONObject()
                .put("id", id)
                .put("script", name)
                .put("kind", kind.label)
                .put("status", if (running) "running" else if (ok) "finished" else "failed")
                .put("ms", (if (finishedAt > 0) finishedAt else System.currentTimeMillis()) - startedAt)
                .put("output", JSONArray(output()))
            if (!running) out.put("exit", exit)
            error?.let { out.put("error", it) }
            return out
        }
    }

    @Volatile
    private var current: Job? = null

    val isRunning: Boolean get() = current?.running == true

    fun status(): JSONObject {
        val job = current ?: return JSONObject().put("status", "idle")
        return job.toJson()
    }

    fun stop(): JSONObject {
        val job = current ?: throw ApiError("no script is running", 404)
        if (!job.running) throw ApiError("no script is running", 404)
        job.stopRequested = true
        ApiLog.note("Script \"${job.name}\" was asked to stop")
        return JSONObject().put("stopping", job.id).put("script", job.name)
    }

    /** Endpoint handler for `/script/run`. */
    fun run(a: JSONObject): Any {
        val name = a.optString("name").takeIf { it.isNotEmpty() && !a.isNull("name") }
        val inline = a.optString("source").takeIf { a.has("source") && !a.isNull("source") }
        val label: String
        val kind: ScriptStore.Kind
        val source: String
        if (inline != null) {
            label = name ?: "(inline)"
            kind = if (a.has("kind")) kindOf(a.getString("kind")) else ScriptStore.Kind.of(label)
            source = inline
        } else {
            if (name == null) throw ApiError("give \"name\" (a saved script) or \"source\"")
            val file: File = ScriptStore.find(context, name) ?: throw ApiError("no script called \"$name\"", 404)
            if (file.length() > ScriptStore.MAX_SIZE) throw ApiError("script is too large", 413)
            label = file.name
            kind = ScriptStore.Kind.of(file.name)
            source = file.readText()
        }

        val args = ArrayList<String>()
        a.optJSONArray("args")?.let { for (i in 0 until it.length()) args.add(it.optString(i)) }
        val timeout = a.optLong("timeout", DEFAULT_TIMEOUT).coerceIn(1000, 30 * 60_000)

        synchronized(this) {
            if (isRunning) throw ApiError("\"${current?.name}\" is still running; stop it first", 409)
            val job = Job(ids.incrementAndGet(), label, kind)
            current = job
            Thread({
                // execute() already catches everything; this is the last line of defence so that
                // a bad script can never end the process.
                CrashLog.guard("running script ${job.name}") { execute(job, source, args, timeout) }
            }, "scrobot-script-${job.id}").apply {
                isDaemon = true
                start()
            }
            val asText = a.optString("format") == "text"
            if (a.optBoolean("background", false)) {
                val started = JSONObject().put("started", job.id).put("script", label).put("kind", kind.label)
                return if (asText) HttpServer.textResponse(200, "started $label (id ${job.id})") else started
            }
            // Foreground: wait a little past the script's own timeout before giving up on it.
            if (!job.done.await(timeout + 10_000, TimeUnit.MILLISECONDS)) {
                job.stopRequested = true
                throw ApiError("script \"$label\" did not finish in time", 408)
            }
            return if (asText) asText(job) else job.toJson()
        }
    }

    /** The plain-text rendering `hands run` prints: the script's output, then how it ended. */
    private fun asText(job: Job): HttpResponse {
        val body = StringBuilder()
        job.output().forEach { body.append(it).append('\n') }
        val ms = (if (job.finishedAt > 0) job.finishedAt else System.currentTimeMillis()) - job.startedAt
        if (job.ok) body.append("-- ").append(job.name).append(" finished in ").append(ms).append(" ms")
        else body.append("-- ").append(job.name).append(" failed after ").append(ms).append(" ms: ").append(job.error)
        return HttpServer.textResponse(if (job.ok) 200 else 500, body.toString())
    }

    private fun kindOf(raw: String): ScriptStore.Kind =
        ScriptStore.Kind.entries.firstOrNull { it.label.equals(raw, true) || raw.lowercase() in it.extensions }
            ?: throw ApiError("unknown script kind \"$raw\"; use actions, json, js or sh")

    private fun execute(job: Job, source: String, args: List<String>, timeout: Long) {
        val deadline = SystemClock.uptimeMillis() + timeout
        ApiLog.note("Script \"${job.name}\" started")
        try {
            when (job.kind) {
                ScriptStore.Kind.ACTIONS -> runActions(job, ScriptParser.parse(source), args, deadline)
                ScriptStore.Kind.JSON -> runActions(job, ScriptParser.parseJson(source), args, deadline)
                ScriptStore.Kind.JS -> JsRunner(context, api, job).run(source, args, deadline)
                ScriptStore.Kind.SHELL -> ShellRunner(context, api, job).run(source, args, deadline)
            }
            job.ok = true
        } catch (e: ScriptError) {
            job.error = if (e.line > 0) "line ${e.line}: ${e.message}" else e.message
            job.exit = 1
        } catch (e: ApiError) {
            job.error = e.message
            job.exit = 1
        } catch (e: Throwable) {
            job.error = e.toString()
            job.exit = 1
            CrashLog.error("script \"${job.name}\" failed", e)
        } finally {
            job.finishedAt = System.currentTimeMillis()
            job.done.countDown()
            val how = if (job.ok) "finished" else "failed: ${job.error}"
            ApiLog.note("Script \"${job.name}\" $how (${job.finishedAt - job.startedAt} ms)")
        }
    }

    // ---------------------------------------------------------------- action scripts

    private fun runActions(job: Job, steps: List<Step>, args: List<String>, deadline: Long) {
        val vars = HashMap<String, String>()
        vars["0"] = job.name
        args.forEachIndexed { i, value -> vars[(i + 1).toString()] = value }
        ActionInterpreter(Host(job, deadline)).run(steps, vars)
    }

    /** Connects the interpreter to the API and to this job's log. */
    private inner class Host(private val job: Job, private val deadline: Long) : ActionInterpreter.Host {
        override fun call(endpoint: String, body: JSONObject): String = summarise(api.invoke(endpoint, body))

        override fun found(selector: JSONObject): Boolean =
            (api.invoke("/find", selector) as? JSONObject)?.optBoolean("found") == true

        override fun log(line: String) = job.log(line)

        override fun checkAlive() = checkAlive(job, deadline)
    }

    /** Turns an endpoint's reply into one short log line (whole text for `screen`). */
    private fun summarise(result: Any): String = when (result) {
        is HttpResponse -> if (result.contentType.startsWith("text/")) {
            "\n" + String(result.body, Charsets.UTF_8).trimEnd()
        } else {
            "ok (${result.body.size} bytes)"
        }
        is JSONObject -> {
            val copy = JSONObject(result.toString())
            copy.remove("nodes")
            copy.remove("png")
            copy.remove("jpeg")
            if (copy.length() == 0) "ok" else copy.toString()
        }
        else -> result.toString()
    }

    private fun checkAlive(job: Job, deadline: Long) {
        if (job.stopRequested) throw ApiError("stopped")
        if (SystemClock.uptimeMillis() > deadline) throw ApiError("script timed out", 408)
    }
}
