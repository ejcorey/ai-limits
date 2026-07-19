package dev.yuhee.ailimits

import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
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
            val flow = CodexApi.beginLogin()
            codexServer = CodexLoginServer(applicationContext, flow) { err ->
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    if (err == null) {
                        toast("Codex signed in ✓")
                        afterSetupChanged()
                    } else {
                        showError("Codex sign-in failed", RuntimeException(err))
                        updateStatus()
                    }
                }
            }.also { it.start() }
            openUrl(flow.url)
            toast("Log in with your ChatGPT account, then return here")
        }

        findViewById<Button>(R.id.btnCodexSignOut).setOnClickListener {
            Prefs.clearCodex(this); updateStatus(); WidgetRenderer.updateAll(this)
        }

        findViewById<Button>(R.id.btnRefresh).setOnClickListener { refreshNowUi() }
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
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
        if (diff <= 0) return "→ now"
        return if (diff < 24 * 3600 * 1000L) {
            "→ " + SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))
        } else {
            "→ " + SimpleDateFormat("EEE HH:mm", Locale.getDefault()).format(Date(ms))
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
