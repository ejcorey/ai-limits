package dev.yuhee.ailimits

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.os.Build
import android.view.View
import android.widget.RemoteViews
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object WidgetRenderer {

    private val claudeRows = listOf(
        intArrayOf(R.id.claude_row0, R.id.claude_label0, R.id.claude_bar0, R.id.claude_pct0, R.id.claude_reset0),
        intArrayOf(R.id.claude_row1, R.id.claude_label1, R.id.claude_bar1, R.id.claude_pct1, R.id.claude_reset1),
        intArrayOf(R.id.claude_row2, R.id.claude_label2, R.id.claude_bar2, R.id.claude_pct2, R.id.claude_reset2),
    )
    private val codexRows = listOf(
        intArrayOf(R.id.codex_row0, R.id.codex_label0, R.id.codex_bar0, R.id.codex_pct0, R.id.codex_reset0),
        intArrayOf(R.id.codex_row1, R.id.codex_label1, R.id.codex_bar1, R.id.codex_pct1, R.id.codex_reset1),
    )

    private val providers = listOf(
        UsageWidgetProvider::class.java,
        BarsWidgetProvider::class.java,
        PercentWidgetProvider::class.java,
        GraphWidgetProvider::class.java,
    )

    private fun ids(ctx: Context, cls: Class<*>): IntArray =
        AppWidgetManager.getInstance(ctx).getAppWidgetIds(ComponentName(ctx, cls))

    fun anyWidgets(ctx: Context): Boolean = providers.any { ids(ctx, it).isNotEmpty() }

    fun updateAll(ctx: Context, refreshing: Boolean = false) {
        val mgr = AppWidgetManager.getInstance(ctx)
        val snap = UsageRepo.load(ctx)
        fun push(cls: Class<*>, build: () -> RemoteViews) {
            val widgetIds = ids(ctx, cls)
            if (widgetIds.isEmpty()) return
            val rv = build()
            widgetIds.forEach { mgr.updateAppWidget(it, rv) }
        }
        push(UsageWidgetProvider::class.java) { buildFull(ctx, snap, refreshing) }
        push(BarsWidgetProvider::class.java) { buildBars(ctx, snap) }
        push(PercentWidgetProvider::class.java) { buildPercent(ctx, snap) }
        push(GraphWidgetProvider::class.java) { buildGraph(ctx, snap, refreshing) }
    }

    // --- pending intents ---

    private fun refreshPI(ctx: Context): PendingIntent {
        val i = Intent(ctx, UsageWidgetProvider::class.java).setAction(UsageWidgetProvider.ACTION_MANUAL_REFRESH)
        return PendingIntent.getBroadcast(ctx, 1, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun openAppPI(ctx: Context, rc: Int): PendingIntent =
        PendingIntent.getActivity(
            ctx, rc, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun tapPI(ctx: Context, snap: Snapshot): PendingIntent =
        if (snap.claude.configured || snap.codex.configured) refreshPI(ctx) else openAppPI(ctx, 2)

    // --- detail widget ---

    private fun buildFull(ctx: Context, snap: Snapshot, refreshing: Boolean): RemoteViews {
        val rv = RemoteViews(ctx.packageName, R.layout.widget_usage)

        bindProvider(ctx, rv, snap.claude, claudeRows, R.id.claude_status, ctx.getColor(R.color.claude))
        bindProvider(ctx, rv, snap.codex, codexRows, R.id.codex_status, ctx.getColor(R.color.codex))

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

        rv.setOnClickPendingIntent(R.id.widget_root, tapPI(ctx, snap))
        rv.setOnClickPendingIntent(R.id.footer_gear, openAppPI(ctx, 3))
        return rv
    }

    private fun bindProvider(
        ctx: Context,
        rv: RemoteViews,
        state: ProviderState,
        rows: List<IntArray>,
        statusId: Int,
        color: Int,
    ) {
        if (state.windows.isEmpty()) {
            rows.forEach { rv.setViewVisibility(it[0], View.GONE) }
            rv.setViewVisibility(statusId, View.VISIBLE)
            rv.setTextViewText(
                statusId,
                when {
                    !state.configured -> "Sign in via app"
                    state.error != null -> shortError(state.error)
                    else -> "no data yet"
                }
            )
            return
        }
        rv.setViewVisibility(statusId, View.GONE)
        rows.forEachIndexed { i, rowIds ->
            val win = state.windows.getOrNull(i)
            if (win == null) {
                rv.setViewVisibility(rowIds[0], View.GONE)
            } else {
                rv.setViewVisibility(rowIds[0], View.VISIBLE)
                rv.setTextViewText(rowIds[1], win.label)
                rv.setProgressBar(rowIds[2], 100, win.pct, false)
                rv.setTextViewText(rowIds[3], "${win.pct}%")
                rv.setTextViewText(rowIds[4], fmtReset(win.resetsAt))
                if (Build.VERSION.SDK_INT >= 31) {
                    val c = if (win.pct >= 90) ctx.getColor(R.color.red) else color
                    rv.setColorStateList(rowIds[2], "setProgressTintList", ColorStateList.valueOf(c))
                }
            }
        }
    }

    // --- slim bars widget ---

    private fun buildBars(ctx: Context, snap: Snapshot): RemoteViews {
        val rv = RemoteViews(ctx.packageName, R.layout.widget_bars)
        fun bind(state: ProviderState, barId: Int, pctId: Int, color: Int) {
            val w = binding(state)
            rv.setProgressBar(barId, 100, w?.pct ?: 0, false)
            rv.setTextViewText(pctId, if (w != null) "${w.pct}%" else "--")
            if (Build.VERSION.SDK_INT >= 31 && w != null) {
                val c = if (w.pct >= 90) ctx.getColor(R.color.red) else color
                rv.setColorStateList(barId, "setProgressTintList", ColorStateList.valueOf(c))
            }
        }
        bind(snap.claude, R.id.bars_bar_c, R.id.bars_pct_c, ctx.getColor(R.color.claude))
        bind(snap.codex, R.id.bars_bar_x, R.id.bars_pct_x, ctx.getColor(R.color.codex))
        rv.setOnClickPendingIntent(R.id.widget_root, tapPI(ctx, snap))
        return rv
    }

    // --- percent widget ---

    private fun buildPercent(ctx: Context, snap: Snapshot): RemoteViews {
        val rv = RemoteViews(ctx.packageName, R.layout.widget_percent)
        fun bind(state: ProviderState, pctId: Int, subId: Int, name: String, color: Int) {
            val w = binding(state)
            if (w == null) {
                rv.setTextViewText(pctId, "--")
                rv.setTextViewText(subId, name)
            } else {
                rv.setTextViewText(pctId, "${w.pct}%")
                rv.setTextViewText(subId, "$name · ${w.label} ${fmtReset(w.resetsAt)}")
                rv.setTextColor(pctId, if (w.pct >= 90) ctx.getColor(R.color.red) else color)
            }
        }
        bind(snap.claude, R.id.pc_cl, R.id.pc_cl_sub, "Claude", ctx.getColor(R.color.claude))
        bind(snap.codex, R.id.pc_cx, R.id.pc_cx_sub, "Codex", ctx.getColor(R.color.codex))
        rv.setOnClickPendingIntent(R.id.widget_root, tapPI(ctx, snap))
        return rv
    }

    /** The binding constraint: the fullest window is what actually limits you. */
    private fun binding(state: ProviderState): Win? = state.windows.maxByOrNull { it.pct }

    // --- 24h graph widget ---

    private fun buildGraph(ctx: Context, snap: Snapshot, refreshing: Boolean): RemoteViews {
        val rv = RemoteViews(ctx.packageName, R.layout.widget_graph)
        rv.setImageViewBitmap(R.id.graph_img, drawGraph(ctx))
        val time = if (snap.fetchedAt > 0) SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(snap.fetchedAt)) else "--:--"
        rv.setTextViewText(R.id.graph_time, if (refreshing) "updating…" else "$time ↻")
        rv.setOnClickPendingIntent(R.id.widget_root, tapPI(ctx, snap))
        return rv
    }

    private fun drawGraph(ctx: Context): Bitmap {
        val w = 800
        val h = 320
        val pad = 14f
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val now = System.currentTimeMillis()
        val spanMs = 24 * 3600 * 1000L
        val hist = UsageRepo.history(ctx).filter { it.first >= now - spanMs }

        val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ctx.getColor(R.color.track)
            strokeWidth = 2f
        }
        for (v in intArrayOf(0, 50, 100)) {
            val y = pad + (100 - v) / 100f * (h - 2 * pad)
            canvas.drawLine(pad, y, w - pad, y, grid)
        }

        fun x(t: Long) = pad + (t - (now - spanMs)).toFloat() / spanMs * (w - 2 * pad)
        fun y(pct: Int) = pad + (100 - pct.coerceIn(0, 100)) / 100f * (h - 2 * pad)

        var drewAny = false
        fun series(pick: (Triple<Long, Int, Int>) -> Int, color: Int) {
            val pts = hist.mapNotNull { e -> pick(e).takeIf { it >= 0 }?.let { Pair(e.first, it) } }
            if (pts.size < 2) return
            drewAny = true
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color
                strokeWidth = 5f
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }
            val path = Path()
            pts.forEachIndexed { i, (t, p) ->
                if (i == 0) path.moveTo(x(t), y(p)) else path.lineTo(x(t), y(p))
            }
            canvas.drawPath(path, paint)
            val last = pts.last()
            canvas.drawCircle(x(last.first), y(last.second), 7f, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color })
        }
        series({ it.second }, ctx.getColor(R.color.claude))
        series({ it.third }, ctx.getColor(R.color.codex))

        if (!drewAny) {
            val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = ctx.getColor(R.color.text2)
                textSize = 30f
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText("collecting history…", w / 2f, h / 2f + 10f, txt)
        }
        return bmp
    }

    private fun shortError(e: String): String = when {
        e.contains("sign in", ignoreCase = true) || e.contains("expired", ignoreCase = true) -> "re-auth in app"
        e.contains("429") -> "rate limited"
        else -> "update failed"
    }

    /** Relative time until reset: "↻3h40m", "↻2d5h", "↻25m". */
    fun fmtReset(ms: Long): String {
        if (ms <= 0) return ""
        val diff = ms - System.currentTimeMillis()
        if (diff <= 0) return "↻now"
        val m = diff / 60000
        return when {
            m < 60 -> "↻${m}m"
            m < 24 * 60 -> "↻${m / 60}h${m % 60}m"
            else -> "↻${m / (24 * 60)}d${m % (24 * 60) / 60}h"
        }
    }
}
