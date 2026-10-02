package com.mahmoud.scrobot

import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Runs the two script engines as far as they can go without a phone.
 *
 * The shell engine is exercised end to end: a real `sh` runs the real prelude and the real bridge
 * code answers it. The JavaScript engine is exercised through Node, which runs the exact prelude
 * and wrapper the app feeds to its WebView. What is left untested either way is Android itself:
 * `/system/bin/sh` instead of this machine's shell, and WebView instead of Node.
 */
object EngineTest {

    private var failures = 0

    fun run(): Int {
        failures = 0
        shell()
        javascript()
        println(if (failures == 0) "engines: all checks passed" else "engines: $failures FAILED")
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

    // ---------------------------------------------------------------- shell

    /** The same script the fix was asked to prove: click, type and wait, from a shell script. */
    private val SHELL_SCRIPT = """
        scrobot click "Ask Meta AI or Search" || exit 1
        scrobot type "Mahmoud Samy" field="Search"
        scrobot wait "Chats" 5000
        scrobot screen > nodes.txt
        echo "nodes: ${'$'}(wc -l < nodes.txt)"
        scrobot log "from the script"
        if scrobot find "Nothing"; then echo "unexpected"; else echo "find reported missing"; fi
        echo "argument: ${'$'}1"
        scrobot --json /tap '{"x":540,"y":1200}'
    """.trimIndent()

    private fun shell() {
        val shell = listOf("/bin/sh", "/system/bin/sh", "/bin/dash").firstOrNull { File(it).canExecute() }
        if (shell == null) {
            println("SKIP  shell engine (no shell on this machine)")
            return
        }
        val work = File(System.getProperty("java.io.tmpdir"), "scrobot-engine-${System.nanoTime()}")
        val bridgeDir = File(work, "bridge").apply { mkdirs() }
        File(work, "script.sh").writeText(SHELL_SCRIPT)
        File(work, "prelude.sh").writeText(ShellBridge.PRELUDE)
        File(work, "wrapper.sh").writeText(ShellBridge.WRAPPER)

        val calls = ArrayList<Pair<String, JSONObject>>()
        val logs = ArrayList<String>()
        val bridge = ShellBridge(bridgeDir) { lines ->
            if (lines.firstOrNull() == "log") {
                logs.addAll(lines.drop(1))
                0 to ""
            } else {
                val (endpoint, body) = if (lines.firstOrNull() == "--json") {
                    lines[1] to JSONObject(lines[2])
                } else {
                    ScriptParser.request(ShellBridge.toStep(lines))
                }
                calls.add(endpoint to body)
                // Pretend the screen holds two nodes and that "Nothing" is not on it.
                if (endpoint == "/screen") {
                    0 to "540,1200  id/one  Chats\n600,1400  id/two  Send"
                } else {
                    val reply = JSONObject().put("ok", true).put("endpoint", endpoint)
                    if (endpoint == "/find") reply.put("found", false)
                    ShellBridge.statusOf(endpoint, reply) to reply.toString()
                }
            }
        }

        val process = ProcessBuilder(shell, File(work, "wrapper.sh").absolutePath, "first-argument")
            .directory(work)
            .redirectErrorStream(true)
            .also {
                it.environment()["SCROBOT_BRIDGE"] = bridgeDir.absolutePath
                it.environment()["SCROBOT_PRELUDE"] = File(work, "prelude.sh").absolutePath
                it.environment()["SCROBOT_SCRIPT"] = File(work, "script.sh").absolutePath
            }
            .start()
        val output = ArrayList<String>()
        val reader = Thread {
            process.inputStream.bufferedReader().forEachLine { output.add(it) }
        }.apply { isDaemon = true; start() }
        val server = Thread { bridge.serve { process.isAlive } }.apply { isDaemon = true; start() }

        val finished = process.waitFor(60, TimeUnit.SECONDS)
        if (!finished) process.destroyForcibly()
        reader.join(2000)
        server.interrupt()

        check("shell script finished", finished)
        equal("shell exit status", process.exitValue(), 0)
        equal("shell made every call", calls.size, 6)
        equal("shell click", calls[0].first, "/click")
        equal("shell click selector", calls[0].second.optString("text"), "Ask Meta AI or Search")
        equal("shell type", calls[1].first, "/type")
        equal("shell type text", calls[1].second.optString("text"), "Mahmoud Samy")
        equal("shell type field", calls[1].second.optString("field"), "Search")
        equal("shell wait", calls[2].first, "/wait")
        equal("shell wait timeout", calls[2].second.optLong("timeout"), 5000L)
        equal("shell screen", calls[3].first, "/screen")
        equal("shell find", calls[4].first, "/find")
        equal("shell --json passes through", calls[5].second.optInt("x"), 540)
        check("shell redirected the screen to a file", output.contains("nodes: 2"), output.toString())
        check("a failed find returns non-zero", output.contains("find reported missing"), output.toString())
        check("script arguments arrive", output.contains("argument: first-argument"), output.toString())
        check("scrobot log reaches the app", logs.contains("from the script"), logs.toString())

        work.deleteRecursively()
    }

    // ---------------------------------------------------------------- javascript

    private val JS_SCRIPT = """
        scrobot.click('Ask Meta AI or Search');
        scrobot.type('Mahmoud Samy', 'Search');
        const found = scrobot.find('Chats');
        console.log('found: ' + found.found);
        await scrobot.wait('Chats', 5000);
        const nodes = scrobot.screen();
        console.log('nodes: ' + nodes.length);
        scrobot.log('argument: ' + scrobot.args[0]);
        return 'done';  // a returned value is reported back
    """.trimIndent()

    private fun javascript() {
        val node = listOf("/usr/bin/node", "/usr/local/bin/node", "node").firstOrNull { path ->
            runCatching { ProcessBuilder(path, "--version").start().waitFor() == 0 }.getOrDefault(false)
        }
        if (node == null) {
            println("SKIP  JavaScript engine (Node is not installed)")
            return
        }
        val work = File(System.getProperty("java.io.tmpdir"), "scrobot-js-${System.nanoTime()}").apply { mkdirs() }
        // A stand-in for the Java object the app injects into its WebView.
        val stub = """
            var __calls = [], __logs = [], __result = null;
            var __scrobot = {
              call: function (endpoint, argsJson) {
                __calls.push([endpoint, argsJson]);
                if (endpoint === '/screen') return JSON.stringify({ ok: true, nodes: [{ text: 'Chats' }, { text: 'Send' }] });
                if (endpoint === '/find') return JSON.stringify({ ok: true, found: true });
                if (endpoint === '/nope') return JSON.stringify({ ok: false, error: 'no such node' });
                return JSON.stringify({ ok: true, endpoint: endpoint });
              },
              log: function (line) { __logs.push(line); },
              stopped: function () { return false; },
              finish: function (ok, message) { __result = { ok: ok, message: message }; }
            };
            // Written straight to stderr: the prelude replaces console.error on purpose.
            process.on('exit', function () {
              process.stderr.write(JSON.stringify({ calls: __calls, logs: __logs, result: __result }) + '\n');
            });
        """.trimIndent()

        fun runJs(script: String): JSONObject {
            val file = File(work, "program-${System.nanoTime()}.js")
            file.writeText(stub + "\n" + JsBridge.program(script, listOf("first-argument")))
            val process = ProcessBuilder(node, file.absolutePath).redirectErrorStream(false).start()
            val stderr = process.errorStream.bufferedReader().readText()
            val stdout = process.inputStream.bufferedReader().readText()
            process.waitFor(60, TimeUnit.SECONDS)
            if (stdout.isNotBlank()) println("      node stdout: ${stdout.trim()}")
            val last = stderr.trim().lines().lastOrNull { it.startsWith("{") }
                ?: throw IllegalStateException("node printed nothing useful: $stderr")
            return JSONObject(last)
        }

        val result = runJs(JS_SCRIPT)
        val calls = result.getJSONArray("calls")
        val logs = result.getJSONArray("logs")
        val ended = result.optJSONObject("result")

        check("the script ran to the end", ended != null && ended.optBoolean("ok"), result.toString())
        equal("its value is reported", ended?.optString("message"), "done")
        equal("every call was made", calls.length(), 5)
        equal("js click", calls.getJSONArray(0).getString(0), "/click")
        check(
            "js click selector",
            calls.getJSONArray(0).getString(1).contains("Ask Meta AI or Search"),
            calls.getJSONArray(0).getString(1),
        )
        equal("js type", calls.getJSONArray(1).getString(0), "/type")
        check(
            "js type text and field",
            JSONObject(calls.getJSONArray(1).getString(1)).optString("field") == "Search" &&
                JSONObject(calls.getJSONArray(1).getString(1)).optString("text") == "Mahmoud Samy",
            calls.getJSONArray(1).getString(1),
        )
        equal("js find", calls.getJSONArray(2).getString(0), "/find")
        equal("js wait", calls.getJSONArray(3).getString(0), "/wait")
        equal("js wait timeout", JSONObject(calls.getJSONArray(3).getString(1)).optLong("timeout"), 5000L)
        equal("js screen", calls.getJSONArray(4).getString(0), "/screen")
        check("console.log reaches the app", logs.toString().contains("found: true"), logs.toString())
        check("screen returns nodes", logs.toString().contains("nodes: 2"), logs.toString())
        check("script arguments arrive", logs.toString().contains("argument: first-argument"), logs.toString())

        // A failing endpoint has to fail the run, not pass silently.
        val failed = runJs("scrobot.call('/nope', {});")
        val failedResult = failed.optJSONObject("result")
        check("a failed call fails the run", failedResult != null && !failedResult.optBoolean("ok"), failed.toString())
        check(
            "the failure says why",
            failedResult?.optString("message")?.contains("no such node") == true,
            failedResult.toString(),
        )

        // An error thrown by the script itself must be reported too.
        val threw = runJs("throw new Error('on purpose');")
        check(
            "a thrown error fails the run",
            threw.optJSONObject("result")?.optString("message")?.contains("on purpose") == true,
            threw.toString(),
        )

        // A script ending in a comment must still be wrapped correctly.
        val commented = runJs("scrobot.key('back'); // trailing comment")
        check(
            "a trailing comment does not break the wrapper",
            commented.optJSONObject("result")?.optBoolean("ok") == true,
            commented.toString(),
        )

        work.deleteRecursively()
    }
}
