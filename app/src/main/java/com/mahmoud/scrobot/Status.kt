package com.mahmoud.scrobot

/**
 * What the app honestly knows about itself: whether the accessibility service is switched on in
 * Settings, whether it is actually connected, and whether the local API is listening.
 *
 * Those are three different things. Android keeps the Settings switch ON after a service crashes,
 * shows "Not working", and refuses to reconnect it until the user turns it off and on again — so
 * "switched on" must never be reported as "working".
 *
 * Plain Kotlin, so every case is unit tested.
 */
object Status {

    enum class Level { OK, WARNING, ERROR }

    class State(
        val level: Level,
        val headline: String,
        val detail: String,
        /** What /ping says when something is wrong, and null when all is well. */
        val reason: String?,
    ) {
        val isWorking: Boolean get() = reason == null
    }

    /** The port the local API listens on. [Prefs] takes its value from here. */
    const val PORT = 8765

    const val TURN_ON = "Turn on Scrobot in Accessibility settings."

    const val NOT_CONNECTED =
        "Accessibility is ON but not connected. Turn Scrobot off and on in Accessibility settings."

    fun of(
        enabledInSettings: Boolean,
        connected: Boolean,
        serverRunning: Boolean,
        serverError: String? = null,
        port: Int = PORT,
    ): State {
        if (!connected) {
            return if (enabledInSettings) {
                State(Level.ERROR, "Accessibility is ON but not connected", NOT_CONNECTED, NOT_CONNECTED)
            } else {
                State(Level.ERROR, "Service: OFF", TURN_ON, "the accessibility service is switched off")
            }
        }
        if (!serverRunning) {
            val why = serverError ?: "the local API is not listening"
            return State(
                Level.WARNING,
                "Service: ON, but the API is not listening",
                "Port $port could not be opened: $why",
                why,
            )
        }
        return State(
            Level.OK,
            "Service: ON",
            "API: listening on http://127.0.0.1:$port",
            null,
        )
    }
}
