package com.mahmoud.scrobot

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import org.json.JSONArray
import org.json.JSONObject

/** A window on screen (split screen gives one per half) with its node tree root. */
class Win(
    val id: Int,
    val title: String?,
    val pkg: String?,
    val type: String,
    val layer: Int,
    val active: Boolean,
    val focused: Boolean,
    val bounds: Rect,
    val root: AccessibilityNodeInfo?,
) {
    /** Short label used in node output: the title, else the package. */
    val label: String get() = title?.takeIf { it.isNotBlank() } ?: pkg ?: "window $id"

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("title", title ?: JSONObject.NULL)
        .put("package", pkg ?: JSONObject.NULL)
        .put("type", type)
        .put("layer", layer)
        .put("active", active)
        .put("focused", focused)
        .put("bounds", JSONArray().put(bounds.left).put(bounds.top).put(bounds.right).put(bounds.bottom))
}

class Found(val node: AccessibilityNodeInfo, val win: Win)

/** What /click, /type and /wait look for. All given fields must match. */
class Selector(
    val text: String?,
    val desc: String?,
    val id: String?,
    val hint: String?,
    val pkg: String?,
    val exact: Boolean,
    val index: Int,
) {
    val isEmpty get() = text == null && desc == null && id == null && hint == null

    fun describe(): String = listOfNotNull(
        text?.let { "text=\"$it\"" }, desc?.let { "desc=\"$it\"" }, id?.let { "id=\"$it\"" },
        hint?.let { "hint=\"$it\"" }, pkg?.let { "package=\"$it\"" },
    ).joinToString(" ")

    fun matches(node: AccessibilityNodeInfo): Boolean {
        val nodeText = node.text?.toString()
        val nodeDesc = node.contentDescription?.toString()
        val nodeHint = Nodes.hintOf(node)
        if (text != null && !(same(nodeText, text) || same(nodeDesc, text) || same(nodeHint, text))) return false
        if (desc != null && !same(nodeDesc, desc)) return false
        if (hint != null && !same(nodeHint, hint)) return false
        if (id != null) {
            val rid = node.viewIdResourceName ?: return false
            val ok = if (id.contains(":id/")) rid == id else rid == id || rid.endsWith(":id/$id")
            if (!ok) return false
        }
        if (pkg != null && node.packageName?.toString() != pkg) return false
        return true
    }

    private fun same(value: String?, query: String): Boolean {
        if (value == null) return false
        return if (exact) value.trim().equals(query.trim(), ignoreCase = true)
        else value.contains(query, ignoreCase = true)
    }

    companion object {
        fun from(args: JSONObject): Selector {
            fun str(name: String): String? =
                args.optString(name, "").takeIf { args.has(name) && !args.isNull(name) && it.isNotEmpty() }
            return Selector(
                text = str("text"),
                desc = str("desc"),
                id = str("id"),
                hint = str("hint"),
                pkg = str("package"),
                exact = args.optBoolean("exact", false),
                index = args.optInt("index", 0),
            )
        }
    }
}

object Nodes {
    private const val MAX_DEPTH = 150

    /** All windows, topmost first, falling back to the active window if the list is empty. */
    fun windows(service: AccessibilityService): List<Win> {
        val list = try {
            service.windows ?: emptyList()
        } catch (e: Exception) {
            emptyList<AccessibilityWindowInfo>()
        }
        val wins = list.map { w ->
            val root = try { w.root } catch (e: Exception) { null }
            val r = Rect()
            w.getBoundsInScreen(r)
            Win(
                id = w.id,
                title = w.title?.toString(),
                pkg = root?.packageName?.toString(),
                type = typeName(w.type),
                layer = w.layer,
                active = w.isActive,
                focused = w.isFocused,
                bounds = r,
                root = root,
            )
        }.sortedByDescending { it.layer }
        if (wins.any { it.root != null }) return wins
        val root = service.rootInActiveWindow ?: return wins
        val r = Rect()
        root.getBoundsInScreen(r)
        return listOf(Win(root.windowId, null, root.packageName?.toString(), "application", 0, true, true, r, root))
    }

    /** Windows in the order /click and /type search them: the active app window, other app windows (top first), then the rest. */
    fun searchOrder(wins: List<Win>): List<Win> {
        val apps = wins.filter { it.type == "application" }.sortedByDescending { it.active }
        return apps + wins.filter { it.type != "application" }
    }

    fun typeName(type: Int): String = when (type) {
        AccessibilityWindowInfo.TYPE_APPLICATION -> "application"
        AccessibilityWindowInfo.TYPE_INPUT_METHOD -> "input_method"
        AccessibilityWindowInfo.TYPE_SYSTEM -> "system"
        AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY -> "accessibility_overlay"
        AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER -> "split_screen_divider"
        6 -> "magnification_overlay"
        else -> "type_$type"
    }

