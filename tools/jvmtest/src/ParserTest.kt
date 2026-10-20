package com.mahmoud.scrobot

import org.json.JSONObject

/**
 * Checks the action-script language on a plain JVM: tokens, blocks, variables, the mapping from
 * commands to API calls, and the way the interpreter walks loops and conditions.
 */
object ParserTest {

    private var failures = 0

    fun run(): Int {
        failures = 0
        tokens()
        namedArguments()
        comments()
        variables()
        requests()
        json()
        blocks()
        interpreter()
        println(if (failures == 0) "parser: all checks passed" else "parser: $failures FAILED")
        return failures
    }

    // ---------------------------------------------------------------- checks

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

    private fun throws(name: String, message: String, body: () -> Unit) {
        try {
            body()
            check(name, false, "no error was raised")
        } catch (e: ScriptError) {
            check(name, e.message?.contains(message) == true, "message was \"${e.message}\"")
        }
    }

    // ---------------------------------------------------------------- cases

    private fun tokens() {
        val step = ScriptParser.parseLine("""click "Ask Meta AI or Search"  2""", 1)!!
        equal("verb", step.verb, "click")
        equal("quoted argument", step.arg(0), "Ask Meta AI or Search")
        equal("second argument", step.arg(1), "2")

        val escapes = ScriptParser.parseLine("type \"line1\\nline2 \\\"q\\\" \\\\ end\"", 1)!!
        equal("escapes", escapes.arg(0), "line1\nline2 \"q\" \\ end")

        val single = ScriptParser.parseLine("""echo 'keeps \n as-is'""", 1)!!
        equal("single quotes are literal", single.arg(0), """keeps \n as-is""")

        val arabic = ScriptParser.parseLine("type \"Hello 👋 مرحبا\"", 1)!!
        equal("Arabic and emoji", arabic.arg(0), "Hello 👋 مرحبا")

        throws("unclosed quote", "unclosed") { ScriptParser.parseLine("""click "oops""", 1) }
        equal("blank line", ScriptParser.parseLine("   ", 1), null)
    }

