package com.mahmoud.scrobot

import org.json.JSONArray

/**
 * The JavaScript a `.js` script runs inside: the `scrobot` object it talks to the app with, and
 * the wrapper that reports how the script ended.
 *
 * Kept away from Android so the exact same code can be run through Node in the tests. Inside the
 * app, `__scrobot` is the Java bridge in [JsRunner]; in the tests it is a stub.
 */
object JsBridge {

    /** Everything a script can call, on top of the single `__scrobot.call` bridge method. */
    fun prelude(args: List<String>): String = """
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
            args: ${JSONArray(args)},
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

    /**
     * Wraps the script so that `await` works, a thrown error fails the run, and the app is told
     * when it is over. The newlines matter: a script may end with a `//` comment.
     */
    fun wrap(source: String): String =
        "(async () => {\n" + source + "\n})().then(\n" +
            "  function (v) { __scrobot.finish(true, v === undefined || v === null ? '' : String(v)); },\n" +
            "  function (e) { __scrobot.finish(false, String((e && e.stack) || e)); }\n" +
            ");"

    fun program(source: String, args: List<String>): String = prelude(args) + "\n" + wrap(source)
}
