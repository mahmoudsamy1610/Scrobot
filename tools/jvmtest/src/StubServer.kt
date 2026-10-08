package com.mahmoud.scrobot

import org.json.JSONObject

/**
 * `test` runs the script-language checks; otherwise serves the real HttpServer + Router on
 * 127.0.0.1:<port> with the token from argv[0]. Endpoints echo what they received instead of
 * touching a screen.
 */
/** `parse FILE...` checks scripts without a phone: it prints each step, or the first mistake. */
private fun parseFiles(paths: List<String>): Int {
    var bad = 0
    for (path in paths) {
        val file = java.io.File(path)
        val source = file.readText()
        try {
            val steps = if (path.endsWith(".json")) ScriptParser.parseJson(source) else ScriptParser.parse(source)
            for (step in steps) {
                if (step.verb in ScriptParser.CONTROL) {
                    println("%-24s %s".format(file.name, "$step   (control)"))
                } else {
                    val (endpoint, body) = ScriptParser.request(step)
                    println("%-24s %-44s -> %s %s".format(file.name, step.toString(), endpoint, body))
                }
            }
            println("%-24s OK, ${steps.size} steps".format(file.name))
        } catch (e: ScriptError) {
            println("%-24s FAILED line ${e.line}: ${e.message}".format(file.name))
            bad++
        }
    }
    return bad
}

fun main(args: Array<String>) {
    if (args.firstOrNull() == "test") {
        kotlin.system.exitProcess(ParserTest.run() + EngineTest.run() + StabilityTest.run())
    }
    if (args.firstOrNull() == "js") {
        // Prints the exact program the app feeds to its WebView, for debugging.
        println(JsBridge.program(java.io.File(args[1]).readText(), args.drop(2)))
        return
    }
    if (args.firstOrNull() == "parse") {
        kotlin.system.exitProcess(parseFiles(args.drop(1)))
    }
    val token = args.getOrElse(0) { "TESTTOKEN" }
    val port = args.getOrElse(1) { "8765" }.toInt()
    val router = Router({ token }, { m, p, ok, err, ms -> System.err.println("$m $p ok=$ok err=$err ${ms}ms") })
    // "disconnected=1" pretends the accessibility service went away, as the app reports it.
    router.route("/ping") { a ->
        val state = Status.of(enabledInSettings = true, connected = !a.optBoolean("disconnected"), serverRunning = true)
        JSONObject().put("version", "2.2").put("service", state.isWorking)
            .apply { state.reason?.let { put("reason", it) } }
    }
    for (path in listOf(
        "/tap", "/longpress", "/swipe", "/click", "/type", "/key", "/open", "/wait", "/clipboard",
        "/focus", "/scroll", "/sleep", "/script/run", "/script/stop", "/script/import",
        "/script/delete",
    )) {
        router.route(path) { a -> JSONObject().put("endpoint", path).put("received", a) }
    }
    router.route("/scripts") {
        JSONObject()
            .put("dir", "/sdcard/Android/data/com.mahmoud.scrobot/files/scripts")
            .put("scripts", org.json.JSONArray().put(JSONObject().put("name", "login.scro").put("kind", "actions").put("size", 42)))
    }
    router.route("/find") { a ->
        JSONObject().put("found", a.optString("text") == "Send").put("received", a)
    }
    router.route("/script/check") { a ->
        JSONObject().put("checked", true).put("steps", 3).put("name", a.optString("name"))
    }
    router.route("/script/read") { a ->
        JSONObject().put("script", JSONObject().put("name", a.optString("name"))).put("source", "tap 1 2\n")
    }
    router.route("/script/status") { JSONObject().put("status", "idle") }
    router.route("/fail") { throw ApiError("no visible node matches text=\"x\"", 404) }
    router.route("/screen") { a ->
        if (a.optString("format") == "text") {
            HttpResponse(200, "text/plain; charset=utf-8",
                ("## window 0: WhatsApp (com.whatsapp)  application  bounds 0,0,1080,1150  active\n" +
                    "540,2201  com.whatsapp:id/send  desc:Send  [WhatsApp]  {click}\n" +
                    "## window 1: Claude (com.anthropic.claude)  application  bounds 0,1190,1080,2340\n" +
                    "300,1500  -  مرحبا 👋  [Claude]\n").toByteArray(Charsets.UTF_8))
        } else {
            JSONObject().put("all", a.optString("all")).put("nodes", org.json.JSONArray())
        }
    }
    router.route("/screenshot") { a ->
        val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10)
        if (a.optString("raw") == "1") HttpResponse(200, "image/png", png) else JSONObject().put("png", "iVBORw0KGgo=")
    }
    HttpServer(port, router::handle).start()
    System.err.println("stub listening on 127.0.0.1:$port")
    Thread.currentThread().join()
}
