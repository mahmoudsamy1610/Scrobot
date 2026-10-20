package com.mahmoud.scrobot

import org.json.JSONObject

/**
 * Walks the steps of an action script: control flow here, real work in [Host].
 * Plain Kotlin, so the loops and conditions can be unit tested without Android.
 */
class ActionInterpreter(private val host: Host) {

    interface Host {
        /** Runs one endpoint and returns a short description for the log. */
        fun call(endpoint: String, body: JSONObject): String

        /** True when a node matching [selector] is on screen (used by if-found / if-missing). */
        fun found(selector: JSONObject): Boolean

        fun log(line: String)

        /** Throws when the script was stopped or ran out of time. */
        fun checkAlive()
    }

    fun run(steps: List<Step>, vars: MutableMap<String, String>) {
        val counters = HashMap<Int, Int>()
        var pc = 0
        while (pc < steps.size) {
            host.checkAlive()
            val step = resolve(steps[pc], vars)
            when (step.verb) {
                "repeat" -> {
                    val left = counters.getOrPut(pc) { step.int(0, "a number of times") }
                    if (left <= 0) {
                        counters.remove(pc)
                        pc = step.jump + 1
                    } else {
                        pc++
                    }
                }
                "end" -> {
                    val start = step.jump
                    if (start >= 0 && steps[start].verb == "repeat") {
                        counters[start] = (counters[start] ?: 1) - 1
                        pc = start
                    } else {
                        pc++
                    }
                }
                "if-found", "if-missing" -> {
                    val isThere = host.found(selector(step))
                    val take = if (step.verb == "if-found") isThere else !isThere
                    host.log("${step.line}: $step -> ${if (take) "yes" else "no"}")
                    pc = if (take) pc + 1 else step.jump + 1
                }
                "else" -> pc = step.jump + 1
                "set" -> {
                    vars[step.require(0, "a variable name")] = step.arg(1) ?: ""
                    pc++
                }
                "echo" -> {
                    host.log("${step.line}: ${step.args.joinToString(" ")}")
                    pc++
                }
                "stop" -> {
                    host.log("${step.line}: stop")
                    return
                }
                else -> {
                    val (endpoint, body) = ScriptParser.request(step)
                    host.log("${step.line}: $step -> ${host.call(endpoint, body)}")
                    pc++
                }
            }
        }
    }

    /** Substitutes `$variables` in a step's arguments just before it runs. */
    private fun resolve(step: Step, vars: Map<String, String>): Step {
        if (vars.isEmpty()) return step
        val args = step.args.map { ScriptParser.substitute(it, vars) }
        val named = step.named.mapValues { ScriptParser.substitute(it.value, vars) }
        return Step(step.verb, args, named, step.line).also { it.jump = step.jump }
    }

    private fun selector(step: Step): JSONObject {
        val body = JSONObject()
        step.arg(0)?.let { body.put("text", it) }
        for ((key, value) in step.named) body.put(key, value)
        if (!body.has("text") && !body.has("id") && !body.has("desc") && !body.has("hint")) {
            throw ScriptError("${step.verb} needs something to look for", step.line)
        }
        return body
    }
}
