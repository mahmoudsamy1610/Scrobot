package com.mahmoud.scrobot

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The scripts folder. Scripts are imported (from the app, or through `/script/import`) and run;
 * nothing is written by hand on the phone.
 */
object ScriptStore {

    /** What a script is written in, decided by its file extension. */
    enum class Kind(val label: String, val extensions: List<String>) {
        ACTIONS("actions", listOf("scro", "scrobot", "txt", "")),
        JSON("json", listOf("json")),
        JS("js", listOf("js", "mjs")),
        SHELL("sh", listOf("sh", "bash")),
        ;

        companion object {
            fun of(name: String): Kind {
                val ext = name.substringAfterLast('.', "").lowercase()
                return entries.firstOrNull { ext in it.extensions } ?: ACTIONS
            }
        }
    }

    const val MAX_SIZE = 1024 * 1024

    fun dir(context: Context): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        return File(base, "scripts").apply { mkdirs() }
    }

    /** Where `shot` saves screenshots taken by scripts. */
    fun shotsDir(context: Context): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        return File(base, "shots").apply { mkdirs() }
    }

    fun list(context: Context): List<File> =
        dir(context).listFiles { f -> f.isFile && !f.name.startsWith(".") }
            ?.sortedBy { it.name.lowercase() } ?: emptyList()

    /** Finds a script by file name, or by name without the extension. */
    fun find(context: Context, name: String): File? {
        val safe = safeName(name)
        val exact = File(dir(context), safe)
        if (exact.isFile) return exact
        return list(context).firstOrNull { it.name.substringBeforeLast('.').equals(safe, true) }
    }

    fun save(context: Context, name: String, source: String): File {
        if (source.length > MAX_SIZE) throw ApiError("script is larger than ${MAX_SIZE / 1024} KB")
        val file = File(dir(context), safeName(name))
        file.writeText(source)
        return file
    }

    fun delete(context: Context, name: String): Boolean =
        find(context, name)?.delete() ?: false

    /** Rejects path separators so a script name can never escape the scripts folder. */
    fun safeName(name: String): String {
        val trimmed = name.trim().substringAfterLast('/').substringAfterLast('\\')
        if (trimmed.isEmpty() || trimmed == "." || trimmed == "..") throw ApiError("bad script name \"$name\"")
        return trimmed
    }

    fun toJson(file: File): JSONObject = JSONObject()
        .put("name", file.name)
        .put("kind", Kind.of(file.name).label)
        .put("size", file.length())
        .put("modified", file.lastModified())

    fun listJson(context: Context): JSONArray {
        val array = JSONArray()
        for (file in list(context)) array.put(toJson(file))
        return array
    }
}
