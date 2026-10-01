package com.mahmoud.scrobot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** The notification's Stop action: turns the API off until the app is opened again. */
class StopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Prefs.setApiEnabled(context, false)
        HandsService.instance?.stopServer()
        KeepAliveService.stop(context)
        ApiLog.note("Stopped from the notification")
    }
}