    private fun namedArguments() {
        val step = ScriptParser.parseLine("""click text="Send me" exact=true index=1""", 1)!!
        equal("named text", step.named["text"], "Send me")
        equal("named exact", step.named["exact"], "true")
        equal("no positional arguments", step.args.size, 0)

        val url = ScriptParser.parseLine("open https://example.com/a?b=1", 1)!!
        equal("a URL is not a named argument", url.arg(0), "https://example.com/a?b=1")

        val quoted = ScriptParser.parseLine("""echo "key=value"""", 1)!!
        equal("quoted key=value stays positional", quoted.arg(0), "key=value")
    }

    private fun comments() {
        val steps = ScriptParser.parse(
            """
            # a comment
            tap 1 2   # trailing comment
            echo "# not a comment"
            """.trimIndent(),
        )
        equal("comments are skipped", steps.size, 2)
        equal("trailing comment removed", steps[0].args, listOf("1", "2"))
        equal("hash inside quotes kept", steps[1].arg(0), "# not a comment")
    }

    private fun variables() {
        val vars = mapOf("name" to "Mahmoud", "1" to "first")
        equal("plain variable", ScriptParser.substitute("hi \$name", vars), "hi Mahmoud")
        equal("braced variable", ScriptParser.substitute("hi \${name}!", vars), "hi Mahmoud!")
        equal("script argument", ScriptParser.substitute("\$1", vars), "first")
        equal("unknown name is empty", ScriptParser.substitute("x\${nope}y", vars), "xy")
        equal("double dollar is literal", ScriptParser.substitute("costs \$\$5", vars), "costs \$5")
        equal("no dollar", ScriptParser.substitute("plain", vars), "plain")
    }

    private fun requests() {
        fun request(line: String): Pair<String, JSONObject> =
            ScriptParser.request(ScriptParser.parseLine(line, 1)!!)

        val (tapPath, tap) = request("tap 540 1200")
        equal("tap endpoint", tapPath, "/tap")
        equal("tap x", tap.getInt("x"), 540)
        equal("tap y", tap.getInt("y"), 1200)

        val (_, swipe) = request("swipe 500 1800 100 300 250")
        equal("swipe target", swipe.getInt("x2"), 100)
        equal("swipe duration", swipe.getLong("ms"), 250L)

        val (clickPath, click) = request("""click-id com.whatsapp:id/send""")
        equal("click-id endpoint", clickPath, "/click")
        equal("click-id selector", click.getString("id"), "com.whatsapp:id/send")

        val (typePath, type) = request("""append "more" "Message"""")
        equal("append endpoint", typePath, "/type")
        equal("append keeps existing text", type.getBoolean("append"), true)
        equal("append field", type.getString("field"), "Message")

        val (openPath, open) = request("open com.whatsapp")
        equal("open endpoint", openPath, "/open")
        equal("open package", open.getString("package"), "com.whatsapp")
        equal("open url", request("open https://x.com/a").second.getString("uri"), "https://x.com/a")

        val (waitPath, wait) = request("""wait-gone "Loading" 3000""")
        equal("wait endpoint", waitPath, "/wait")
        equal("wait-gone flag", wait.getBoolean("gone"), true)
        equal("wait timeout", wait.getLong("timeout"), 3000L)

        val (_, extras) = request("""click "Send" exact=true index=2""")
        equal("named booleans become real booleans", extras.get("exact"), true)
        equal("named numbers become numbers", extras.get("index"), 2L)

        equal("sleep", request("sleep 250").second.getLong("ms"), 250L)
        equal("focus default", request("focus").second.getString("direction"), "next")
        equal("scroll direction", request("scroll backward").second.getString("direction"), "backward")
        equal("screen is text", request("screen").second.getString("format"), "text")

        val (rawPath, raw) = request("""/clipboard text="hi"""")
        equal("an endpoint can be called directly", rawPath, "/clipboard")
        equal("direct endpoint argument", raw.getString("text"), "hi")

        // The same commands written with names, which is how JSON scripts and `scrobot tap x=1` arrive.
        equal("named coordinates", request("tap x=540 y=1200").second.getInt("x"), 540)
        equal("named text", request("""click text="Send"""").second.getString("text"), "Send")
        equal("named timeout", request("""wait text="Chats" timeout=3000""").second.getLong("timeout"), 3000L)

        throws("unknown command", "unknown command") { request("fly 1 2") }
        throws("missing argument", "needs") { request("click-id") }
        throws("bad number", "not a number") { request("tap x 2") }
    }

    private fun json() {
        val steps = ScriptParser.parseJson(
            """{"steps":[
                 {"action":"open","package":"com.whatsapp"},
                 {"action":"tap","x":540,"y":1200},
                 {"action":"type","args":["hi"],"field":"Message"}
               ]}""",
        )
        equal("json step count", steps.size, 3)
        equal("json keeps the verb", steps[0].verb, "open")
        equal("json named argument", ScriptParser.request(steps[0]).second.getString("package"), "com.whatsapp")
        equal("json numbers", ScriptParser.request(steps[1]).second.getInt("y"), 1200)
        equal("json positional args", ScriptParser.request(steps[2]).second.getString("text"), "hi")

        val bare = ScriptParser.parseJson("""[{"action":"key","args":["back"]}]""")
        equal("a bare array works", ScriptParser.request(bare[0]).second.getString("key"), "back")

        throws("json without steps", "steps") { ScriptParser.parseJson("""{"nope":1}""") }
        throws("json that is not a list", "must be an array") { ScriptParser.parseJson(""""hello"""") }
        throws("json step without an action", "no \"action\"") { ScriptParser.parseJson("""[{"x":1}]""") }
    }

    private fun blocks() {
        val steps = ScriptParser.parse(
            """
            repeat 2
              key tab
            end
            if-found "Send"
              click "Send"
            else
              echo "no send button"
            end
            """.trimIndent(),
        )
        equal("step count", steps.size, 8)
        equal("repeat jumps to its end", steps[0].jump, 2)
        equal("end jumps back to repeat", steps[2].jump, 0)
        equal("if jumps to else", steps[3].jump, 5)
        equal("else jumps to end", steps[5].jump, 7)

        throws("end without a block", "end without") { ScriptParser.parse("end") }
        throws("unclosed block", "never closed") { ScriptParser.parse("repeat 2\nkey back") }
        throws("else without if", "else without") { ScriptParser.parse("else\nend") }
        throws("else in repeat", "else cannot") { ScriptParser.parse("repeat 2\nelse\nend") }
    }

    /** A fake screen: records the calls an action script makes, and what it was told is on screen. */
    private class FakeHost(private val present: Set<String>) : ActionInterpreter.Host {
        val calls = ArrayList<String>()
        val logs = ArrayList<String>()
        var alive = 0

        override fun call(endpoint: String, body: JSONObject): String {
            calls.add(endpoint + " " + body.toString())
            return "ok"
        }

        override fun found(selector: JSONObject): Boolean = selector.optString("text") in present

        override fun log(line: String) {
            logs.add(line)
        }

        override fun checkAlive() {
            alive++
        }
    }

    private fun interpreter() {
        fun run(source: String, present: Set<String> = emptySet(), args: List<String> = emptyList()): FakeHost {
            val host = FakeHost(present)
            val vars = HashMap<String, String>()
            args.forEachIndexed { i, v -> vars[(i + 1).toString()] = v }
            ActionInterpreter(host).run(ScriptParser.parse(source), vars)
            return host
        }

        val loop = run("repeat 3\n  key back\nend")
        equal("repeat runs the body three times", loop.calls.size, 3)

        val nested = run("repeat 2\n  repeat 3\n    key back\n  end\nend")
        equal("nested loops multiply", nested.calls.size, 6)

        equal("repeat 0 skips the body", run("repeat 0\n  key back\nend").calls.size, 0)

        val taken = run("""if-found "Send"
              click "Send"
            else
              key back
            end""", present = setOf("Send"))
        equal("if-found takes the first branch", taken.calls.size, 1)
        check("if-found clicked", taken.calls[0].startsWith("/click"), taken.calls.toString())

        val notTaken = run("""if-found "Send"
              click "Send"
            else
              key back
            end""")
        check("else branch runs", notTaken.calls[0].startsWith("/key"), notTaken.calls.toString())

        val missing = run("""if-missing "Chats"
              open com.whatsapp
            end
            key home""")
        equal("if-missing runs and continues", missing.calls.size, 2)

        val stopped = run("key back\nstop\nkey home")
        equal("stop ends the script", stopped.calls.size, 1)

        val vars = run("""set who "Mahmoud"
            type "hello ${'$'}who ${'$'}1"""", args = listOf("there"))
        check("variables are substituted", vars.calls[0].contains("hello Mahmoud there"), vars.calls.toString())

        val skipped = run("""if-found "nope"
              key back
              key home
            end
            key recents""")
        equal("a false if skips its whole body", skipped.calls.size, 1)
        check("the step after the block runs", skipped.calls[0].contains("recents"), skipped.calls.toString())

        check("each step is checked for stop", run("key back\nkey home").alive >= 2)
    }
}