    fun walk(root: AccessibilityNodeInfo?, visit: (AccessibilityNodeInfo) -> Boolean) {
        if (root == null) return
        fun rec(node: AccessibilityNodeInfo, depth: Int): Boolean {
            if (!visit(node)) return false
            if (depth >= MAX_DEPTH) return true
            for (i in 0 until node.childCount) {
                val child = try { node.getChild(i) } catch (e: Exception) { null } ?: continue
                if (!rec(child, depth + 1)) return false
            }
            return true
        }
        rec(root, 0)
    }

    /** Visible nodes matching [sel] in every window, in search order. */
    fun findAll(service: AccessibilityService, sel: Selector, limit: Int = Int.MAX_VALUE): List<Found> {
        val out = ArrayList<Found>()
        for (win in searchOrder(windows(service))) {
            walk(win.root) { node ->
                if (node.isVisibleToUser && sel.matches(node)) out.add(Found(node, win))
                out.size < limit
            }
            if (out.size >= limit) break
        }
        return out
    }

    fun find(service: AccessibilityService, sel: Selector): Found? =
        findAll(service, sel, sel.index + 1).getOrNull(sel.index)

    fun hintOf(node: AccessibilityNodeInfo): String? = try {
        node.hintText?.toString()
    } catch (e: Throwable) {
        null
    }

    fun center(node: AccessibilityNodeInfo): IntArray {
        val r = Rect()
        node.getBoundsInScreen(r)
        return intArrayOf(r.centerX(), r.centerY())
    }

    /** Text the user actually typed (null while the field only shows its hint). */
    fun realText(node: AccessibilityNodeInfo): String? {
        val text = node.text?.toString() ?: return null
        if (node.isShowingHintText) return null
        return text
    }

    fun hasLabel(node: AccessibilityNodeInfo): Boolean =
        !node.text.isNullOrEmpty() || !node.contentDescription.isNullOrEmpty() ||
            !hintOf(node).isNullOrEmpty() || !node.viewIdResourceName.isNullOrEmpty()

    @Suppress("DEPRECATION") // isChecked; its replacement getChecked() needs Android 16
    fun toJson(node: AccessibilityNodeInfo, win: Win?): JSONObject {
        val r = Rect()
        node.getBoundsInScreen(r)
        val o = JSONObject()
        realText(node)?.let { o.put("text", it) }
        node.contentDescription?.let { o.put("desc", it.toString()) }
        hintOf(node)?.let { o.put("hint", it) }
        node.viewIdResourceName?.let { o.put("id", it) }
        node.className?.let { o.put("class", it.toString()) }
        node.packageName?.let { o.put("package", it.toString()) }
        o.put("bounds", JSONArray().put(r.left).put(r.top).put(r.right).put(r.bottom))
        o.put("center", JSONArray().put(r.centerX()).put(r.centerY()))
        o.put("clickable", node.isClickable)
        o.put("editable", node.isEditable)
        o.put("focused", node.isFocused)
        if (node.isCheckable) o.put("checked", node.isChecked)
        o.put("enabled", node.isEnabled)
        if (node.isScrollable) o.put("scrollable", true)
        if (node.isLongClickable) o.put("long_clickable", true)
        if (!node.isVisibleToUser) o.put("visible", false)
        if (win != null) {
            o.put("window", win.label)
            o.put("window_id", win.id)
        }
        return o
    }

    /** One line per node: `x,y  id  label  [window] flags`, grouped under a header per window. */
    @Suppress("DEPRECATION")
    fun toTextLine(node: AccessibilityNodeInfo, win: Win): String {
        val c = center(node)
        val text = realText(node)
        val desc = node.contentDescription?.toString()
        val hint = hintOf(node)
        val label = when {
            !text.isNullOrEmpty() -> text
            !desc.isNullOrEmpty() -> "desc:$desc"
            !hint.isNullOrEmpty() -> "hint:$hint"
            else -> "-"
        }.replace("\n", "⏎")
        val flags = buildList {
            if (node.isClickable) add("click")
            if (node.isEditable) add("edit")
            if (node.isFocused) add("focused")
            if (node.isCheckable) add(if (node.isChecked) "checked" else "unchecked")
            if (!node.isEnabled) add("disabled")
            if (node.isScrollable) add("scroll")
        }
        val id = node.viewIdResourceName ?: "-"
        return "${c[0]},${c[1]}  $id  $label  [${win.label}]" + if (flags.isEmpty()) "" else "  {${flags.joinToString(",")}}"
    }
}
