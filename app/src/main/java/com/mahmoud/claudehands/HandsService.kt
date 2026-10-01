package com.mahmoud.claudehands

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent

/** The accessibility service. It owns the local HTTP API while it is connected. */
class HandsService : AccessibilityService() {

    private var server: HttpServer? = null
    private var api: Api? = null

    val isServerRunning: Boolean get() = server?.isRunning == true

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        ApiLog.note("Accessibility service connected")
        if (Prefs.isApiEnabled(this)) {
            startServer()
            KeepAliveService.start(this)
        } else {
            ApiLog.note("API is stopped; open the app to start it again")
        }
    }

    @Synchronized
    fun startServer() {
        if (isServerRunning) return
        val api = Api(this)
        val router = Router({ Prefs.token(this) }, ApiLog::add)
        api.register(router)
        val server = HttpServer(Prefs.PORT, router::handle)
        try {
            server.start()
            this.api = api
            this.server = server
            ApiLog.note("API listening on 127.0.0.1:${Prefs.PORT}")
        } catch (e: Exception) {
            api.shutdown()
            ApiLog.note("Could not start API on port ${Prefs.PORT}: $e")
        }
    }

    @Synchronized
    fun stopServer() {
        val wasRunning = server != null
        server?.stop()
        server = null
        api?.shutdown()
        api = null
        if (wasRunning) ApiLog.note("API stopped")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Nothing to do: the API reads the window tree on demand.
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        shutdown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    private fun shutdown() {
        stopServer()
        if (instance === this) {
            instance = null
            ApiLog.note("Accessibility service disconnected")
            KeepAliveService.stop(this)
        }
    }

    companion object {
        @Volatile
        var instance: HandsService? = null
            private set
    }
}
