package com.mahmoud.scrobot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.io.File

/**
 * Runs after the app is updated. An update unbinds the accessibility service, and Android does not
 * always bind it again — least of all if it had crashed before. So the foreground service is
 * started again (which brings the API back), and the heartbeat in [KeepAliveService] warns the user
 * if accessibility stays away.
 */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        CrashLog.start(File(context.filesDir, "scrobot.log"))
        CrashLog.note("app updated (${intent.action})")
        // A fresh episode: let the heartbeat warn again if the service does not come back.
        Prefs.setWarned(context, false)
        Prefs.setDisconnectedSince(context, System.currentTimeMillis())
        if (Prefs.isApiEnabled(context)) {
            ApiServer.start(context)
            KeepAliveService.start(context)
        }
    }
}
