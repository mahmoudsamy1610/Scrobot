package com.mahmoud.scrobot

import org.json.JSONArray
import org.json.JSONObject

/** A problem in a script, reported with the line it is on. */
class ScriptError(message: String, val line: Int = 0) : Exception(message)

/**
 * One line of an action script: a verb, its positional arguments, and any `key=value` extras.
 * [jump] links block verbs to their `end` (and an `end` back to the verb that opened it).
 */
class Step(
    val verb: String,
    val args: List<String>,
    val named: Map<String, String>,
    val line: Int,
) {
    var jump: Int = -1

    fun arg(i: Int): String? = args.getOrNull(i)

    fun require(i: Int, what: String): String =
        args.getOrNull(i) ?: throw ScriptError("$verb needs $what", line)

    fun int(i: Int, what: String): Int = number(require(i, what))

    /**
     * An argument by position or by name, so `tap 540 1200` and `tap x=540 y=1200` (and the same
     * step written as JSON) all work.
     */
    fun value(i: Int, name: String): String? = args.getOrNull(i) ?: named[name]

    fun value(i: Int, name: String, what: String): String =
        value(i, name) ?: throw ScriptError("$verb needs $what", line)

    fun int(i: Int, name: String, what: String): Int = number(value(i, name, what))

    fun long(i: Int, name: String, fallback: Long): Long {
        val raw = value(i, name) ?: return fallback
        return raw.toLongOrNull() ?: throw ScriptError("$verb: \"$raw\" is not a number", line)
    }

    private fun number(raw: String): Int = raw.toIntOrNull()
        ?: raw.toDoubleOrNull()?.toInt()
        ?: throw ScriptError("$verb: \"$raw\" is not a number", line)

    override fun toString(): String = (listOf(verb) + args).joinToString(" ") +
        named.entries.joinToString("") { " ${it.key}=${it.value}" }
}

/**
 * The action-script language: one command per line, `#` comments, quoted arguments,
 * `$variables`, and `repeat` / `if-found` / `if-missing` / `else` / `end` blocks.
 *
 * Everything here is plain Kotlin so it can be unit tested without Android.
 */
object ScriptParser {

    /** Verbs handled by the interpreter itself rather than sent to the API. */
    val CONTROL = setOf("repeat", "if-found", "if-missing", "else", "end", "set", "echo", "stop")

    private val NAMED = Regex("^[A-Za-z_][A-Za-z0-9_]*=")

    fun parse(source: String): List<Step> {
        val steps = ArrayList<Step>()
        for ((i, raw) in source.lines().withIndex()) {
            val step = parseLine(raw, i + 1) ?: continue
            steps.add(step)
        }
        link(steps)
        return steps
    }

    /**
     * The JSON form of a script: `{"steps":[{"action":"tap","x":540,"y":1200}]}`, or a bare array
     * of the same objects. Fields other than "action" and "args" become named arguments.
     */
    fun parseJson(source: String): List<Step> {
        val trimmed = source.trim()
        val array = when {
            trimmed.startsWith("[") -> JSONArray(trimmed)
            trimmed.startsWith("{") -> JSONObject(trimmed).optJSONArray("steps")
                ?: throw ScriptError("the JSON object needs a \"steps\" array")
            else -> throw ScriptError("a JSON script must be an array, or an object with \"steps\"")
        }
        val steps = ArrayList<Step>()
        for (i in 0 until array.length()) {
            val raw = array.optJSONObject(i) ?: throw ScriptError("step ${i + 1} is not an object")
            val action = raw.optString("action").ifEmpty { raw.optString("verb") }
            if (action.isEmpty()) throw ScriptError("step ${i + 1} has no \"action\"")
            val named = LinkedHashMap<String, String>()
            for (key in raw.keys()) {
                if (key == "action" || key == "verb" || key == "args") continue
                named[key] = raw.get(key).toString()
            }
            val args = ArrayList<String>()
            raw.optJSONArray("args")?.let { for (j in 0 until it.length()) args.add(it.optString(j)) }
            steps.add(Step(action.lowercase(), args, named, i + 1))
        }
        link(steps)
        return steps
    }

