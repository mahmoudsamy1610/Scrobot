package com.mahmoud.claudehands

import org.json.JSONObject
import java.security.MessageDigest

/** Thrown by endpoint handlers; becomes {"ok":false,"error":message} with the given HTTP status. */
class ApiError(message: String, val status: Int = 400) : Exception(message)

/**
 * Token check, JSON parsing, dispatch and error wrapping. Android-free so it can be unit tested.
 *
 * A handler receives the merged arguments (JSON body + query string; body wins) and returns either
 * a JSONObject (sent with "ok":true added) or a raw [HttpResponse] (e.g. PNG bytes, plain text).
 */
class Router(
    private val tokenProvider: () -> String,
    private val logger: (method: String, path: String, ok: Boolean, error: String?, ms: Long) -> Unit = { _, _, _, _, _ -> },
) {
    private val routes = HashMap<String, (JSONObject) -> Any>()

    fun route(path: String, handler: (JSONObject) -> Any) {
        routes[path] = handler
    }

    fun handle(request: HttpRequest): HttpResponse {
        val started = System.currentTimeMillis()
        var ok = false
        var error: String? = null
        val response = try {
            if (!tokenMatches(request.headers["x-token"])) {
                throw ApiError("missing or wrong X-Token header", 401)
            }
            val handler = routes[request.path.trimEnd('/').ifEmpty { "/" }]
                ?: throw ApiError("unknown endpoint ${request.path}", 404)
            if (request.method != "GET" && request.method != "POST") {
                throw ApiError("use GET or POST", 405)
            }
            val result = handler(parseArgs(request))
            ok = true
            when (result) {
                is HttpResponse -> result
                is JSONObject -> json(200, result.put("ok", true))
                else -> json(200, JSONObject().put("ok", true).put("result", result))
            }
        } catch (e: ApiError) {
            error = e.message
            json(e.status, JSONObject().put("ok", false).put("error", e.message))
        } catch (e: Throwable) {
            error = e.toString()
            json(500, JSONObject().put("ok", false).put("error", e.toString()))
        }
        logger(request.method, request.path, ok, error, System.currentTimeMillis() - started)
        return response
    }

    private fun tokenMatches(given: String?): Boolean {
        val expected = tokenProvider()
        if (given == null || expected.isEmpty()) return false
        return MessageDigest.isEqual(given.trim().toByteArray(), expected.toByteArray())
    }

    private fun parseArgs(request: HttpRequest): JSONObject {
        val args = JSONObject()
        for ((k, v) in request.query) args.put(k, v)
        val body = request.body.trim()
        if (body.isNotEmpty()) {
            val parsed = try {
                JSONObject(body)
            } catch (e: Exception) {
                throw ApiError("body is not a JSON object: ${e.message}")
            }
            val keys = parsed.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                args.put(k, parsed.get(k))
            }
        }
        return args
    }

    companion object {
        fun json(status: Int, obj: JSONObject) =
            HttpResponse(status, "application/json; charset=utf-8", obj.toString().toByteArray(Charsets.UTF_8))
    }
}
