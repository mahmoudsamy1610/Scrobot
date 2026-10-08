package com.mahmoud.scrobot

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings

/**
 * Reads, and with the right permission repairs, Android's list of enabled accessibility services.
 *
 * The list itself is parsed by [AccessibilityList]. Switching Scrobot off and on inside that list is exactly what the user
 * would do by hand after a crash, and it is the only way to get Android to reconnect a service it
 * has marked as crashed.
 *
 * Writing the setting needs WRITE_SECURE_SETTINGS, which only the user can grant, once, over adb:
 *
 *     adb shell pm grant com.mahmoud.scrobot android.permission.WRITE_SECURE_SETTINGS
 *
 * Without it the app only reads. The repair never runs on its own — only when the user taps it.
 */
object AccessibilityRepair {

    // ---------------------------------------------------------------- Android

    fun isEnabledInSettings(context: Context): Boolean = try {
        AccessibilityList.contains(Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES))
    } catch (e: Throwable) {
        false
    }

    fun canRepair(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    /**
     * Switches Scrobot's own service off and then on again. Returns what happened, for a toast.
     * Only ever called from the button on the main screen.
     */
    fun repair(context: Context): String {
        if (!canRepair(context)) return "Scrobot does not hold WRITE_SECURE_SETTINGS"
        val resolver = context.contentResolver
        return try {
            val before = Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, AccessibilityList.without(before))
            Thread.sleep(600)
            Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, AccessibilityList.with(before))
            Settings.Secure.putInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            CrashLog.note("accessibility repaired from the app")
            "Switched the service off and on"
        } catch (e: Throwable) {
            CrashLog.error("repair failed", e)
            "Could not repair: $e"
        }
    }
}