    fun parseLine(raw: String, line: Int): Step? {
        val tokens = tokenize(raw, line)
        if (tokens.isEmpty()) return null
        val verb = tokens[0].text.lowercase()
        val args = ArrayList<String>()
        val named = LinkedHashMap<String, String>()
        for (token in tokens.drop(1)) {
            val eq = if (token.quoted) -1 else NAMED.find(token.text)?.value?.length?.minus(1) ?: -1
            if (eq > 0) named[token.text.substring(0, eq)] = token.text.substring(eq + 1)
            else args.add(token.text)
        }
        return Step(verb, args, named, line)
    }

    class Token(val text: String, val quoted: Boolean)

    /**
     * Splits a line into tokens. Double quotes allow `\n`, `\t`, `\"` and `\\`; single quotes are
     * taken literally. A `#` outside quotes starts a comment.
     */
    fun tokenize(raw: String, line: Int = 0): List<Token> {
        val out = ArrayList<Token>()
        val current = StringBuilder()
        var started = false
        var quotedStart = false
        var i = 0
        fun flush() {
            if (started) out.add(Token(current.toString(), quotedStart))
            current.setLength(0)
            started = false
            quotedStart = false
        }
        while (i < raw.length) {
            val c = raw[i]
            when {
                c == '#' && !started -> return out.also { flush() }
                c.isWhitespace() -> {
                    flush()
                    i++
                }
                c == '"' || c == '\'' -> {
                    if (!started) quotedStart = true
                    started = true
                    val quote = c
                    i++
                    while (i < raw.length && raw[i] != quote) {
                        val ch = raw[i]
                        if (quote == '"' && ch == '\\' && i + 1 < raw.length) {
                            i++
                            current.append(
                                when (val esc = raw[i]) {
                                    'n' -> '\n'
                                    't' -> '\t'
                                    'r' -> '\r'
                                    else -> esc
                                },
                            )
                        } else {
                            current.append(ch)
                        }
                        i++
                    }
                    if (i >= raw.length) throw ScriptError("unclosed $quote quote", line)
                    i++
                }
                else -> {
                    started = true
                    current.append(c)
                    i++
                }
            }
        }
        flush()
        return out
    }

    /** Matches block verbs with their `end`, so the interpreter can jump instead of scanning. */
    private fun link(steps: List<Step>) {
        val open = ArrayDeque<Int>()
        val pendingElse = HashMap<Int, Int>()
        for ((index, step) in steps.withIndex()) {
            when (step.verb) {
                "repeat", "if-found", "if-missing" -> open.addLast(index)
                "else" -> {
                    val start = open.lastOrNull()
                        ?: throw ScriptError("else without if-found or if-missing", step.line)
                    if (steps[start].verb == "repeat") throw ScriptError("else cannot be used in repeat", step.line)
                    // The if jumps here when its condition is false; the else jumps to the end.
                    steps[start].jump = index
                    pendingElse[start] = index
                }
                "end" -> {
                    val start = open.removeLastOrNull()
                        ?: throw ScriptError("end without repeat, if-found or if-missing", step.line)
                    step.jump = start
                    val elseIndex = pendingElse.remove(start)
                    // A block verb jumps to its end (an if with an else already points at the else).
                    if (elseIndex != null) steps[elseIndex].jump = index else steps[start].jump = index
                }
            }
        }
        val unclosed = open.lastOrNull()
        if (unclosed != null) {
            throw ScriptError("${steps[unclosed].verb} is never closed with end", steps[unclosed].line)
        }
    }

