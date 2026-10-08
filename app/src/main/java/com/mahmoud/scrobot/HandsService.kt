package com.mahmoud.scrobot

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import java.io.File

/**
 * The accessibility service. While it is connected, [instance] is how the rest of the app reaches
 * the screen; the HTTP server itself belongs to [ApiServer] so that it survives this service
 * crashing or being killed, and can say so.
 */
class HandsService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        CrashLog.start(File(filesDir, "scrobot.log"))
        CrashLog.installHandler(mainLooper.thread, Thread.getDefaultUncaughtExceptionHandler())
        CrashLog.note("accessibility service connected")
        ApiLog.note("Accessibility service connected")
        Prefs.setDisconnectedSince(this, 0)
        if (Prefs.isApiEnabled(this)) {
            ApiServer.start(this)
            KeepAliveService.start(this)
        } else {
            ApiLog.note("API is stopped; open the app to start it again")
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Nothing to do: the API reads the window tree on demand.
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        disconnected("unbound")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        disconnected("destroyed")
        super.onDestroy()
    }

    /**
     * The service is going away. If Settings still lists it, this was a crash, a kill or an update,
     * so everything else stays up to report it; if it is no longer listed, the user switched it off
     * and the app should go quiet.
     */
    private fun disconnected(how: String) {
        if (instance !== this) return
        instance = null
        val stillEnabled = AccessibilityRepair.isEnabledInSettings(this)
        CrashLog.note("accessibility service $how (still switched on in Settings: $stillEnabled)")
        ApiLog.note("Accessibility service disconnected")
        if (stillEnabled) {
            Prefs.setDisconnectedSince(this, System.currentTimeMillis())
            KeepAliveService.refresh(this)
        } else {
            Prefs.setDisconnectedSince(this, 0)
            ApiServer.stop()
            KeepAliveService.stop(this)
        }
    }

    companion object {
        @Volatile
        var instance: HandsService? = null
            private set
    }
}
