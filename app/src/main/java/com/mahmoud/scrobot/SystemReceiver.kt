package com.mahmoud.scrobot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import java.io.File

/**
 * Turns the handful of system broadcasts triggers care about into trigger events: the screen going
 * on or off, the phone being unlocked, and the charger. Registered while the foreground service
 * runs, because SCREEN_ON and SCREEN_OFF are only delivered to receivers registered in code.
 *
 * Boot is separate: it arrives before the accessibility service is ready, so it only leaves a note
 * that the heartbeat picks up once the service is connected.
 */
class SystemReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        CrashLog.guard("system broadcast ${intent.action}") {
            when (intent.action) {
                Intent.ACTION_SCREEN_ON -> Triggers.onSystem(context, Trigger.Kind.SCREEN, "on")
                Intent.ACTION_SCREEN_OFF -> Triggers.onSystem(context, Trigger.Kind.SCREEN, "off")
                Intent.ACTION_USER_PRESENT -> Triggers.onSystem(context, Trigger.Kind.SCREEN, "unlocked")
                Intent.ACTION_POWER_CONNECTED -> Triggers.onSystem(context, Trigger.Kind.POWER, "charging")
                Intent.ACTION_POWER_DISCONNECTED -> Triggers.onSystem(context, Trigger.Kind.POWER, "unplugged")
            }
        }
    }

    companion object {
        fun filter(): IntentFilter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
    }
}

/** Runs after the phone starts: brings the API back and arms any `boot` triggers. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        CrashLog.start(File(context.filesDir, "scrobot.log"))
        CrashLog.note("phone started (${intent.action})")
        Prefs.setBootPending(context, true)
        if (Prefs.isApiEnabled(context)) {
            ApiServer.start(context)
            KeepAliveService.start(context)
        }
    }
}
