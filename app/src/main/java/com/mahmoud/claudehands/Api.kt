package com.mahmoud.claudehands

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Implementation of every endpoint, on top of the connected [AccessibilityService]. */
class Api(private val service: AccessibilityService) {

    private val callbackThread = HandlerThread("hands-callbacks").apply { start() }
    private val callbackHandler = Handler(callbackThread.looper)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val gestureLock = Any()

    fun register(router: Router) {
        router.route("/ping") { JSONObject().put("version", Prefs.VERSION).put("service", true) }
        router.route("/screen", ::screen)
        router.route("/tap") { a -> tap(int(a, "x"), int(a, "y"), a.optLong("ms", 60)) }
        router.route("/longpress") { a -> tap(int(a, "x"), int(a, "y"), a.optLong("ms", 800)) }
        router.route("/swipe", ::swipe)
        router.route("/click", ::click)
        router.route("/type", ::type)
        router.route("/key", ::key)
        router.route("/open", ::open)
        router.route("/wait", ::waitFor)
        router.route("/screenshot", ::screenshot)
        router.route("/clipboard", ::clipboard)
    }

    fun shutdown() {
        callbackThread.quitSafely()
        screenshotExecutor.shutdown()
    }

    // ---------------------------------------------------------------- /screen

    private fun screen(a: JSONObject): Any {
        val all = flag(a, "all")
        val wins = Nodes.windows(service)
        if (a.optString("format") == "text") {
            val sb = StringBuilder()
            for ((i, win) in wins.withIndex()) {
                val b = win.bounds
                sb.append("## window ").append(i).append(": ").append(win.label)
                if (win.pkg != null && win.pkg != win.label) sb.append(" (").append(win.pkg).append(')')
                sb.append("  ").append(win.type).append("  bounds ")
                    .append(b.left).append(',').append(b.top).append(',').append(b.right).append(',').append(b.bottom)
                if (win.active) sb.append("  active")
                sb.append('\n')
                Nodes.walk(win.root) { node ->
                    if (all || (node.isVisibleToUser && Nodes.hasLabel(node))) {
                        sb.append(Nodes.toTextLine(node, win)).append('\n')
                    }
                    true
                }
            }
            return HttpResponse(200, "text/plain; charset=utf-8", sb.toString().toByteArray(Charsets.UTF_8))
        }
        val nodes = JSONArray()
        val windows = JSONArray()
        for (win in wins) {
            windows.put(win.toJson())
            Nodes.walk(win.root) { node ->
                if (all || (node.isVisibleToUser && Nodes.hasLabel(node))) nodes.put(Nodes.toJson(node, win))
                true
            }
        }
        return JSONObject().put("windows", windows).put("count", nodes.length()).put("nodes", nodes)
    }

    // ---------------------------------------------------------------- gestures

    private fun tap(x: Int, y: Int, ms: Long): JSONObject {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        gesture(path, ms)
        return JSONObject().put("x", x).put("y", y).put("ms", ms)
    }

    private fun swipe(a: JSONObject): JSONObject {
        val x1 = int(a, "x1"); val y1 = int(a, "y1"); val x2 = int(a, "x2"); val y2 = int(a, "y2")
        val ms = a.optLong("ms", 300)
        val path = Path().apply {
            moveTo(x1.toFloat(), y1.toFloat())
            lineTo(x2.toFloat(), y2.toFloat())
        }
        gesture(path, ms)
        return JSONObject().put("from", JSONArray().put(x1).put(y1)).put("to", JSONArray().put(x2).put(y2)).put("ms", ms)
    }

