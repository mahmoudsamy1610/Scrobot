package com.mahmoud.claudehands

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** The single, simple screen: status, token, battery exemption and the call log. */
class MainActivity : Activity() {

    private lateinit var statusView: TextView
    private lateinit var tokenView: TextView
    private lateinit var batteryView: TextView
    private lateinit var logView: TextView
    private val logListener: () -> Unit = { runOnUiThread { refresh() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        // Opening the app re-enables the API after the notification's Stop action.
        Prefs.setApiEnabled(this, true)
        HandsService.instance?.let {
            it.startServer()
            KeepAliveService.start(this)
        }
        ApiLog.addListener(logListener)
        refresh()
    }

    override fun onPause() {
        ApiLog.removeListener(logListener)
        super.onPause()
    }

    private fun refresh() {
        val service = HandsService.instance
        val enabledInSettings = isEnabledInSettings()
        statusView.text = when {
            service != null && service.isServerRunning -> "Service: ON\nAPI: listening on http://127.0.0.1:${Prefs.PORT}"
            service != null -> "Service: ON\nAPI: not running (see log below)"
            enabledInSettings -> "Service: switched on but not connected yet.\nTry switching it off and on again."
            else -> "Service: OFF\nTurn on \"Scrobot\" in Accessibility settings."
        }
        statusView.setTextColor(if (service?.isServerRunning == true) 0xFF2E7D32.toInt() else 0xFFC62828.toInt())
        tokenView.text = Prefs.token(this)
        val pm = getSystemService(PowerManager::class.java)
        batteryView.text = if (pm.isIgnoringBatteryOptimizations(packageName)) {
            "Battery optimisation: excluded ✓"
        } else {
            "Battery optimisation: ON (Android may stop the app)"
        }
        val lines = ApiLog.lines()
        logView.text = if (lines.isEmpty()) "No calls yet." else lines.joinToString("\n")
    }

    private fun isEnabledInSettings(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return enabled.split(':').any { it.startsWith("$packageName/") }
    }

    // ---------------------------------------------------------------- UI

    private fun buildUi(): View {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(20)
            setPadding(pad, pad, pad, pad)
        }

        column.addView(text("Scrobot", 26f, bold = true))
        column.addView(text("Lets Claude in Termux control this phone through a local API.", 14f))

        column.addView(header("Status"))
        statusView = text("", 16f, bold = true)
        column.addView(statusView)
        column.addView(button("Open Accessibility settings") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        if (Build.VERSION.SDK_INT >= 33) {
            column.addView(text("If the switch is greyed out: App info → ⋮ → Allow restricted settings.", 14f))
            column.addView(button("Open App info") {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            })
        }

        column.addView(header("Token"))
        tokenView = text("", 16f, mono = true).apply { setTextIsSelectable(true) }
        column.addView(tokenView)
        column.addView(row(
            button("Copy") {
                val cm = getSystemService(ClipboardManager::class.java)
                cm.setPrimaryClip(ClipData.newPlainText("Scrobot token", Prefs.token(this)))
                Toast.makeText(this, "Token copied", Toast.LENGTH_SHORT).show()
            },
            button("New token") {
                Prefs.regenerateToken(this)
                refresh()
                Toast.makeText(this, "New token created", Toast.LENGTH_SHORT).show()
            },
        ))
        val file = Prefs.tokenFile(this)?.absolutePath ?: "(external storage unavailable)"
        column.addView(text("API: http://127.0.0.1:${Prefs.PORT}  (header X-Token)\nToken file: $file", 13f, mono = true))

        column.addView(header("Battery"))
        batteryView = text("", 15f)
        column.addView(batteryView)
        column.addView(button("Ignore battery optimisations") { requestBatteryExemption() })

        column.addView(header("Last 50 API calls"))
        logView = text("", 12f, mono = true).apply { setTextIsSelectable(true) }
        column.addView(logView)

        val scroll = ScrollView(this)
        scroll.addView(column)
        // Target SDK 35+ draws edge to edge; keep content clear of the status and navigation bars.
        scroll.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            } else {
                @Suppress("DEPRECATION")
                v.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }
        return scroll
    }

    private fun requestBatteryExemption() {
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            Toast.makeText(this, "Already excluded", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            @Suppress("BatteryLife")
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun text(s: String, sizeSp: Float, bold: Boolean = false, mono: Boolean = false) = TextView(this).apply {
        text = s
        textSize = sizeSp
        if (bold || mono) typeface = Typeface.create(if (mono) Typeface.MONOSPACE else Typeface.DEFAULT, if (bold) Typeface.BOLD else Typeface.NORMAL)
        setPadding(0, dp(4), 0, dp(4))
    }

    private fun header(s: String) = text(s, 18f, bold = true).apply { setPadding(0, dp(20), 0, dp(4)) }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun row(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        views.forEach {
            addView(it, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
    }
}