    /** Replaces `$name` and `${name}` with values from [vars]; unknown names become empty. */
    fun substitute(text: String, vars: Map<String, String>): String {
        if (!text.contains('$')) return text
        val out = StringBuilder()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c != '$' || i == text.length - 1) {
                out.append(c)
                i++
                continue
            }
            if (text[i + 1] == '$') {
                out.append('$')
                i += 2
                continue
            }
            var j = i + 1
            var name: String
            if (text[j] == '{') {
                val close = text.indexOf('}', j)
                if (close < 0) {
                    out.append(c)
                    i++
                    continue
                }
                name = text.substring(j + 1, close)
                j = close + 1
            } else {
                val start = j
                while (j < text.length && (text[j].isLetterOrDigit() || text[j] == '_')) j++
                name = text.substring(start, j)
                if (name.isEmpty()) {
                    out.append(c)
                    i++
                    continue
                }
            }
            out.append(vars[name] ?: "")
            i = j
        }
        return out.toString()
    }

    /**
     * Turns a step into an API call: the endpoint plus its JSON body. Shared by the action-script
     * interpreter and the `scrobot` shell helper, so both speak exactly the same language.
     */
    fun request(step: Step): Pair<String, JSONObject> {
        val body = JSONObject()
        val endpoint = when (step.verb) {
            "tap" -> {
                body.put("x", step.int(0, "x", "x")).put("y", step.int(1, "y", "y"))
                body.put("ms", step.long(2, "ms", 60))
                "/tap"
            }
            "longpress" -> {
                body.put("x", step.int(0, "x", "x")).put("y", step.int(1, "y", "y"))
                body.put("ms", step.long(2, "ms", 800))
                "/longpress"
            }
            "swipe" -> {
                body.put("x1", step.int(0, "x1", "x1")).put("y1", step.int(1, "y1", "y1"))
                    .put("x2", step.int(2, "x2", "x2")).put("y2", step.int(3, "y2", "y2"))
                    .put("ms", step.long(4, "ms", 300))
                "/swipe"
            }
            "click", "click-id", "click-desc" -> {
                val field = when (step.verb) {
                    "click-id" -> "id"
                    "click-desc" -> "desc"
                    else -> "text"
                }
                val what = step.value(0, field)
                    ?: step.named["text"] ?: step.named["id"] ?: step.named["desc"] ?: step.named["hint"]
                    ?: throw ScriptError("${step.verb} needs something to click", step.line)
                if (step.args.isNotEmpty() || step.named.containsKey(field)) body.put(field, what)
                step.value(1, "index")?.let { body.put("index", step.int(1, "index", "index")) }
                "/click"
            }
            "type", "append" -> {
                body.put("text", step.value(0, "text", "the text to type"))
                step.value(1, "field")?.let { body.put("field", it) }
                if (step.verb == "append") body.put("append", true)
                "/type"
            }
            "key" -> {
                body.put("key", step.value(0, "key", "a key name"))
                "/key"
            }
            "open" -> {
                val target = step.value(0, "package") ?: step.named["uri"] ?: step.named["url"]
                    ?: throw ScriptError("open needs a package or URL", step.line)
                if (target.contains("://")) body.put("uri", target) else body.put("package", target)
                "/open"
            }
            "wait", "wait-gone" -> {
                body.put("text", step.value(0, "text", "something to wait for"))
                body.put("timeout", step.long(1, "timeout", 8000))
                if (step.verb == "wait-gone") body.put("gone", true)
                "/wait"
            }
            "find" -> {
                body.put("text", step.value(0, "text", "something to look for"))
                "/find"
            }
            "focus" -> {
                body.put("direction", step.value(0, "direction") ?: "next")
                "/focus"
            }
            "scroll" -> {
                body.put("direction", step.value(0, "direction") ?: "forward")
                step.value(1, "text")?.let { body.put("text", it) }
                "/scroll"
            }
            "screen" -> {
                body.put("format", "text")
                if (step.value(0, "all") != null) body.put("all", true)
                "/screen"
            }
            "shot" -> {
                body.put("save", step.value(0, "save") ?: "")
                "/screenshot"
            }
            "clip" -> {
                step.value(0, "text")?.let { body.put("text", it) }
                "/clipboard"
            }
            "sleep" -> {
                body.put("ms", step.long(0, "ms", 500))
                "/sleep"
            }
            else -> {
                // Anything else is sent straight through: `/endpoint key=value ...`.
                if (!step.verb.startsWith("/")) {
                    throw ScriptError("unknown command \"${step.verb}\"", step.line)
                }
                step.verb
            }
        }
        for ((key, value) in step.named) body.put(key, value(value))
        return endpoint to body
    }

    /** `true`, `false` and numbers in `key=value` extras become real JSON types. */
    private fun value(raw: String): Any = when {
        raw.equals("true", true) -> true
        raw.equals("false", true) -> false
        raw.toLongOrNull() != null -> raw.toLong()
        raw.toDoubleOrNull() != null -> raw.toDouble()
        else -> raw
    }
}
