package com.mahmoud.scrobot

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Runs a `.js` script in an off-screen WebView. The script talks to the same endpoints as the
 * HTTP API through a `scrobot` object, so no network access is involved.
 */
class JsRunner(
    private val context: Context,
    private val api: Api,
    private val job: ScriptRunner.Job,
) {
    private val main = Handler(Looper.getMainLooper())
    private val finished = CountDownLatch(1)
    private val failure = AtomicReference<String?>(null)
    private var webView: WebView? = null

    /** What the script calls; every method runs on the WebView's bridge thread, not the UI thread. */
    inner class Bridge {
        /** One API call. Returns the JSON reply as a string, including `ok:false` on failure. */
        @JavascriptInterface
        fun call(endpoint: String, argsJson: String): String {
            if (job.stopRequested) return JSONObject().put("ok", false).put("error", "stopped").toString()
            val body = if (argsJson.isEmpty()) JSONObject() else try {
                JSONObject(argsJson)
            } catch (e: Exception) {
                return JSONObject().put("ok", false).put("error", "arguments are not a JSON object").toString()
            }
            return try {
                when (val result = api.invoke(if (endpoint.startsWith("/")) endpoint else "/$endpoint", body)) {
                    is JSONObject -> result.put("ok", true).toString()
                    is HttpResponse -> JSONObject()
                        .put("ok", true)
                        .put("text", if (result.contentType.startsWith("text/")) String(result.body, Charsets.UTF_8) else "")
                        .put("bytes", result.body.size)
                        .toString()
                    else -> JSONObject().put("ok", true).put("result", result.toString()).toString()
                }
            } catch (e: ApiError) {
                JSONObject().put("ok", false).put("error", e.message).toString()
            } catch (e: Throwable) {
                JSONObject().put("ok", false).put("error", e.toString()).toString()
            }
        }

        @JavascriptInterface
        fun log(message: String) {
            job.log(message)
        }

        @JavascriptInterface
        fun stopped(): Boolean = job.stopRequested

        @JavascriptInterface
        fun finish(ok: Boolean, message: String) {
            if (!ok) failure.set(message.ifEmpty { "script failed" })
            else if (message.isNotEmpty()) job.log(message)
            finished.countDown()
        }
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    fun run(source: String, args: List<String>, deadline: Long) {
        val ready = CountDownLatch(1)
        val startupError = AtomicReference<String?>(null)
        main.post {
            try {
                val view = WebView(context)
                webView = view
                view.settings.javaScriptEnabled = true
                view.settings.domStorageEnabled = false
                view.addJavascriptInterface(Bridge(), "__scrobot")
                view.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) {
                        ready.countDown()
                    }
                }
                view.loadUrl("about:blank")
            } catch (e: Throwable) {
                startupError.set(e.toString())
                ready.countDown()
            }
        }
        if (!ready.await(15, TimeUnit.SECONDS)) {
            destroy()
            throw ApiError("the JavaScript engine did not start", 500)
        }
        startupError.get()?.let {
            destroy()
            throw ApiError("the JavaScript engine did not start: $it", 500)
        }

        val code = prelude(args) + "\n(async () => {\n" + source + "\n})().then(" +
            "v => __scrobot.finish(true, v === undefined || v === null ? '' : String(v))," +
            "e => __scrobot.finish(false, String((e && e.stack) || e)));"
        main.post { webView?.evaluateJavascript(code, null) }

        try {
            while (finished.count > 0L) {
                if (job.stopRequested) throw ApiError("stopped")
                if (SystemClock.uptimeMillis() > deadline) throw ApiError("script timed out", 408)
                finished.await(100, TimeUnit.MILLISECONDS)
            }
        } finally {
            destroy()
        }
        failure.get()?.let { throw ScriptError(it) }
    }

    private fun destroy() {
        main.post {
            webView?.let {
                it.removeJavascriptInterface("__scrobot")
                it.stopLoading()
                it.destroy()
            }
            webView = null
        }
    }

    /** The `scrobot` object every script gets, plus console capture. */
    private fun prelude(args: List<String>): String {
        val argsJson = JSONArray(args).toString()
        return """
            var scrobot = (function () {
              function call(endpoint, args) {
                var reply = JSON.parse(__scrobot.call(endpoint, JSON.stringify(args || {})));
                if (!reply.ok) throw new Error(endpoint + ': ' + reply.error);
                return reply;
              }
              function selector(sel, extra) {
                var o = typeof sel === 'string' ? { text: sel } : Object.assign({}, sel || {});
                return Object.assign(o, extra || {});
              }
              return {
                args: $argsJson,
                call: call,
                log: function () {
                  var parts = [];
                  for (var i = 0; i < arguments.length; i++) {
                    var a = arguments[i];
                    parts.push(typeof a === 'string' ? a : JSON.stringify(a));
                  }
                  __scrobot.log(parts.join(' '));
                },
                stopped: function () { return __scrobot.stopped(); },
                ping: function () { return call('/ping', {}); },
                screen: function (all) { return call('/screen', all ? { all: true } : {}).nodes; },
                find: function (sel) { return call('/find', selector(sel)); },
                tap: function (x, y, ms) { return call('/tap', { x: x, y: y, ms: ms || 60 }); },
                longpress: function (x, y, ms) { return call('/longpress', { x: x, y: y, ms: ms || 800 }); },
                swipe: function (x1, y1, x2, y2, ms) { return call('/swipe', { x1: x1, y1: y1, x2: x2, y2: y2, ms: ms || 300 }); },
                click: function (sel, extra) { return call('/click', selector(sel, extra)); },
                type: function (text, field) { return call('/type', field ? { text: text, field: field } : { text: text }); },
                append: function (text, field) { return call('/type', { text: text, field: field, append: true }); },
                key: function (name) { return call('/key', { key: name }); },
                open: function (target) {
                  return call('/open', String(target).indexOf('://') >= 0 ? { uri: target } : { package: target });
                },
                wait: function (sel, timeout) { return call('/wait', selector(sel, { timeout: timeout || 8000 })); },
                waitGone: function (sel, timeout) { return call('/wait', selector(sel, { timeout: timeout || 8000, gone: true })); },
                focus: function (direction) { return call('/focus', { direction: direction || 'next' }); },
                scroll: function (direction, sel) { return call('/scroll', selector(sel, { direction: direction || 'forward' })); },
                shot: function (name) { return call('/screenshot', { save: name || '' }); },
                clipboard: function (text) { return call('/clipboard', text === undefined ? {} : { text: text }); },
                sleep: function (ms) { return call('/sleep', { ms: ms }); }
              };
            })();
            console.log = function () { scrobot.log.apply(null, arguments); };
            console.warn = console.error = console.info = console.log;
        """.trimIndent()
    }
}
