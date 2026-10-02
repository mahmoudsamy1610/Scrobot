package com.mahmoud.scrobot

import java.io.File

/**
 * How a shell script talks to the app.
 *
 * Android's shell is mksh and has no curl or wget, so the `scrobot` command cannot call the HTTP
 * API. Instead it writes one small file per command into a bridge folder and waits for the reply
 * file. This class is the app's side of that exchange, and [PRELUDE] is the script's side.
 *
 * Plain Kotlin and plain files, so it can be tested against a real shell on any machine.
 */
class ShellBridge(private val dir: File, private val answer: (List<String>) -> Pair<Int, String>) {

    /** Serves requests until [alive] says to stop. Each request file is answered exactly once. */
    fun serve(alive: () -> Boolean) {
        while (alive() && !Thread.currentThread().isInterrupted) {
            // ".part" is the half-written file the shell is still appending to; it becomes a
            // request only once the shell renames it, which is atomic.
            val requests = dir.listFiles { f ->
                f.isFile && f.name.startsWith(REQUEST) && !f.name.endsWith(PARTIAL)
            }
            if (requests == null || requests.isEmpty()) {
                try {
                    Thread.sleep(POLL_MS)
                } catch (e: InterruptedException) {
                    return
                }
                continue
            }
            for (request in requests.sortedBy { it.lastModified() }) {
                serveOne(request)
            }
        }
    }

    fun serveOne(request: File) {
        val id = request.name.removePrefix(REQUEST)
        val lines = try {
            request.readLines()
        } catch (e: Exception) {
            emptyList()
        }
        request.delete()
        val (status, body) = try {
            answer(lines)
        } catch (e: Throwable) {
            1 to "scrobot: $e"
        }
        val reply = File(dir, "$REPLY$id$PARTIAL")
        try {
            reply.writeText(status.toString() + "\n" + body + "\n")
            reply.renameTo(File(dir, "$REPLY$id"))
        } catch (e: Exception) {
            reply.delete()
        }
    }

    companion object {
        private const val REQUEST = "req."
        private const val REPLY = "res."
        private const val PARTIAL = ".part"
        private const val POLL_MS = 40L

        /**
         * Sourced before the script runs: defines `scrobot`, which writes the command to the
         * bridge folder, waits for the reply, prints it, and returns the app's status.
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

        /** Sources the helper and then the script, so the script keeps its own line numbers and `$1`. */
        const val WRAPPER = ". \"\$SCROBOT_PRELUDE\"\n. \"\$SCROBOT_SCRIPT\"\n"

        /**
         * The exit status for a call that worked. A `find` that found nothing is a shell "false",
         * so `if scrobot find "Send"; then ...` reads the way a shell script expects.
         */
        fun statusOf(endpoint: String, reply: org.json.JSONObject): Int =
            if (endpoint == "/find" && !reply.optBoolean("found")) 1 else 0

        /**
         * Splits one request into a step. The shell writes one argument per line, so there are no
         * quoting rules to get wrong here.
         */
        fun toStep(lines: List<String>): Step {
            val verb = lines.firstOrNull()?.trim().orEmpty()
            val args = ArrayList<String>()
            val named = LinkedHashMap<String, String>()
            val pattern = Regex("^[A-Za-z_][A-Za-z0-9_]*=")
            for (value in lines.drop(1)) {
                val match = pattern.find(value)
                if (match != null) named[value.substring(0, match.value.length - 1)] = value.substring(match.value.length)
                else args.add(value)
            }
            return Step(verb.lowercase(), args, named, 0)
        }
    }
}
