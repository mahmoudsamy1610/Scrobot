package com.mahmoud.claudehands

import org.json.JSONObject

/**
 * Serves the real HttpServer + Router on 127.0.0.1:<port> with the token from argv[0].
 * Endpoints echo what they received instead of touching a screen.
 */
fun main(args: Array<String>) {
    val token = args.getOrElse(0) { "TESTTOKEN" }
    val port = args.getOrElse(1) { "8765" }.toInt()
    val router = Router({ token }, { m, p, ok, err, ms -> System.err.println("$m $p ok=$ok err=$err ${ms}ms") })
    router.route("/ping") { JSONObject().put("version", "1.0") }
    for (path in listOf("/tap", "/longpress", "/swipe", "/click", "/type", "/key", "/open", "/wait", "/clipboard")) {
        router.route(path) { a -> JSONObject().put("endpoint", path).put("received", a) }
    }
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
