package com.mahmoud.scrobot

import org.json.JSONArray
import org.json.JSONObject

/**
 * A rule that runs a script by itself: "when WhatsApp opens", "every 10 minutes", "at 08:30",
 * "when the screen unlocks", "when 'Battery low' appears".
 *
 * Triggers are plain data, kept in `triggers.json` next to the scripts, and are added the same way
 * scripts are: imported, or sent through the API. Nothing is typed into the phone.
 */
class Trigger(
    val name: String,
    val kind: Kind,
    /** What to match: a package for `app`, text for `text`, `on`/`off`/`unlocked` for `screen`… */
    val match: String,
    val script: String,
    val args: List<String> = emptyList(),
    val enabled: Boolean = true,
    /** Seconds this trigger must wait before it may run again. */
    val cooldown: Int = kind.defaultCooldown,
    /** `schedule`: run every so many minutes. */
    val every: Int = 0,
    /** `schedule`: run daily at "HH:MM" (the phone's own clock). */
    val at: String = "",
    /** `text`: only inside this app. */
    val pkg: String = "",
    /** `app`: only when the window title contains this. */
    val window: String = "",
) {
    enum class Kind(val label: String, val defaultCooldown: Int) {
        APP("app", 30),
        TEXT("text", 60),
        SCHEDULE("schedule", 5),
        SCREEN("screen", 10),
        POWER("power", 10),
        BOOT("boot", 5),
        ;

        companion object {
            fun of(raw: String): Kind = entries.firstOrNull { it.label.equals(raw.trim(), true) }
                ?: throw ApiError(
                    "unknown trigger kind \"$raw\"; use " + entries.joinToString(", ") { it.label },
                )
        }
    }

    fun toJson(): JSONObject {
        val out = JSONObject()
            .put("name", name)
            .put("kind", kind.label)
            .put("script", script)
            .put("enabled", enabled)
            .put("cooldown", cooldown)
        if (match.isNotEmpty()) out.put("match", match)
        if (args.isNotEmpty()) out.put("args", JSONArray(args))
        if (every > 0) out.put("every", every)
        if (at.isNotEmpty()) out.put("at", at)
        if (pkg.isNotEmpty()) out.put("package", pkg)
        if (window.isNotEmpty()) out.put("window", window)
        return out
    }

    fun describe(): String = when (kind) {
        Kind.APP -> "when $match opens"
        Kind.TEXT -> "when \"$match\" appears" + if (pkg.isEmpty()) "" else " in $pkg"
        Kind.SCHEDULE -> if (every > 0) "every $every min" else "daily at $at"
        Kind.SCREEN -> "when the screen is $match"
        Kind.POWER -> "when the charger is $match"
        Kind.BOOT -> "after the phone starts"
    }

    companion object {
        val SCREEN_STATES = listOf("on", "off", "unlocked")
        val POWER_STATES = listOf("charging", "unplugged")
        private val TIME = Regex("^([01]?\\d|2[0-3]):([0-5]\\d)$")

        /** Reads one trigger and complains clearly about anything that could never work. */
        fun from(json: JSONObject): Trigger {
            fun str(key: String) = if (json.has(key) && !json.isNull(key)) json.get(key).toString().trim() else ""
            val name = str("name")
            if (name.isEmpty()) throw ApiError("a trigger needs a \"name\"")
            if (name.length > 64) throw ApiError("the trigger name is too long")
            val kind = Kind.of(str("kind").ifEmpty { throw ApiError("trigger \"$name\" needs a \"kind\"") })
            val script = str("script")
            if (script.isEmpty()) throw ApiError("trigger \"$name\" needs a \"script\" to run")

            val match = str("match")
            val every = json.optInt("every", 0)
            val at = str("at")
            when (kind) {
                Kind.APP -> if (match.isEmpty()) throw ApiError("trigger \"$name\" needs the app's package in \"match\"")
                Kind.TEXT -> if (match.isEmpty()) throw ApiError("trigger \"$name\" needs the text to look for in \"match\"")
                Kind.SCREEN -> if (match !in SCREEN_STATES) {
                    throw ApiError("trigger \"$name\": \"match\" must be ${SCREEN_STATES.joinToString(", ")}")
                }
                Kind.POWER -> if (match !in POWER_STATES) {
                    throw ApiError("trigger \"$name\": \"match\" must be ${POWER_STATES.joinToString(" or ")}")
                }
                Kind.SCHEDULE -> {
                    if (every <= 0 && at.isEmpty()) {
                        throw ApiError("trigger \"$name\" needs \"every\" (minutes) or \"at\" (\"HH:MM\")")
                    }
                    if (every > 0 && every < 1) throw ApiError("trigger \"$name\": \"every\" must be at least 1 minute")
                    if (at.isNotEmpty() && !TIME.matches(at)) {
                        throw ApiError("trigger \"$name\": \"at\" must look like \"08:30\"")
                    }
                }
                Kind.BOOT -> {}
            }

            val args = ArrayList<String>()
            json.optJSONArray("args")?.let { for (i in 0 until it.length()) args.add(it.optString(i)) }
            return Trigger(
                name = name,
                kind = kind,
                match = match,
                script = script,
                args = args,
                enabled = json.optBoolean("enabled", true),
                cooldown = json.optInt("cooldown", kind.defaultCooldown).coerceIn(0, 24 * 3600),
                every = every,
                at = at,
                pkg = str("package"),
                window = str("window"),
            )
        }

        /** Reads a whole `triggers.json`: an object with "triggers", or a bare array. */
        fun listFrom(source: String): List<Trigger> {
            val trimmed = source.trim()
            if (trimmed.isEmpty()) return emptyList()
            val array = when {
                trimmed.startsWith("[") -> JSONArray(trimmed)
                trimmed.startsWith("{") -> {
                    val obj = JSONObject(trimmed)
                    obj.optJSONArray("triggers") ?: JSONArray().put(obj)
                }
                else -> throw ApiError("triggers must be a JSON array, or an object with \"triggers\"")
            }
            val out = ArrayList<Trigger>()
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: throw ApiError("trigger ${i + 1} is not an object")
                val trigger = from(item)
                if (out.any { it.name.equals(trigger.name, true) }) {
                    throw ApiError("two triggers are both called \"${trigger.name}\"")
                }
                out.add(trigger)
            }
            return out
        }

        fun listToJson(triggers: List<Trigger>): JSONObject {
            val array = JSONArray()
            for (trigger in triggers) array.put(trigger.toJson())
            return JSONObject().put("triggers", array)
        }
    }
}
