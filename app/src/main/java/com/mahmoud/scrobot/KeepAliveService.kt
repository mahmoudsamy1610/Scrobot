package com.mahmoud.scrobot

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings

/**
 * The foreground service: a quiet, permanent notification so Samsung's battery management leaves
 * the process alone, and a heartbeat that keeps the local API listening and tells the user when
 * accessibility has stopped working.
 */
class KeepAliveService : Service() {

    private val beat = Handler(Looper.getMainLooper())
    private val heartbeat = object : Runnable {
        override fun run() {
            CrashLog.guard("heartbeat") { check() }
            beat.postDelayed(this, HEARTBEAT_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private var systemReceiver: SystemReceiver? = null

    override fun onCreate() {
        super.onCreate()
        CrashLog.start(java.io.File(filesDir, "scrobot.log"))
        CrashLog.installHandler(mainLooper.thread, Thread.getDefaultUncaughtExceptionHandler())
        try {
            val receiver = SystemReceiver()
            registerReceiver(receiver, SystemReceiver.filter())
            systemReceiver = receiver
        } catch (e: Throwable) {
            CrashLog.error("could not listen for screen and charger changes", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            val notification = buildNotification()
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Throwable) {
            CrashLog.error("could not show the status notification", e)
            stopSelf()
            return START_NOT_STICKY
        }
        if (Prefs.isApiEnabled(this)) ApiServer.start(this)
        beat.removeCallbacks(heartbeat)
        beat.postDelayed(heartbeat, HEARTBEAT_MS)
        // START_STICKY: if Android kills the process, it starts this service again.
        return START_STICKY
    }

    override fun onDestroy() {
        beat.removeCallbacks(heartbeat)
        systemReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (e: Throwable) {
                CrashLog.note("receiver was already gone")
            }
        }
        systemReceiver = null
        super.onDestroy()
    }

    /** Keeps the API up, refreshes the notification, and warns once when accessibility is stuck. */
    private fun check() {
        if (Prefs.isApiEnabled(this) && !ApiServer.isRunning) ApiServer.start(this)
        updateNotification()
        // Scheduled triggers, and the one run after the phone starts.
        CrashLog.guard("checking triggers") { Triggers.tick(this) }

        val connected = HandsService.instance != null
        if (connected) {
            Prefs.setDisconnectedSince(this, 0)
            Prefs.setWarned(this, false)
            return
        }
        if (!AccessibilityRepair.isEnabledInSettings(this)) return
        val since = Prefs.disconnectedSince(this)
        if (since == 0L) {
            Prefs.setDisconnectedSince(this, System.currentTimeMillis())
            return
        }
        if (System.currentTimeMillis() - since > WARN_AFTER_MS && !Prefs.wasWarned(this)) {
            Prefs.setWarned(this, true)
            warn(
                "Scrobot is not connected",
                "Accessibility is ON but not working. Turn Scrobot off and on in Accessibility settings.",
            )
        }
    }

    private fun state(): Status.State = Status.of(
        enabledInSettings = AccessibilityRepair.isEnabledInSettings(this),
        connected = HandsService.instance != null,
        serverRunning = ApiServer.isRunning,
        serverError = ApiServer.lastError,
    )

    // ---------------------------------------------------------------- notifications

    private fun channels(): NotificationManager {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(CHANNEL_ID, "Scrobot status", NotificationManager.IMPORTANCE_LOW)
            channel.description = "Shown while the local API is running"
            channel.setShowBadge(false)
            nm.createNotificationChannel(channel)
        }
        if (nm.getNotificationChannel(ALERT_CHANNEL_ID) == null) {
            val channel = NotificationChannel(ALERT_CHANNEL_ID, "Scrobot problems", NotificationManager.IMPORTANCE_DEFAULT)
            channel.description = "Shown when Scrobot needs attention"
            nm.createNotificationChannel(channel)
        }
        return nm
    }

    private fun buildNotification(): Notification {
        channels()
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getBroadcast(
            this, 1, Intent(this, StopReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val state = state()
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_robot)
            .setContentTitle(if (state.isWorking) "Scrobot is running" else "Scrobot needs attention")
            .setContentText(if (state.isWorking) "Local API on 127.0.0.1:${Prefs.PORT}" else state.headline)
            .setStyle(Notification.BigTextStyle().bigText(state.detail))
            .setOngoing(true)
            .setShowWhen(false)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
    }

    private fun updateNotification() {
        try {
            channels().notify(NOTIFICATION_ID, buildNotification())
        } catch (e: Throwable) {
            CrashLog.error("could not refresh the notification", e)
        }
    }

    private fun warn(title: String, text: String) {
        try {
            val open = PendingIntent.getActivity(
                this, 2, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val notification = Notification.Builder(this, ALERT_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_robot)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setContentIntent(open)
                .build()
            channels().notify(ALERT_ID, notification)
            CrashLog.note("warned the user: $title")
        } catch (e: Throwable) {
            CrashLog.error("could not post the warning", e)
        }
    }

    companion object {
        private const val CHANNEL_ID = "status"
        private const val ALERT_CHANNEL_ID = "alerts"
        private const val NOTIFICATION_ID = 1
        private const val ALERT_ID = 2
        private const val HEARTBEAT_MS = 20_000L

        /** How long accessibility may stay away before the user is told. */
        private const val WARN_AFTER_MS = 45_000L

        fun start(context: Context) {
            try {
                context.startForegroundService(Intent(context, KeepAliveService::class.java))
            } catch (e: Throwable) {
                // Android may refuse to start a foreground service from the background; the app
                // retries the next time it is opened. The accessibility binding keeps us alive meanwhile.
                CrashLog.note("foreground service not started: ${e.javaClass.simpleName}")
            }
        }

        /** Nudges the notification to redraw after the state changed. */
        fun refresh(context: Context) = start(context)

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, KeepAliveService::class.java))
            } catch (e: Throwable) {
                CrashLog.error("stopping the foreground service", e)
            }
        }
    }
}
