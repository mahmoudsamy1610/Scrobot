package com.mahmoud.scrobot

import android.content.Context
import java.io.File

/** Where triggers live: `triggers.json`, in the same folder as the scripts they run. */
object TriggerStore {

    fun file(context: Context): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        return File(base, "triggers.json")
    }

    /** Never throws: a broken file is logged and treated as "no triggers", so nothing stalls. */
    fun load(context: Context): List<Trigger> = try {
        val file = file(context)
        if (file.isFile) Trigger.listFrom(file.readText()) else emptyList()
    } catch (e: Throwable) {
        CrashLog.error("could not read ${file(context).name}", e)
        emptyList()
    }

    fun save(context: Context, triggers: List<Trigger>) {
        val file = file(context)
        file.parentFile?.mkdirs()
        file.writeText(Trigger.listToJson(triggers).toString(2))
    }

    /** Adds a trigger, or replaces the one with the same name. */
    fun add(context: Context, trigger: Trigger): List<Trigger> {
        val kept = load(context).filter { !it.name.equals(trigger.name, true) }
        val all = kept + trigger
        save(context, all)
        return all
    }

    fun remove(context: Context, name: String): Boolean {
        val all = load(context)
        val kept = all.filter { !it.name.equals(name, true) }
        if (kept.size == all.size) return false
        save(context, kept)
        return true
    }

    fun setEnabled(context: Context, name: String, enabled: Boolean): Trigger? {
        val all = load(context)
        val found = all.firstOrNull { it.name.equals(name, true) } ?: return null
        val changed = Trigger(
            name = found.name,
            kind = found.kind,
            match = found.match,
            script = found.script,
            args = found.args,
            enabled = enabled,
            cooldown = found.cooldown,
            every = found.every,
            at = found.at,
            pkg = found.pkg,
            window = found.window,
        )
        save(context, all.map { if (it.name.equals(name, true)) changed else it })
        return changed
    }
}