    /** Dispatches a one-stroke gesture and blocks until the system reports it completed or cancelled. */
    private fun gesture(path: Path, ms: Long) {
        val duration = ms.coerceIn(1, GestureDescription.getMaxGestureDuration())
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
            .build()
        synchronized(gestureLock) {
            val latch = CountDownLatch(1)
            val result = AtomicReference<String?>(null)
            val callback = object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    result.set("completed"); latch.countDown()
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    result.set("cancelled"); latch.countDown()
                }
            }
            if (!service.dispatchGesture(gesture, callback, callbackHandler)) {
                throw ApiError("dispatchGesture was rejected by the system", 500)
            }
            if (!latch.await(duration + 5000, TimeUnit.MILLISECONDS)) {
                throw ApiError("gesture did not finish in time", 500)
            }
            if (result.get() != "completed") throw ApiError("gesture was cancelled", 500)
        }
    }

    // ---------------------------------------------------------------- /click

    private fun click(a: JSONObject): JSONObject {
        val sel = selector(a)
        val found = Nodes.find(service, sel) ?: throw ApiError("no visible node matches ${sel.describe()} (index ${sel.index})", 404)
        return clickFound(found).put("matched", Nodes.toJson(found.node, found.win))
    }

    /** ACTION_CLICK on the node or its nearest clickable ancestor; otherwise a tap on the node's center. */
    private fun clickFound(found: Found): JSONObject {
        var target: AccessibilityNodeInfo? = found.node
        while (target != null && !target.isClickable) target = target.parent
        if (target != null && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            return JSONObject().put("method", "action_click").put("clicked", Nodes.toJson(target, found.win))
        }
        val c = Nodes.center(found.node)
        tap(c[0], c[1], 60)
        return JSONObject().put("method", "tap").put("x", c[0]).put("y", c[1])
            .put("clicked", Nodes.toJson(found.node, found.win))
    }

    // ---------------------------------------------------------------- /type

    private fun type(a: JSONObject): JSONObject {
        if (!a.has("text")) throw ApiError("missing \"text\"")
        val text = a.getString("text")
        val append = a.optBoolean("append", false)

        // The field is chosen like /click, except that "text" is the text to type, so the
        // text/desc/hint match of the field goes in "field".
        val sel = Selector(str(a, "field"), str(a, "desc"), str(a, "id"), str(a, "hint"), str(a, "package"),
            a.optBoolean("exact", false), a.optInt("index", 0))

        val field = resolveField(sel)
        val node = field.node
        if (!node.isFocused) {
            if (!node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)) node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            SystemClock.sleep(80)
            node.refresh()
        }

        val existing = if (append) Nodes.realText(node) ?: "" else ""
        val wanted = existing + text
        var method = "set_text"
        var acted = setText(node, wanted)
        var verified = acted && waitForText(node, wanted)
        if (!verified) {
            method = "paste"
            val pasted = paste(node, wanted)
            acted = acted || pasted
            verified = pasted && waitForText(node, wanted)
        }
        if (!acted) throw ApiError("the field refused both ACTION_SET_TEXT and ACTION_PASTE", 500)

        if (flag(a, "enter")) pressEnter(node)
        node.refresh()
        return JSONObject().put("method", method).put("verified", verified).put("field", Nodes.toJson(node, field.win))
    }

    /** Picks the field to type into: by selector, else the focused input, else the first editable node. */
    private fun resolveField(sel: Selector): Found {
        if (!sel.isEmpty) {
            val found = Nodes.find(service, sel) ?: throw ApiError("no visible field matches ${sel.describe()}", 404)
            if (found.node.isEditable) return found
            var editable: AccessibilityNodeInfo? = null
            Nodes.walk(found.node) { n -> if (n.isEditable && n.isVisibleToUser) { editable = n; false } else true }
            editable?.let { return Found(it, found.win) }
            // Things like WhatsApp's search bar only become an EditText after they are clicked.
            clickFound(found)
            return focusedInput(1500, sel.pkg) ?: throw ApiError("clicked ${sel.describe()} but no text field got focus", 404)
        }
        return focusedInput(1000, sel.pkg) ?: firstEditable(sel.pkg)
            ?: throw ApiError("no focused or editable text field on screen", 404)
    }

    /** The focused text field (optionally only in app [pkg]); in split screen each half may have one. */
    private fun focusedInput(timeoutMs: Long, pkg: String? = null): Found? {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (true) {
            val wins = Nodes.windows(service)
            val candidates = listOfNotNull(service.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)) +
                Nodes.searchOrder(wins).mapNotNull { it.root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }
            val node = candidates.firstOrNull { it.isEditable && (pkg == null || it.packageName?.toString() == pkg) }
            if (node != null) {
                val win = wins.firstOrNull { it.id == node.windowId }
                    ?: Win(node.windowId, null, node.packageName?.toString(), "application", 0, true, true, Rect(), null)
                return Found(node, win)
            }
            if (SystemClock.uptimeMillis() >= deadline) return null
            SystemClock.sleep(100)
        }
    }

    private fun firstEditable(pkg: String?): Found? {
        for (win in Nodes.searchOrder(Nodes.windows(service))) {
            if (win.type == "input_method") continue
            if (pkg != null && win.pkg != pkg) continue
            var hit: AccessibilityNodeInfo? = null
            Nodes.walk(win.root) { n -> if (n.isEditable && n.isVisibleToUser) { hit = n; false } else true }
            hit?.let { return Found(it, win) }
        }
        return null
    }

    private fun setText(node: AccessibilityNodeInfo, value: String): Boolean {
        val args = Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        return try {
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        } catch (e: Exception) {
            false
        }
    }

    /** Clipboard fallback: put the full wanted text on the clipboard, select everything, paste over it. */
    private fun paste(node: AccessibilityNodeInfo, value: String): Boolean {
        setClip(value)
        node.refresh()
        val len = Nodes.realText(node)?.length ?: 0
        val sel = Bundle()
        sel.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
        sel.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, len)
        node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, sel)
        return node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
    }

    private fun waitForText(node: AccessibilityNodeInfo, wanted: String): Boolean {
        if (node.isPassword) return true
        val deadline = SystemClock.uptimeMillis() + 600
        while (true) {
            SystemClock.sleep(60)
            node.refresh()
            if ((Nodes.realText(node) ?: "") == wanted) return true
            if (SystemClock.uptimeMillis() >= deadline) return false
        }
    }

    private fun pressEnter(node: AccessibilityNodeInfo): Boolean {
        if (Build.VERSION.SDK_INT < 30) return false
        return node.performAction(AccessibilityAction.ACTION_IME_ENTER.id)
    }

    // ---------------------------------------------------------------- /key

    private fun key(a: JSONObject): JSONObject {
        val name = str(a, "key")?.lowercase() ?: throw ApiError("missing \"key\"")
        val ok = when (name) {
            "enter", "ime_enter" -> {
                val f = focusedInput(0) ?: throw ApiError("no focused text field for enter", 404)
                if (Build.VERSION.SDK_INT < 30) throw ApiError("enter needs Android 11+", 400)
                pressEnter(f.node)
            }
            else -> {
                val action = when (name) {
                    "back" -> AccessibilityService.GLOBAL_ACTION_BACK
                    "home" -> AccessibilityService.GLOBAL_ACTION_HOME
                    "recents" -> AccessibilityService.GLOBAL_ACTION_RECENTS
                    "notifications" -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
                    "quick_settings" -> AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
                    "power", "power_dialog" -> AccessibilityService.GLOBAL_ACTION_POWER_DIALOG
                    "split_screen" -> AccessibilityService.GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN
                    "lock_screen" -> AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN
                    "screenshot" -> AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT
                    else -> throw ApiError(
                        "unknown key \"$name\"; use back, home, recents, notifications, quick_settings, " +
                            "lock_screen, screenshot, split_screen, power, enter",
                    )
                }
                service.performGlobalAction(action)
            }
        }
        if (!ok) throw ApiError("the system refused key \"$name\"", 500)
        return JSONObject().put("key", name)
    }

    // ---------------------------------------------------------------- /open

    private fun open(a: JSONObject): JSONObject {
        val pkg = str(a, "package")
        val uri = str(a, "uri") ?: str(a, "url")
        val intent = when {
            uri != null -> Intent(Intent.ACTION_VIEW, Uri.parse(uri)).apply { if (pkg != null) setPackage(pkg) }
            pkg != null -> service.packageManager.getLaunchIntentForPackage(pkg)
                ?: throw ApiError("no launchable app with package \"$pkg\"", 404)
            else -> throw ApiError("give \"package\" or \"uri\"")
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (flag(a, "adjacent")) {
            // Opens in the other half when the screen is already split.
            intent.addFlags(Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        }
        try {
            onMain { service.startActivity(intent) }
        } catch (e: ActivityNotFoundException) {
            throw ApiError("nothing can open $uri", 404)
        }
        return JSONObject().apply {
            if (uri != null) put("uri", uri)
            put("package", pkg ?: intent.component?.packageName ?: JSONObject.NULL)
        }
    }

    // ---------------------------------------------------------------- /wait

    private fun waitFor(a: JSONObject): JSONObject {
        val sel = selector(a)
        val gone = flag(a, "gone")
        val timeout = a.optLong("timeout", 8000).coerceIn(0, 120_000)
        val interval = a.optLong("interval", 250).coerceIn(50, 5000)
        val started = SystemClock.uptimeMillis()
        while (true) {
            val found = Nodes.find(service, sel)
            val waited = SystemClock.uptimeMillis() - started
            if (gone && found == null) return JSONObject().put("gone", true).put("waited_ms", waited)
            if (!gone && found != null) {
                return JSONObject().put("node", Nodes.toJson(found.node, found.win)).put("waited_ms", waited)
            }
            if (waited >= timeout) {
                throw ApiError("timeout after ${timeout} ms waiting for ${sel.describe()} to ${if (gone) "disappear" else "appear"}", 408)
            }
            SystemClock.sleep(interval)
        }
    }

    // ---------------------------------------------------------------- /screenshot

    private fun screenshot(a: JSONObject): Any {
        if (Build.VERSION.SDK_INT < 30) throw ApiError("screenshots need Android 11+", 400)
        var bitmap = capture()
        val scale = a.optDouble("scale", 1.0)
        if (scale > 0 && scale < 1.0) {
            bitmap = Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1),
                (bitmap.height * scale).toInt().coerceAtLeast(1), true)
        }
        val jpeg = a.optString("format").lowercase().let { it == "jpg" || it == "jpeg" }
        val bytes = ByteArrayOutputStream().use { out ->
            bitmap.compress(if (jpeg) Bitmap.CompressFormat.JPEG else Bitmap.CompressFormat.PNG, a.optInt("quality", 85), out)
            out.toByteArray()
        }
        val mime = if (jpeg) "image/jpeg" else "image/png"
        if (flag(a, "raw")) return HttpResponse(200, mime, bytes)
        return JSONObject().put("mime", mime).put("width", bitmap.width).put("height", bitmap.height)
            .put(if (jpeg) "jpeg" else "png", Base64.encodeToString(bytes, Base64.NO_WRAP))
    }

    private fun capture(): Bitmap {
        var lastError = 0
        repeat(3) {
            val latch = CountDownLatch(1)
            val bitmap = AtomicReference<Bitmap?>(null)
            val error = AtomicReference(0)
            service.takeScreenshot(Display.DEFAULT_DISPLAY, screenshotExecutor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                        try {
                            val buffer = result.hardwareBuffer
                            val hw = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                            bitmap.set(hw?.copy(Bitmap.Config.ARGB_8888, false))
                            hw?.recycle()
                            buffer.close()
                        } finally {
                            latch.countDown()
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        error.set(errorCode); latch.countDown()
                    }
                })
            if (!latch.await(5, TimeUnit.SECONDS)) throw ApiError("screenshot timed out", 500)
            bitmap.get()?.let { return it }
            lastError = error.get()
            // ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT: the system allows about one screenshot per 1/3 s.
            if (lastError != AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) return@repeat
            SystemClock.sleep(400)
        }
        val why = when (lastError) {
            AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR -> "internal error"
            AccessibilityService.ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS -> "no accessibility access"
            AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> "called too often"
            AccessibilityService.ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY -> "invalid display"
            5 -> "invalid window"
            6 -> "secure window (FLAG_SECURE)"
            else -> "error $lastError"
        }
        throw ApiError("screenshot failed: $why", 500)
    }

    private val screenshotExecutor = Executors.newSingleThreadExecutor()

    // ---------------------------------------------------------------- /clipboard

    private fun clipboard(a: JSONObject): JSONObject {
        if (a.has("text") && !a.isNull("text")) {
            val text = a.getString("text")
            setClip(text)
            return JSONObject().put("set", true).put("length", text.length)
        }
        val text = onMain {
            val cm = service.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(service)?.toString()
        }
        val out = JSONObject().put("text", text ?: JSONObject.NULL)
        if (text == null) {
            out.put("note", "Android only lets the focused app or the keyboard read the clipboard; it may be hidden from Scrobot")
        }
        return out
    }

    private fun setClip(text: String) {
        onMain {
            val cm = service.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("Scrobot", text))
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun <T> onMain(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        val result = AtomicReference<Any?>(null)
        val failure = AtomicReference<Throwable?>(null)
        val latch = CountDownLatch(1)
        mainHandler.post {
            try {
                result.set(block())
            } catch (t: Throwable) {
                failure.set(t)
            } finally {
                latch.countDown()
            }
        }
        if (!latch.await(5, TimeUnit.SECONDS)) throw ApiError("main thread busy", 503)
        failure.get()?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return result.get() as T
    }

    private fun selector(a: JSONObject): Selector {
        val sel = Selector.from(a)
        if (sel.isEmpty) throw ApiError("give one of \"text\", \"desc\", \"id\" or \"hint\"")
        return sel
    }

    private fun int(a: JSONObject, name: String): Int {
        if (!a.has(name)) throw ApiError("missing \"$name\"")
        val v = try {
            a.getDouble(name).toInt()
        } catch (e: Exception) {
            throw ApiError("\"$name\" must be a number")
        }
        if (v < 0) throw ApiError("\"$name\" must not be negative")
        return v
    }

    private fun str(a: JSONObject, name: String): String? =
        if (a.has(name) && !a.isNull(name)) a.optString(name).takeIf { it.isNotEmpty() } else null

    private fun flag(a: JSONObject, name: String): Boolean {
        if (!a.has(name)) return false
        return when (val v = a.opt(name)) {
            is Boolean -> v
            is Number -> v.toInt() != 0
            else -> v.toString().let { it == "1" || it.equals("true", true) || it.equals("yes", true) }
        }
    }
}
