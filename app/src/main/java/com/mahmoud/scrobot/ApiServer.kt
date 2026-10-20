package com.mahmoud.scrobot

import android.content.Context

/**
 * Owns the local HTTP server and keeps it alive.
 *
 * The server deliberately does not belong to the accessibility service: if that service crashes or
 * is killed, the server stays up and answers honestly (`/ping` → `service:false` and why), instead
 * of leaving Termux with a connection refused and no explanation.
 *
 * A supervisor thread restarts the server if it ever dies, and retries with a growing delay when
 * the port cannot be opened.
 */
object ApiServer {

    private const val HEALTHY_CHECK_MS = 15_000L
    private const val FIRST_RETRY_MS = 2_000L
    private const val MAX_RETRY_MS = 60_000L

    @Volatile
    private var server: HttpServer? = null

    @Volatile
    private var instance: Api? = null

    @Volatile
    private var appContext: Context? = null

    /** Why the last start failed, for the main screen. Null once it is listening. */
    @Volatile
    var lastError: String? = null
        private set

    @Volatile
    private var wanted = false

    private var supervisor: Thread? = null

    val isRunning: Boolean get() = server?.isRunning == true

    /** The endpoints. They exist even while the accessibility service is away. */
    fun api(): Api? = instance

    @Synchronized
    fun start(context: Context) {
        val app = context.applicationContext
        appContext = app
        wanted = true
        tryStart(app)
        if (supervisor == null) {
            supervisor = Thread({ supervise() }, "scrobot-api-supervisor").apply {
                isDaemon = true
                start()
            }
        }
    }

    @Synchronized
    fun stop() {
        wanted = false
        try {
            server?.stop()
        } catch (e: Throwable) {
            CrashLog.error("stopping the API server", e)
        }
        server = null
        instance?.shutdown()
        instance = null
        ApiLog.note("API stopped")
        CrashLog.note("API server stopped")
    }

    /** True once it is listening. Records why not otherwise. */
    @Synchronized
    private fun tryStart(app: Context): Boolean {
        if (isRunning) return true
        // A half-open socket from a previous run would keep the port busy.
        server?.stop()
        server = null
        return try {
            val api = instance ?: Api(app) { HandsService.instance }.also { instance = it }
            val router = Router({ Prefs.token(app) }, ApiLog::add)
            api.register(router)
            val fresh = HttpServer(Prefs.PORT, router::handle, onError = CrashLog::error)
            fresh.start()
            server = fresh
            lastError = null
            ApiLog.note("API listening on 127.0.0.1:${Prefs.PORT}")
            CrashLog.note("API listening on 127.0.0.1:${Prefs.PORT}")
            true
        } catch (e: Throwable) {
            lastError = e.message ?: e.toString()
            server = null
            ApiLog.note("Could not open port ${Prefs.PORT}: $lastError")
            CrashLog.error("could not open port ${Prefs.PORT}", e)
            false
        }
    }

    /** Restarts the server if it dies, and backs off while the port stays busy. */
    private fun supervise() {
        var wait = HEALTHY_CHECK_MS
        while (true) {
            try {
                Thread.sleep(wait)
            } catch (e: InterruptedException) {
                return
            }
            val app = appContext
            if (!wanted || app == null) {
                wait = HEALTHY_CHECK_MS
                continue
            }
            if (isRunning) {
                wait = HEALTHY_CHECK_MS
                continue
            }
            CrashLog.note("API server is not listening; trying again")
            wait = if (tryStart(app)) HEALTHY_CHECK_MS else (wait * 2).coerceIn(FIRST_RETRY_MS, MAX_RETRY_MS)
        }
    }
}
