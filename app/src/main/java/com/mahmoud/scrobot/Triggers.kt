package com.mahmoud.scrobot

import android.content.Context
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import org.json.JSONArray
import org.json.JSONObject

/**
 * The live side of triggers: it watches what the phone is doing and starts the scripts that are
 * waiting for it.
 *
 * Screen events come from the accessibility service, the clock and the boot flag from the
 * foreground service's heartbeat, and screen/charger changes from broadcasts. The decisions
 * themselves are all made by [TriggerEngine].
 */
object Triggers {

    /** Scanning every window for text is the expensive one, so it is throttled. */
    private const val TEXT_SCAN_EVERY_MS = 1_500L
    private const val TEXT_SCAN_LIMIT = 20_000

    private val engine = TriggerEngine()

    @Volatile
    private var cached: List<Trigger>? = null

    @Volatile
    private var lastTextScan = 0L

    @Volatile
    private var lastPackage: String? = null

    fun list(context: Context): List<Trigger> =
        cached ?: TriggerStore.load(context).also { cached = it }

    /** Call after anything changes the file. */
    fun reload(context: Context) {
        cached = TriggerStore.load(context)
        ApiLog.note("Triggers reloaded (${cached?.size ?: 0})")
    }

    fun lastFired(name: String): Long = engine.lastFired(name)

    fun nextRun(trigger: Trigger): Long = engine.nextRun(trigger)

    private fun busy(): Boolean = ApiServer.api()?.scripts?.isRunning == true

    // ---------------------------------------------------------------- events

    /** From the accessibility service, for `app` and `text` triggers. */
    fun onAccessibilityEvent(context: Context, event: AccessibilityEvent) {
        val triggers = list(context)
        if (triggers.isEmpty()) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val pkg = event.packageName?.toString() ?: return
                if (pkg != lastPackage) {
                    lastPackage = pkg
                    val window = event.text?.joinToString(" ") { it.toString() }
                    fireAll(context, TriggerEvent(Trigger.Kind.APP, pkg, pkg, window), "$pkg opened")
                }
                scanText(context, triggers)
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> scanText(context, triggers)
            else -> {}
        }
    }

    /** Reads what is on screen, at most now and then, and only while a text trigger wants it. */
    private fun scanText(context: Context, triggers: List<Trigger>) {
        if (triggers.none { it.enabled && it.kind == Trigger.Kind.TEXT }) return
        val now = SystemClock.uptimeMillis()
        if (now - lastTextScan < TEXT_SCAN_EVERY_MS) return
        lastTextScan = now
        val service = HandsService.instance ?: return
        val screen = StringBuilder()
        var pkg: String? = null
        for (win in Nodes.windows(service)) {
            if (pkg == null && win.active) pkg = win.pkg
            Nodes.walk(win.root) { node ->
                if (node.isVisibleToUser) {
                    node.text?.let { screen.append(it).append('\n') }
                    node.contentDescription?.let { screen.append(it).append('\n') }
                }
                screen.length < TEXT_SCAN_LIMIT
            }
            if (screen.length >= TEXT_SCAN_LIMIT) break
        }
        if (screen.isEmpty()) return
        fireAll(context, TriggerEvent(Trigger.Kind.TEXT, screen.toString(), pkg), "text appeared")
    }

    /** From the broadcast receiver: the screen went on or off, or the charger was plugged in. */
    fun onSystem(context: Context, kind: Trigger.Kind, value: String) {
        fireAll(context, TriggerEvent(kind, value), "$value")
    }

    /** From the heartbeat: scheduled triggers, and the one boot run. */
    fun tick(context: Context) {
        val triggers = list(context)
        if (triggers.isEmpty()) return
        val busy = busy()
        for (trigger in engine.due(triggers, busy)) {
            run(context, trigger, if (trigger.every > 0) "every ${trigger.every} min" else "at ${trigger.at}")
        }
        if (Prefs.bootPending(context) && HandsService.instance != null) {
            Prefs.setBootPending(context, false)
            fireAll(context, TriggerEvent(Trigger.Kind.BOOT), "the phone started")
        }
    }

    private fun fireAll(context: Context, event: TriggerEvent, why: String) {
        val triggers = list(context)
        if (triggers.isEmpty()) return
        val busy = busy()
        for (trigger in engine.onEvent(triggers, event, busy)) run(context, trigger, why)
    }

    /** Starts a trigger's script. Never throws: a trigger must not be able to break the app. */
    fun run(context: Context, trigger: Trigger, why: String) {
        CrashLog.guard("trigger \"${trigger.name}\"") {
            val api = ApiServer.api() ?: return@guard
            val body = JSONObject()
                .put("name", trigger.script)
                .put("args", JSONArray(trigger.args))
                .put("background", true)
            try {
                api.invoke("/script/run", body)
                ApiLog.note("Trigger \"${trigger.name}\" ($why) started ${trigger.script}")
                CrashLog.note("trigger \"${trigger.name}\" ($why) started ${trigger.script}")
            } catch (e: ApiError) {
                ApiLog.note("Trigger \"${trigger.name}\" could not run ${trigger.script}: ${e.message}")
                CrashLog.note("trigger \"${trigger.name}\" could not run ${trigger.script}: ${e.message}")
            }
        }
    }

    /** What the screen and `GET /triggers` show. */
    fun toJson(context: Context): JSONObject {
        val array = JSONArray()
        for (trigger in list(context)) {
            val item = trigger.toJson()
            item.put("description", trigger.describe())
            item.put("last_fired", lastFired(trigger.name))
            val next = nextRun(trigger)
            if (next > 0) item.put("next_run", next)
            array.put(item)
        }
        return JSONObject().put("triggers", array).put("file", TriggerStore.file(context).absolutePath)
    }
}
