package com.mahmoud.scrobot

import android.content.Context
import android.os.SystemClock
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * Runs a `.sh` script with Android's own shell (`/system/bin/sh`, which is mksh — not bash).
 *
 * Scripts reach the API through the `scrobot` shell function in [ShellBridge]: Android's shell has
 * no HTTP client, so the function passes each command to the app through files instead.
 *
 * Android does not let an app execute files it has written, so the script is read by the shell
 * (`sh wrapper.sh`) rather than executed, and the helper is a shell function, not a command.
 */
class ShellRunner(
    private val context: Context,
    private val api: Api,
    private val job: ScriptRunner.Job,
) {
    fun run(source: String, args: List<String>, deadline: Long) {
        val work = File(context.cacheDir, "shell/${job.id}").apply { mkdirs() }
        val bridgeDir = File(work, "bridge").apply { mkdirs() }
        val script = File(work, "script.sh").apply { writeText(source) }
        val prelude = File(work, "prelude.sh").apply { writeText(ShellBridge.PRELUDE) }
        val wrapper = File(work, "wrapper.sh").apply { writeText(ShellBridge.WRAPPER) }

        val command = ArrayList<String>()
        command.add(SHELL)
        command.add(wrapper.absolutePath)
        command.addAll(args)

        val builder = ProcessBuilder(command)
        builder.directory(work)
        builder.redirectErrorStream(true)
        builder.environment().apply {
            put("SCROBOT_BRIDGE", bridgeDir.absolutePath)
            put("SCROBOT_PRELUDE", prelude.absolutePath)
            put("SCROBOT_SCRIPT", script.absolutePath)
            put("SCROBOT_URL", "http://127.0.0.1:${Prefs.PORT}")
            put("SCROBOT_TOKEN", Prefs.token(context))
            put("SCROBOT_SCRIPTS", ScriptStore.dir(context).absolutePath)
            put("SCROBOT_SHOTS", ScriptStore.shotsDir(context).absolutePath)
            put("HOME", work.absolutePath)
            put("TMPDIR", work.absolutePath)
        }

        val process = try {
            builder.start()
        } catch (e: Exception) {
            throw ApiError("could not start $SHELL: $e", 500)
        }
        val reader = Thread({
            try {
                BufferedReader(InputStreamReader(process.inputStream)).forEachLine { job.log(it) }
            } catch (_: Exception) {
            }
        }, "scrobot-sh-out-${job.id}").apply { isDaemon = true; start() }
        val bridge = ShellBridge(bridgeDir, ::answer)
        val watcher = Thread({ bridge.serve { process.isAlive } }, "scrobot-sh-bridge-${job.id}")
            .apply { isDaemon = true; start() }

        try {
            while (process.isAlive) {
                if (job.stopRequested) throw ApiError("stopped")
                if (SystemClock.uptimeMillis() > deadline) throw ApiError("script timed out", 408)
                process.waitFor(100, TimeUnit.MILLISECONDS)
            }
            job.exit = process.exitValue()
            reader.join(2000)
            if (job.exit != 0) throw ScriptError("the shell script exited with status ${job.exit}")
        } finally {
            process.destroyForcibly()
            watcher.interrupt()
            work.deleteRecursively()
        }
    }

    /** Answers one `scrobot ...` call from the script. */
    private fun answer(lines: List<String>): Pair<Int, String> {
        if (lines.isEmpty()) return 2 to "scrobot: no command"
        if (job.stopRequested) return 2 to "scrobot: stopped"
        return try {
            val verb = lines[0].trim()
            if (verb == "log") {
                lines.drop(1).forEach { job.log(it) }
                return 0 to ""
            }
            val (endpoint, body) = if (verb == "--json") {
                val target = lines.getOrNull(1)?.trim() ?: throw ApiError("--json needs an endpoint")
                val json = lines.getOrNull(2)?.takeIf { it.isNotBlank() } ?: "{}"
                (if (target.startsWith("/")) target else "/$target") to JSONObject(json)
            } else {
                ScriptParser.request(ShellBridge.toStep(lines))
            }
            when (val result = api.invoke(endpoint, body)) {
                is HttpResponse ->
                    if (result.contentType.startsWith("text/")) 0 to String(result.body, Charsets.UTF_8).trimEnd()
                    else 0 to JSONObject().put("ok", true).put("bytes", result.body.size).toString()
                is JSONObject -> ShellBridge.statusOf(endpoint, result) to result.put("ok", true).toString()
                else -> 0 to result.toString()
            }
        } catch (e: ApiError) {
            1 to "scrobot: ${e.message}"
        } catch (e: ScriptError) {
            1 to "scrobot: ${e.message}"
        } catch (e: Throwable) {
            1 to "scrobot: $e"
        }
    }

    companion object {
        private const val SHELL = "/system/bin/sh"
    }
}
