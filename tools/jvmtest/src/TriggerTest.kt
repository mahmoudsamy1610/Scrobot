package com.mahmoud.scrobot

import org.json.JSONObject
import java.util.Calendar
import java.util.TimeZone

/**
 * The trigger rules: what a trigger file may say, what starts a script, and what must not.
 *
 * The clock is faked, so the schedules, cooldowns and edges are all checked exactly. What Android
 * does around this — delivering the accessibility and broadcast events — is not covered here.
 */
object TriggerTest {

    private var failures = 0
    private val zone: TimeZone = TimeZone.getTimeZone("Africa/Cairo")

    fun run(): Int {
        failures = 0
        parsing()
        validation()
        appTriggers()
        textTriggers()
        screenAndPower()
        cooldownAndBusy()
        schedules()
        println(if (failures == 0) "triggers: all checks passed" else "triggers: $failures FAILED")
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

    private fun rejects(name: String, message: String, body: () -> Unit) {
        try {
            body()
            check(name, false, "it was accepted")
        } catch (e: ApiError) {
            check(name, e.message?.contains(message) == true, "message was \"${e.message}\"")
        }
    }

    private fun trigger(json: String) = Trigger.from(JSONObject(json))

    // ---------------------------------------------------------------- the file

    private fun parsing() {
        val one = trigger("""{"name":"wa","kind":"app","match":"com.whatsapp","script":"greet.scro"}""")
        equal("kind", one.kind, Trigger.Kind.APP)
        equal("script", one.script, "greet.scro")
        check("enabled by default", one.enabled)
        equal("a sensible default cooldown", one.cooldown, Trigger.Kind.APP.defaultCooldown)
        equal("it describes itself", one.describe(), "when com.whatsapp opens")

        val full = trigger(
            """{"name":"n","kind":"text","match":"Battery low","script":"s.js","args":["a","b"],
                "enabled":false,"cooldown":120,"package":"com.android.systemui"}""",
        )
        equal("arguments are kept", full.args, listOf("a", "b"))
        check("it can start switched off", !full.enabled)
        equal("the cooldown is kept", full.cooldown, 120)
        equal("the app filter is kept", full.pkg, "com.android.systemui")

        val list = Trigger.listFrom(
            """{"triggers":[
                 {"name":"a","kind":"boot","script":"x.scro"},
                 {"name":"b","kind":"schedule","every":10,"script":"y.scro"}
               ]}""",
        )
        equal("a whole file reads back", list.size, 2)
        equal("a bare array works too", Trigger.listFrom("""[{"name":"a","kind":"boot","script":"x"}]""").size, 1)
        equal("an empty file is no triggers", Trigger.listFrom("  ").size, 0)

        // What is written is what is read back.
        val again = Trigger.listFrom(Trigger.listToJson(list).toString())
        equal("saving and loading keeps them", again.map { it.name }, list.map { it.name })
        equal("saving keeps the schedule", again[1].every, 10)
    }

    private fun validation() {
        rejects("a trigger needs a name", "needs a \"name\"") { trigger("""{"kind":"boot","script":"x"}""") }
        rejects("a trigger needs a script", "needs a \"script\"") { trigger("""{"name":"a","kind":"boot"}""") }
        rejects("the kind must be known", "unknown trigger kind") { trigger("""{"name":"a","kind":"telepathy","script":"x"}""") }
        rejects("an app trigger needs a package", "package") { trigger("""{"name":"a","kind":"app","script":"x"}""") }
        rejects("a text trigger needs text", "text to look for") { trigger("""{"name":"a","kind":"text","script":"x"}""") }
        rejects("a screen trigger needs a real state", "must be") {
            trigger("""{"name":"a","kind":"screen","match":"sideways","script":"x"}""")
        }
        rejects("a power trigger needs a real state", "must be") {
            trigger("""{"name":"a","kind":"power","match":"warm","script":"x"}""")
        }
        rejects("a schedule needs a time", "every") { trigger("""{"name":"a","kind":"schedule","script":"x"}""") }
        rejects("the time must look like a time", "08:30") {
            trigger("""{"name":"a","kind":"schedule","at":"half eight","script":"x"}""")
        }
        rejects("names cannot repeat", "both called") {
            Trigger.listFrom("""[{"name":"a","kind":"boot","script":"x"},{"name":"a","kind":"boot","script":"y"}]""")
        }
        rejects("a file must be a list", "must be a JSON array") { Trigger.listFrom("\"nope\"") }
    }

    // ---------------------------------------------------------------- what starts a script

    private class Clock(var now: Long = 1_000_000L) {
        fun engine() = TriggerEngine { now }
    }

    private fun appTriggers() {
        val clock = Clock()
        val engine = clock.engine()
        val wa = trigger("""{"name":"wa","kind":"app","match":"com.whatsapp","script":"greet.scro","cooldown":0}""")
        val list = listOf(wa)

        equal(
            "the app opening starts it",
            engine.onEvent(list, TriggerEvent(Trigger.Kind.APP, "com.whatsapp"), busy = false).size,
            1,
        )
        // Staying in the app must not keep starting it, or a script would run on every screen change.
        equal(
            "staying in the app does not",
            engine.onEvent(list, TriggerEvent(Trigger.Kind.APP, "com.whatsapp"), busy = false).size,
            0,
        )
        engine.onEvent(list, TriggerEvent(Trigger.Kind.APP, "com.android.settings"), busy = false)
        clock.now += 60_000
        equal(
            "coming back starts it again",
            engine.onEvent(list, TriggerEvent(Trigger.Kind.APP, "com.whatsapp"), busy = false).size,
            1,
        )
        equal(
            "another app does not",
            engine.onEvent(list, TriggerEvent(Trigger.Kind.APP, "com.instagram"), busy = false).size,
            0,
        )

        val titled = trigger(
            """{"name":"chat","kind":"app","match":"com.whatsapp","window":"Chats","script":"x.scro","cooldown":0}""",
        )
        equal(
            "a window title can narrow it",
            clock.engine().onEvent(
                listOf(titled),
                TriggerEvent(Trigger.Kind.APP, "com.whatsapp", window = "Chats"),
                busy = false,
            ).size,
            1,
        )
        equal(
            "the wrong window does not match",
            clock.engine().onEvent(
                listOf(titled),
                TriggerEvent(Trigger.Kind.APP, "com.whatsapp", window = "Calls"),
                busy = false,
            ).size,
            0,
        )
    }

    private fun textTriggers() {
        val clock = Clock()
        val engine = clock.engine()
        val low = trigger("""{"name":"low","kind":"text","match":"Battery low","script":"save.scro","cooldown":0}""")
        val list = listOf(low)

        val screen = "Settings\nBattery low\nTap to see"
        equal("text on screen starts it", engine.onEvent(list, TriggerEvent(Trigger.Kind.TEXT, screen), false).size, 1)
        equal("while it stays, nothing more", engine.onEvent(list, TriggerEvent(Trigger.Kind.TEXT, screen), false).size, 0)
        engine.onEvent(list, TriggerEvent(Trigger.Kind.TEXT, "Settings"), false)
        clock.now += 60_000
        equal("it starts again when it comes back", engine.onEvent(list, TriggerEvent(Trigger.Kind.TEXT, screen), false).size, 1)
        equal(
            "matching ignores case",
            clock.engine().onEvent(list, TriggerEvent(Trigger.Kind.TEXT, "BATTERY LOW"), false).size,
            1,
        )

        val scoped = trigger(
            """{"name":"s","kind":"text","match":"Send","package":"com.whatsapp","script":"x.scro","cooldown":0}""",
        )
        equal(
            "the app filter lets the right app through",
            clock.engine().onEvent(listOf(scoped), TriggerEvent(Trigger.Kind.TEXT, "Send", pkg = "com.whatsapp"), false).size,
            1,
        )
        equal(
            "and keeps other apps out",
            clock.engine().onEvent(listOf(scoped), TriggerEvent(Trigger.Kind.TEXT, "Send", pkg = "com.slack"), false).size,
            0,
        )
    }

    private fun screenAndPower() {
        val engine = Clock().engine()
        val unlock = trigger("""{"name":"u","kind":"screen","match":"unlocked","script":"x.scro","cooldown":0}""")
        val charge = trigger("""{"name":"c","kind":"power","match":"charging","script":"y.scro","cooldown":0}""")
        val boot = trigger("""{"name":"b","kind":"boot","script":"z.scro","cooldown":0}""")
        val all = listOf(unlock, charge, boot)

        equal("unlocking starts its own", engine.onEvent(all, TriggerEvent(Trigger.Kind.SCREEN, "unlocked"), false).map { it.name }, listOf("u"))
        equal("the screen going off does not", engine.onEvent(all, TriggerEvent(Trigger.Kind.SCREEN, "off"), false).size, 0)
        equal("the charger starts its own", engine.onEvent(all, TriggerEvent(Trigger.Kind.POWER, "charging"), false).map { it.name }, listOf("c"))
        equal("unplugging does not", engine.onEvent(all, TriggerEvent(Trigger.Kind.POWER, "unplugged"), false).size, 0)
        equal("booting starts its own", engine.onEvent(all, TriggerEvent(Trigger.Kind.BOOT), false).map { it.name }, listOf("b"))
    }

    private fun cooldownAndBusy() {
        val clock = Clock()
        val engine = clock.engine()
        val t = trigger("""{"name":"u","kind":"screen","match":"unlocked","script":"x.scro","cooldown":30}""")
        val list = listOf(t)
        val unlocked = TriggerEvent(Trigger.Kind.SCREEN, "unlocked")

        equal("the first one runs", engine.decide(t, unlocked, busy = false), TriggerEngine.Decision.FIRE)
        equal("a second one straight after waits", engine.decide(t, unlocked, busy = false), TriggerEngine.Decision.COOLDOWN)
        clock.now += 29_000
        equal("still waiting just before the cooldown ends", engine.decide(t, unlocked, false), TriggerEngine.Decision.COOLDOWN)
        clock.now += 2_000
        equal("and runs again after it", engine.decide(t, unlocked, false), TriggerEngine.Decision.FIRE)

        // While a script runs, nothing fires: a script changing the screen must not set triggers off.
        clock.now += 60_000
        equal("a running script holds triggers back", engine.decide(t, unlocked, busy = true), TriggerEngine.Decision.BUSY)
        check("and nothing is returned while busy", engine.onEvent(list, unlocked, busy = true).isEmpty())

        val off = trigger("""{"name":"o","kind":"screen","match":"unlocked","script":"x.scro","enabled":false}""")
        equal("a switched-off trigger never runs", engine.decide(off, unlocked, false), TriggerEngine.Decision.DISABLED)
        check("and is not returned", engine.onEvent(listOf(off), unlocked, false).isEmpty())
    }

    // ---------------------------------------------------------------- schedules

    private fun at(hour: Int, minute: Int, day: Int = 8): Long {
        val calendar = Calendar.getInstance(zone)
        calendar.set(2026, Calendar.OCTOBER, day, hour, minute, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        return calendar.timeInMillis
    }

    private fun schedules() {
        // Every N minutes, counted from when the trigger was first seen.
        val clock = Clock(at(9, 0))
        val engine = clock.engine()
        val every10 = trigger("""{"name":"e","kind":"schedule","every":10,"script":"x.scro"}""")
        val list = listOf(every10)

        equal("nothing is due at once", engine.due(list, busy = false, zone).size, 0)
        clock.now = at(9, 9)
        equal("nor a minute early", engine.due(list, false, zone).size, 0)
        clock.now = at(9, 10)
        equal("it runs after ten minutes", engine.due(list, false, zone).size, 1)
        equal("and not twice", engine.due(list, false, zone).size, 0)
        clock.now = at(9, 20)
        equal("it runs again ten minutes later", engine.due(list, false, zone).size, 1)

        clock.now = at(9, 40)
        check("a running script delays it", engine.due(list, busy = true, zone).isEmpty())
        equal("and it runs once free", engine.due(list, false, zone).size, 1)

        // Daily at a set time.
        val daily = Clock(at(7, 59))
        val dailyEngine = daily.engine()
        val morning = listOf(trigger("""{"name":"m","kind":"schedule","at":"08:30","script":"brief.js"}"""))
        equal("before the time, nothing", dailyEngine.due(morning, false, zone).size, 0)
        daily.now = at(8, 30)
        equal("at the time it runs", dailyEngine.due(morning, false, zone).size, 1)
        daily.now = at(8, 31)
        equal("not again the same day", dailyEngine.due(morning, false, zone).size, 0)
        daily.now = at(23, 59)
        equal("still not later that day", dailyEngine.due(morning, false, zone).size, 0)
        daily.now = at(8, 30, day = 9)
        equal("and again the next day", dailyEngine.due(morning, false, zone).size, 1)

        // A time that passed before the app was running is skipped, not run late: a morning
        // script should not go off at lunchtime because the phone was off at 08:30.
        val late = Clock(at(10, 0))
        val lateEngine = late.engine()
        equal("a time already past when the app starts is skipped", lateEngine.due(morning, false, zone).size, 0)
        late.now = at(8, 30, day = 9)
        equal("the next day it runs as normal", lateEngine.due(morning, false, zone).size, 1)

        val off = listOf(trigger("""{"name":"m","kind":"schedule","at":"08:30","script":"x","enabled":false}"""))
        daily.now = at(8, 30, day = 10)
        check("a switched-off schedule never runs", dailyEngine.due(off, false, zone).isEmpty())
    }
}
