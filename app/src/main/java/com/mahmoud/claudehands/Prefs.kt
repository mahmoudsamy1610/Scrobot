package com.mahmoud.claudehands

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import java.io.File
import java.security.SecureRandom

object Prefs {
    private const val FILE = "claude_hands"
    private const val KEY_TOKEN = "token"
    private const val KEY_API_ENABLED = "api_enabled"
    private const val TOKEN_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"

    const val PORT = 8765
    const val VERSION = "1.0"

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    @Volatile
    private var cachedToken: String? = null

    /** Returns the API token, creating a random 32-char one on first use. Also (re)writes token.txt once per process. */
    fun token(context: Context): String {
        cachedToken?.let { return it }
        synchronized(this) {
            cachedToken?.let { return it }
            val p = prefs(context)
            var token = p.getString(KEY_TOKEN, null)
            if (token.isNullOrEmpty()) {
                token = newToken()
                p.edit().putString(KEY_TOKEN, token).commit()
            }
            exportToken(context, token)
            cachedToken = token
            return token
        }
    }

    @Synchronized
    fun regenerateToken(context: Context): String {
        val token = newToken()
        prefs(context).edit().putString(KEY_TOKEN, token).commit()
        exportToken(context, token)
        cachedToken = token
        return token
    }

    /** False after the notification's Stop action, until the app is opened again. */
    fun isApiEnabled(context: Context) = prefs(context).getBoolean(KEY_API_ENABLED, true)

    fun setApiEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_API_ENABLED, enabled).apply()
    }

    fun tokenFile(context: Context): File? = context.getExternalFilesDir(null)?.let { File(it, "token.txt") }

    private fun exportToken(context: Context, token: String) {
        try {
            val file = tokenFile(context) ?: return
            if (file.exists() && file.readText().trim() == token) return
            file.parentFile?.mkdirs()
            file.writeText(token + "\n")
        } catch (e: Exception) {
            Log.w("ClaudeHands", "could not write token file", e)
        }
    }

    private fun newToken(): String {
        val random = SecureRandom()
        return String(CharArray(32) { TOKEN_CHARS[random.nextInt(TOKEN_CHARS.length)] })
    }
}
