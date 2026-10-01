package com.mahmoud.scrobot

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.provider.OpenableColumns
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
import org.json.JSONObject

/** The single, simple screen: status, token, battery exemption and the call log. */
class MainActivity : Activity() {

    private lateinit var statusView: TextView
    private lateinit var tokenView: TextView
    private lateinit var batteryView: TextView
    private lateinit var logView: TextView
    private lateinit var scriptsView: LinearLayout
    private lateinit var scriptsHeader: TextView
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
        refreshScripts()
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

        column.addView(header("Scripts"))
        scriptsHeader = text("", 13f)
        column.addView(scriptsHeader)
        column.addView(row(
            button("Import script…") { pickScripts() },
            button("Stop") { stopScript() },
        ))
        scriptsView = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(scriptsView)

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

    // ---------------------------------------------------------------- scripts

    private fun refreshScripts() {
        scriptsView.removeAllViews()
        val files = ScriptStore.list(this)
        scriptsHeader.text = if (files.isEmpty()) {
            "No scripts yet. Import .scro, .json, .js or .sh files; they are kept in\n${ScriptStore.dir(this)}"
        } else {
            "${files.size} script${if (files.size == 1) "" else "s"} in\n${ScriptStore.dir(this)}"
        }
        for (file in files) {
            val kind = ScriptStore.Kind.of(file.name).label
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            line.addView(
                text("${file.name}\n$kind, ${file.length()} bytes", 14f),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            line.addView(button("Run") { runScript(file.name) })
            line.addView(button("⋯") { scriptMenu(file.name) })
            scriptsView.addView(line)
        }
    }

    private fun api(): Api? {
        val api = HandsService.instance?.api
        if (api == null) {
            Toast.makeText(this, "Turn the accessibility service on first", Toast.LENGTH_LONG).show()
        }
        return api
    }

    private fun runScript(name: String) {
        val api = api() ?: return
        try {
            api.invoke("/script/run", JSONObject().put("name", name).put("background", true))
            Toast.makeText(this, "Running $name", Toast.LENGTH_SHORT).show()
        } catch (e: ApiError) {
            Toast.makeText(this, e.message, Toast.LENGTH_LONG).show()
        }
    }

    private fun stopScript() {
        val api = api() ?: return
        try {
            api.invoke("/script/stop", JSONObject())
            Toast.makeText(this, "Stopping", Toast.LENGTH_SHORT).show()
        } catch (e: ApiError) {
            Toast.makeText(this, e.message ?: "nothing to stop", Toast.LENGTH_SHORT).show()
        }
    }

    private fun scriptMenu(name: String) {
        AlertDialog.Builder(this)
            .setTitle(name)
            .setItems(arrayOf("Run", "Show", "Delete")) { _, which ->
                when (which) {
                    0 -> runScript(name)
                    1 -> showScript(name)
                    2 -> {
                        ScriptStore.delete(this, name)
                        refreshScripts()
                    }
                }
            }
            .show()
    }

    private fun showScript(name: String) {
        val file = ScriptStore.find(this, name) ?: return
        val body = text(file.readText().take(8000), 12f, mono = true).apply { setTextIsSelectable(true) }
        AlertDialog.Builder(this)
            .setTitle(name)
            .setView(ScrollView(this).apply { addView(body); setPadding(dp(16), dp(8), dp(16), dp(8)) })
            .setPositiveButton("Close", null)
            .show()
    }

    /** Copies files chosen with the system picker into the scripts folder. */
    private fun pickScripts() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        try {
            startActivityForResult(intent, REQUEST_IMPORT)
        } catch (e: Exception) {
            Toast.makeText(this, "No file picker on this phone", Toast.LENGTH_LONG).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_IMPORT || resultCode != RESULT_OK || data == null) return
        val uris = ArrayList<Uri>()
        data.clipData?.let { clip -> for (i in 0 until clip.itemCount) uris.add(clip.getItemAt(i).uri) }
        data.data?.let { uris.add(it) }
        var imported = 0
        for (uri in uris) {
            try {
                val name = displayName(uri)
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw ApiError("could not read $name")
                if (bytes.size > ScriptStore.MAX_SIZE) throw ApiError("$name is too large")
                ScriptStore.save(this, name, String(bytes, Charsets.UTF_8))
                imported++
            } catch (e: Exception) {
                Toast.makeText(this, e.message ?: "import failed", Toast.LENGTH_LONG).show()
            }
        }
        if (imported > 0) {
            ApiLog.note("Imported $imported script${if (imported == 1) "" else "s"}")
            Toast.makeText(this, "Imported $imported", Toast.LENGTH_SHORT).show()
        }
        refreshScripts()
    }

    private fun displayName(uri: Uri): String {
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (column >= 0 && cursor.moveToFirst()) {
                cursor.getString(column)?.let { return it }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "script-${System.currentTimeMillis()}"
    }

    // ---------------------------------------------------------------- views

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

    companion object {
        private const val REQUEST_IMPORT = 7
    }
}
