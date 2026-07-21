package dev.yuhee.ailimits

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.materialswitch.MaterialSwitch
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private val scope = MainScope()
    private var codexServer: CodexLoginServer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<Button>(R.id.btnClaudeSignIn).setOnClickListener {
            val url = ClaudeApi.beginLogin(this)
            openUrl(url)
            toast("Approve access, copy the code, come back and tap “Paste code”")
        }

        findViewById<Button>(R.id.btnClaudePaste).setOnClickListener {
            promptText("Paste authorization code", "code#state from the browser") { text ->
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) { ClaudeApi.finishLogin(this@MainActivity, text) }
                        toast("Claude signed in ✓")
                        afterSetupChanged()
                    } catch (e: Exception) {
                        showError("Sign-in failed", e)
                    }
                }
            }
        }

        findViewById<Button>(R.id.btnClaudeSignOut).setOnClickListener {
            Prefs.clearClaude(this); updateStatus(); WidgetRenderer.updateAll(this)
        }

        findViewById<Button>(R.id.btnCodexSignIn).setOnClickListener {
            codexServer?.stop()
            Prefs.clearCodexPending(this)
            val flow = CodexApi.beginLogin(this)
            codexServer = CodexLoginServer(applicationContext, flow) { err ->
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    if (err != null) {
                        showError("Codex sign-in failed", RuntimeException(err))
                    }
                    updateStatus()
                }
            }.also { it.start() }
            openUrl(flow.url)
            toast("Log in with ChatGPT, then come back here to finish")
        }

        findViewById<Button>(R.id.btnCodexPaste).setOnClickListener {
            promptText(
                "Paste callback link",
                "http://localhost:1455/auth/callback?code=…"
            ) { text ->
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) { CodexApi.finishLoginManual(this@MainActivity, text) }
                        toast("Codex signed in ✓")
                        afterSetupChanged()
                    } catch (e: Exception) {
                        showError("Codex sign-in failed", e)
                    }
                }
            }
        }

        findViewById<Button>(R.id.btnCodexSignOut).setOnClickListener {
            Prefs.clearCodex(this); updateStatus(); WidgetRenderer.updateAll(this)
        }

        findViewById<Button>(R.id.btnRefresh).setOnClickListener { refreshNowUi() }
        findViewById<Button>(R.id.btnDiagnostics).setOnClickListener { copyDiagnostics() }

        val intervals = listOf(15, 30, 60, 120)
        spinner(R.id.spinnerInterval, intervals.map { if (it < 60) "$it min" else "${it / 60} h" },
            intervals.indexOf(Prefs.refreshMinutes(this)).coerceAtLeast(0)) { pos ->
            val m = intervals[pos]
            if (m != Prefs.refreshMinutes(this)) {
                Prefs.setRefreshMinutes(this, m)
                RefreshWorker.schedulePeriodic(this)
                toast("Auto-refresh every ${if (m < 60) "$m min" else "${m / 60} h"}")
            }
        }

        setUpAppearance()
        setUpAlerts()
        setUpPreview()
    }

    // ---------------------------------------------------------------- settings

    private fun setUpAppearance() {
        syncProviderSwitches()

        val themes = ThemeMode.entries
        spinner(R.id.spinnerTheme, themes.map { it.label }, themes.indexOf(Settings.themeMode(this))) { pos ->
            if (themes[pos] != Settings.themeMode(this)) {
                Settings.setThemeMode(this, themes[pos]); applyWidgetChange()
            }
        }

        // SeekBar spans 40..100% opacity.
        val seek = findViewById<SeekBar>(R.id.seekOpacity)
        seek.progress = Settings.opacity(this) - 40
        updateOpacityLabel(Settings.opacity(this))
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                if (fromUser) { Settings.setOpacity(this@MainActivity, value + 40); updateOpacityLabel(value + 40); renderPreview() }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) { applyWidgetChange() }
        })

        switch(R.id.swProjection, Settings.showProjection(this)) { on ->
            Settings.setShowProjection(this, on); applyWidgetChange()
        }
        switch(R.id.swSparkline, Settings.showSparkline(this)) { on ->
            Settings.setShowSparkline(this, on); applyWidgetChange()
        }
    }

    /**
     * Turning one provider off may force the other back on, so both switches are
     * re-bound from the stored state rather than trusting what the user tapped.
     */
    private fun syncProviderSwitches() {
        switch(R.id.swShowClaude, Settings.showClaude(this)) { on ->
            Settings.setShowClaude(this, on); syncProviderSwitches(); applyWidgetChange()
        }
        switch(R.id.swShowCodex, Settings.showCodex(this)) { on ->
            Settings.setShowCodex(this, on); syncProviderSwitches(); applyWidgetChange()
        }
        findViewById<TextView>(R.id.soloHint).visibility =
            if (Settings.solo(this)) View.VISIBLE else View.GONE
    }

    private fun updateOpacityLabel(v: Int) {
        findViewById<TextView>(R.id.opacityLabel).text = "Background opacity — $v%"
    }

    private fun setUpAlerts() {
        switch(R.id.swNotify, Settings.notifyEnabled(this)) { on ->
            if (on && !Notifier.canPost(this)) {
                requestNotifications.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            } else {
                Settings.setNotifyEnabled(this, on)
                if (on) Notifier.ensureChannel(this)
            }
        }
        val levels = listOf(75, 80, 90, 95)
        spinner(R.id.spinnerThreshold, levels.map { "$it%" },
            levels.indexOf(Settings.notifyThreshold(this)).coerceAtLeast(0)) { pos ->
            Settings.setNotifyThreshold(this, levels[pos])
        }
    }

    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            Settings.setNotifyEnabled(this, granted)
            setUpAlerts()   // re-bind so the switch reflects what was actually granted
            if (granted) Notifier.ensureChannel(this) else toast("Notification permission denied")
        }

    // ---------------------------------------------------------------- preview

    private fun setUpPreview() {
        val styles = WidgetRenderer.Style.entries
        val labels = listOf("Detail", "Slim bars", "Rings", "History")
        spinner(R.id.spinnerStyle, labels, styles.indexOf(Settings.previewStyle(this))) { pos ->
            Settings.setPreviewStyle(this, styles[pos]); renderPreview()
        }
        findViewById<SeekBar>(R.id.seekPreviewSize)
            .setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) = renderPreview()
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
    }

    /**
     * Renders the real widget so the preview cannot drift from the home screen.
     * The slider sweeps the height across every layout tier the widget supports.
     */
    private fun renderPreview() {
        val img = findViewById<ImageView>(R.id.preview) ?: return
        val frac = findViewById<SeekBar>(R.id.seekPreviewSize).progress / 100f
        val hDp = 64f + frac * 176f          // 64dp .. 240dp spans compact -> rich
        val wDp = 264f
        val snap = UsageRepo.load(this)
        val hist = UsageRepo.history(this)
        runCatching {
            val (bmp, _) = WidgetRenderer.render(
                this, Settings.previewStyle(this), wDp, hDp, snap, hist,
                o = WidgetRenderer.optsFrom(this),
            )
            img.setImageBitmap(bmp)
        }
        findViewById<TextView>(R.id.previewSizeLabel).text =
            "${wDp.toInt()} × ${hDp.toInt()} dp — drag to see it reflow"
    }

    /** Persisted setting changed: redraw both the preview and any live widgets. */
    private fun applyWidgetChange() {
        renderPreview()
        WidgetRenderer.updateAll(this)
    }

    // ---------------------------------------------------------------- helpers

    private fun spinner(id: Int, labels: List<String>, selected: Int, onPick: (Int) -> Unit) {
        val sp = findViewById<Spinner>(id)
        sp.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        sp.setSelection(selected.coerceIn(0, labels.size - 1))
        var first = true
        sp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                if (first) { first = false; return }   // setSelection fires once on attach
                onPick(pos)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    /**
     * Detach before setting the value, so a programmatic sync cannot be mistaken
     * for a user action. Gating on `isPressed` instead would have silently ignored
     * toggles from a keyboard or accessibility service.
     */
    private fun switch(id: Int, checked: Boolean, onChange: (Boolean) -> Unit) {
        val sw = findViewById<MaterialSwitch>(id)
        sw.setOnCheckedChangeListener(null)
        sw.isChecked = checked
        sw.setOnCheckedChangeListener { _, on -> onChange(on) }
    }

    private fun copyDiagnostics() {
        val snap = UsageRepo.load(this)
        val sb = StringBuilder("Auspex diagnostics\n")
        sb.append("version 2.2\n")
        sb.append("updated: ").append(if (snap.fetchedAt > 0) Date(snap.fetchedAt).toString() else "never").append('\n')
        listOf("Claude" to snap.claude, "Codex" to snap.codex).forEach { (name, st) ->
            sb.append(name).append(": configured=").append(st.configured)
            st.plan?.let { sb.append(" plan=").append(it) }
            st.error?.let { sb.append(" error=").append(it) }
            sb.append('\n')
            st.windows.forEach { w ->
                sb.append("  ").append(w.label).append(' ').append(w.pct).append("% resets ")
                    .append(if (w.resetsAt > 0) Date(w.resetsAt).toString() else "?").append('\n')
            }
        }
        sb.append("history points: ").append(UsageRepo.history(this).size).append('\n')
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("Auspex diagnostics", sb.toString()))
        toast("Diagnostics copied — no tokens included")
    }

    private var codexExchangeInFlight = false

    override fun onResume() {
        super.onResume()
        updateStatus()
        // finish a Codex login whose code was caught while we were behind the browser
        if (!codexExchangeInFlight && Prefs.codexPendingCode(this) != null) {
            codexExchangeInFlight = true
            scope.launch {
                try {
                    withContext(Dispatchers.IO) { CodexApi.completePendingLogin(this@MainActivity) }
                    toast("Codex signed in ✓")
                    afterSetupChanged()
                } catch (e: Exception) {
                    showError("Codex sign-in failed", e)
                    updateStatus()
                } finally {
                    codexExchangeInFlight = false
                }
            }
            return
        }
        // if a login just completed while we were in the browser, show fresh numbers
        if (Prefs.claudeTokens(this).first != null || Prefs.codexTokens(this) != null) {
            val snap = UsageRepo.load(this)
            if (snap.fetchedAt == 0L) refreshNowUi()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        codexServer?.stop()
        scope.cancel()
    }

    private fun openUrl(url: String) {
        try {
            CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(this, Uri.parse(url))
        } catch (_: Exception) {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }
    }

    private fun afterSetupChanged() {
        updateStatus()
        RefreshWorker.schedulePeriodic(this)
        refreshNowUi()
    }

    private fun refreshNowUi() {
        val btn = findViewById<Button>(R.id.btnRefresh)
        btn.isEnabled = false
        btn.text = "Refreshing…"
        scope.launch {
            try {
                withContext(Dispatchers.IO) { UsageRepo.fetchAll(this@MainActivity) }
                WidgetRenderer.updateAll(this@MainActivity)
            } catch (e: Exception) {
                showError("Refresh failed", e)
            } finally {
                btn.isEnabled = true
                btn.text = "Refresh usage now"
                updateStatus()
            }
        }
    }

    private fun updateStatus() {
        val df = SimpleDateFormat("MMM d HH:mm", Locale.getDefault())
        val snap = UsageRepo.load(this)

        val (clAccess, _, _) = Prefs.claudeTokens(this)
        findViewById<TextView>(R.id.claudeStatus).text = when {
            clAccess == null -> "Not signed in"
            snap.claude.error != null -> "Signed in ✓ — ⚠ ${snap.claude.error}"
            else -> "Signed in ✓"
        }
        renderRows(findViewById(R.id.claudeRows), snap.claude, ContextCompat.getColor(this, R.color.claude))

        val cx = Prefs.codexTokens(this)
        findViewById<TextView>(R.id.codexStatus).text = when {
            cx == null -> "Not signed in"
            snap.codex.error != null -> "Signed in ✓ acct …${cx.accountId?.takeLast(4) ?: "?"} — ⚠ ${snap.codex.error}"
            else -> "Signed in ✓ acct …${cx.accountId?.takeLast(4) ?: "?"}" +
                (snap.codex.plan?.let { "  [$it]" } ?: "")
        }
        renderRows(findViewById(R.id.codexRows), snap.codex, ContextCompat.getColor(this, R.color.codex))

        findViewById<TextView>(R.id.updated).text =
            if (snap.fetchedAt > 0) "Updated ${df.format(Date(snap.fetchedAt))}" else "Never updated"

        renderPreview()
    }

    private fun renderRows(container: LinearLayout, state: ProviderState, tint: Int) {
        container.removeAllViews()
        if (!state.configured || state.windows.isEmpty()) return
        val red = ContextCompat.getColor(this, R.color.red)
        val track = ContextCompat.getColor(this, R.color.track)
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()

        state.windows.forEach { w ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(4), 0, dp(4))
            }
            row.addView(TextView(this).apply {
                text = w.label
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text))
                textSize = 13f
            }, LinearLayout.LayoutParams(dp(52), ViewGroup.LayoutParams.WRAP_CONTENT))
            row.addView(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100
                progress = w.pct
                progressTintList = ColorStateList.valueOf(if (w.pct >= 90) red else tint)
                progressBackgroundTintList = ColorStateList.valueOf(track)
            }, LinearLayout.LayoutParams(0, dp(8), 1f))
            row.addView(TextView(this).apply {
                text = "${w.pct}%"
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text))
                textSize = 13f
                gravity = Gravity.END
            }, LinearLayout.LayoutParams(dp(48), ViewGroup.LayoutParams.WRAP_CONTENT))
            row.addView(TextView(this).apply {
                text = fmtReset(w.resetsAt)
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text2))
                textSize = 12f
                gravity = Gravity.END
            }, LinearLayout.LayoutParams(dp(76), ViewGroup.LayoutParams.WRAP_CONTENT))
            container.addView(row, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun fmtReset(ms: Long): String {
        if (ms <= 0) return ""
        val diff = ms - System.currentTimeMillis()
        if (diff <= 0) return "resets now"
        val m = diff / 60000
        return when {
            m < 60 -> "in ${m}m"
            m < 24 * 60 -> "in ${m / 60}h ${m % 60}m"
            else -> "in ${m / (24 * 60)}d ${m % (24 * 60) / 60}h"
        }
    }

    private fun promptText(title: String, hint: String, onOk: (String) -> Unit) {
        val edit = EditText(this).apply {
            this.hint = hint
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 1
            maxLines = 8
        }
        val wrap = FrameLayout(this).apply {
            setPadding(48, 24, 48, 0)
            addView(edit, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(wrap)
            .setPositiveButton("OK") { _, _ -> onOk(edit.text.toString()) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showError(title: String, e: Exception) {
        if (isFinishing || isDestroyed) return
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(e.message ?: e.toString())
            .setPositiveButton("OK", null)
            .show()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
