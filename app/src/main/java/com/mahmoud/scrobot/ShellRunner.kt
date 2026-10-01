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
 * Scripts call the API through a `scrobot` shell function. It does not need curl or any other
 * network tool: the function hands each command to the app through small files in a bridge folder
 * and waits for the reply. That matters because Android has no HTTP client in the shell.
 *
 * Android does not allow an app to execute files it has written, so the script is read by the
 * shell (`sh wrapper.sh`) rather than executed, and the helper is a shell function, not a command.
 */
class ShellRunner(
    private val context: Context,
    private val api: Api,
    private val job: ScriptRunner.Job,
) {
    fun run(source: String, args: List<String>, deadline: Long) {
        val work = File(context.cacheDir, "shell/${job.id}").apply { mkdirs() }
        val bridge = File(work, "bridge").apply { mkdirs() }
        val script = File(work, "script.sh").apply { writeText(source) }
        val prelude = File(work, "prelude.sh").apply { writeText(PRELUDE) }
        val wrapper = File(work, "wrapper.sh").apply {
            // Sourcing keeps the script's own line numbers and its "$1", "$2" arguments.
            writeText(". \"\$SCROBOT_PRELUDE\"\n. \"\$SCROBOT_SCRIPT\"\n")
        }

        val command = ArrayList<String>()
        command.add("/system/bin/sh")
        command.add(wrapper.absolutePath)
        command.addAll(args)

        val builder = ProcessBuilder(command)
        builder.directory(work)
        builder.redirectErrorStream(true)
        builder.environment().apply {
            put("SCROBOT_BRIDGE", bridge.absolutePath)
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
            throw ApiError("could not start /system/bin/sh: $e", 500)
        }
        val reader = Thread({
            try {
                BufferedReader(InputStreamReader(process.inputStream)).forEachLine { job.log(it) }
            } catch (_: Exception) {
            }
        }, "scrobot-sh-out-${job.id}").apply { isDaemon = true; start() }
        val watcher = Thread({ serveBridge(bridge, process) }, "scrobot-sh-bridge-${job.id}")
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

    /** Answers `scrobot ...` calls: each one arrives as a `req.*` file and is answered with `res.*`. */
    private fun serveBridge(bridge: File, process: Process) {
        while (!Thread.currentThread().isInterrupted && process.isAlive) {
            val requests = bridge.listFiles { f -> f.isFile && f.name.startsWith("req.") }
            if (requests == null || requests.isEmpty()) {
                try {
                    Thread.sleep(40)
                } catch (e: InterruptedException) {
                    return
                }
                continue
            }
            for (request in requests.sortedBy { it.lastModified() }) {
                val id = request.name.removePrefix("req.")
                val lines = try {
                    request.readLines()
                } catch (e: Exception) {
                    emptyList()
                }
                request.delete()
                val (status, body) = answer(lines)
                val reply = File(bridge, "res.$id.part")
                try {
                    reply.writeText(status.toString() + "\n" + body + "\n")
                    reply.renameTo(File(bridge, "res.$id"))
                } catch (e: Exception) {
                    reply.delete()
                }
            }
        }
    }

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
                val step = buildStep(verb, lines.drop(1))
                ScriptParser.request(step)
            }
            when (val result = api.invoke(endpoint, body)) {
                is HttpResponse ->
                    if (result.contentType.startsWith("text/")) 0 to String(result.body, Charsets.UTF_8).trimEnd()
                    else 0 to JSONObject().put("ok", true).put("bytes", result.body.size).toString()
                is JSONObject -> 0 to result.put("ok", true).toString()
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

    /** The shell passes one argument per line, so the command needs no quoting rules here. */
    private fun buildStep(verb: String, rest: List<String>): Step {
        val args = ArrayList<String>()
        val named = LinkedHashMap<String, String>()
        val pattern = Regex("^[A-Za-z_][A-Za-z0-9_]*=")
        for (value in rest) {
            val match = pattern.find(value)
            if (match != null) named[value.substring(0, match.value.length - 1)] = value.substring(match.value.length)
            else args.add(value)
        }
        return Step(verb.lowercase(), args, named, 0)
    }

    companion object {
        /**
         * Sourced before the script: defines `scrobot`, which writes the command to the bridge
         * folder and waits for the app's reply. Prints the reply and returns the app's status.
         */
        val PRELUDE = """
            # Scrobot shell helper. Usage:
            #   scrobot tap 540 1200
            #   scrobot click "Send"
            #   scrobot type "Hello" field="Message"
            #   scrobot screen                  # prints the screen, one node per line
            #   scrobot --json /tap '{"x":1,"y":2}'
            #   scrobot log "a message for the app's log"
            SCROBOT_SEQ=0
            scrobot() {
              if [ -z "${'$'}SCROBOT_BRIDGE" ]; then
                echo "scrobot: this script is not running inside Scrobot" >&2
                return 2
              fi
              SCROBOT_SEQ=${'$'}((SCROBOT_SEQ + 1))
              _sid="${'$'}${'$'}.${'$'}SCROBOT_SEQ"
              _req="${'$'}SCROBOT_BRIDGE/req.${'$'}_sid"
              _res="${'$'}SCROBOT_BRIDGE/res.${'$'}_sid"
              rm -f "${'$'}_res"
              : > "${'$'}_req.part"
              for _a in "${'$'}@"; do printf '%s\n' "${'$'}_a" >> "${'$'}_req.part"; done
              mv "${'$'}_req.part" "${'$'}_req"
              _n=0
              while [ ! -f "${'$'}_res" ]; do
                _n=${'$'}((_n + 1))
                if [ ${'$'}_n -gt 6000 ]; then
                  echo "scrobot: the app did not answer" >&2
                  return 2
                fi
                sleep 0.05 2>/dev/null || sleep 1
              done
              _status=${'$'}(head -n 1 "${'$'}_res")
              sed -n '2,${'$'}p' "${'$'}_res"
              rm -f "${'$'}_res"
              return "${'$'}_status"
            }
        """.trimIndent()
    }
}
