package dev.yuhee.ailimits

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Build
import android.view.View
import android.widget.RemoteViews
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object WidgetRenderer {

    private const val RED = 0xFFE5484D.toInt()
    private const val CLAUDE = 0xFFD97757.toInt()
    private const val CODEX = 0xFF19C37D.toInt()

    private val claudeRows = listOf(
        intArrayOf(R.id.claude_row0, R.id.claude_label0, R.id.claude_bar0, R.id.claude_pct0, R.id.claude_reset0),
        intArrayOf(R.id.claude_row1, R.id.claude_label1, R.id.claude_bar1, R.id.claude_pct1, R.id.claude_reset1),
        intArrayOf(R.id.claude_row2, R.id.claude_label2, R.id.claude_bar2, R.id.claude_pct2, R.id.claude_reset2),
    )
    private val codexRows = listOf(
        intArrayOf(R.id.codex_row0, R.id.codex_label0, R.id.codex_bar0, R.id.codex_pct0, R.id.codex_reset0),
        intArrayOf(R.id.codex_row1, R.id.codex_label1, R.id.codex_bar1, R.id.codex_pct1, R.id.codex_reset1),
    )

    fun updateAll(ctx: Context, refreshing: Boolean = false) {
        val mgr = AppWidgetManager.getInstance(ctx)
        val ids = mgr.getAppWidgetIds(ComponentName(ctx, UsageWidgetProvider::class.java))
        if (ids.isEmpty()) return
        val rv = build(ctx, refreshing)
        ids.forEach { mgr.updateAppWidget(it, rv) }
    }

    private fun build(ctx: Context, refreshing: Boolean): RemoteViews {
        val rv = RemoteViews(ctx.packageName, R.layout.widget_usage)
        val snap = UsageRepo.load(ctx)

        bindProvider(rv, snap.claude, claudeRows, R.id.claude_status, CLAUDE, "Sign in via app")
        bindProvider(rv, snap.codex, codexRows, R.id.codex_status, CODEX, "Sign in via app")

        // footer
        val anyError = snap.claude.error != null || snap.codex.error != null
        val time = if (snap.fetchedAt > 0) SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(snap.fetchedAt)) else "--:--"
        rv.setTextViewText(
            R.id.footer_time,
            when {
                refreshing -> "updating…"
                anyError -> "$time ⚠"
                else -> "$time ↻"
            }
        )

        // tap: refresh if anything is configured, otherwise open the app
        val pi = if (snap.claude.configured || snap.codex.configured) {
            val i = Intent(ctx, UsageWidgetProvider::class.java).setAction(UsageWidgetProvider.ACTION_MANUAL_REFRESH)
            PendingIntent.getBroadcast(ctx, 1, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        } else {
            PendingIntent.getActivity(
                ctx, 2, Intent(ctx, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
        rv.setOnClickPendingIntent(R.id.widget_root, pi)

        // gear always opens the app
        rv.setOnClickPendingIntent(
            R.id.footer_gear,
            PendingIntent.getActivity(
                ctx, 3, Intent(ctx, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        )
        return rv
    }

    private fun bindProvider(
        rv: RemoteViews,
        state: ProviderState,
        rows: List<IntArray>,
        statusId: Int,
        color: Int,
        setupHint: String,
    ) {
        if (state.windows.isEmpty()) {
            rows.forEach { rv.setViewVisibility(it[0], View.GONE) }
            rv.setViewVisibility(statusId, View.VISIBLE)
            rv.setTextViewText(
                statusId,
                when {
                    !state.configured -> setupHint
                    state.error != null -> shortError(state.error)
                    else -> "no data yet"
                }
            )
            return
        }
        rv.setViewVisibility(statusId, View.GONE)
        rows.forEachIndexed { i, ids ->
            val win = state.windows.getOrNull(i)
            if (win == null) {
                rv.setViewVisibility(ids[0], View.GONE)
            } else {
                rv.setViewVisibility(ids[0], View.VISIBLE)
                rv.setTextViewText(ids[1], win.label)
                rv.setProgressBar(ids[2], 100, win.pct, false)
                rv.setTextViewText(ids[3], "${win.pct}%")
                rv.setTextViewText(ids[4], fmtReset(win.resetsAt))
                if (Build.VERSION.SDK_INT >= 31) {
                    val c = if (win.pct >= 90) RED else color
                    rv.setColorStateList(ids[2], "setProgressTintList", ColorStateList.valueOf(c))
                }
            }
        }
    }

    private fun shortError(e: String): String = when {
        e.contains("sign in", ignoreCase = true) || e.contains("expired", ignoreCase = true) -> "re-auth in app"
        e.contains("429") -> "rate limited"
        else -> "update failed"
    }

    private fun fmtReset(ms: Long): String {
        if (ms <= 0) return ""
        val diff = ms - System.currentTimeMillis()
        if (diff <= 0) return "→ now"
        return if (diff < 24 * 3600 * 1000L) {
            "→ " + SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))
        } else {
            "→ " + SimpleDateFormat("EEE", Locale.getDefault()).format(Date(ms))
        }
    }
}
