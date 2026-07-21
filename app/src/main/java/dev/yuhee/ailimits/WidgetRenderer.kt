package dev.yuhee.ailimits

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.View
import android.widget.RemoteViews
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private fun withAlpha(color: Int, alpha: Int): Int =
    (color and 0x00FFFFFF) or ((alpha.coerceIn(0, 255)) shl 24)

private fun blend(a: Int, b: Int, k: Float): Int {
    val f = k.coerceIn(0f, 1f)
    return Color.argb(
        255,
        (Color.red(a) + (Color.red(b) - Color.red(a)) * f).roundToInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * f).roundToInt(),
        (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * f).roundToInt(),
    )
}

/**
 * Every widget style is drawn onto a bitmap rather than assembled from RemoteViews
 * rows. RemoteViews cannot host custom views, so a Canvas is the only way to get
 * real typography, rounded gradient bars, rings and sparklines — and it lets each
 * widget instance lay itself out against the size the launcher actually gave it.
 */
object WidgetRenderer {

    /** The user's display choices, resolved once per update so tests can vary them freely. */
    data class Opts(
        val showClaude: Boolean = true,
        val showCodex: Boolean = true,
        val opacity: Int = 100,
        val projection: Boolean = true,
        val sparkline: Boolean = true,
    ) {
        val solo: Boolean get() = showClaude != showCodex
    }

    fun optsFrom(ctx: Context) = Opts(
        showClaude = Settings.showClaude(ctx),
        showCodex = Settings.showCodex(ctx),
        opacity = Settings.opacity(ctx),
        projection = Settings.showProjection(ctx),
        sparkline = Settings.showSparkline(ctx),
    )

    // -- tiers -------------------------------------------------------------
    // Declared heights so the layout can guarantee a fit before it draws.
    private const val H_FULL = 55f
    private const val H_MED = 38f
    private const val SPARK = 30f
    private const val H_RICH = H_FULL + SPARK
    private const val MIN_GAP = 10f
    private const val FOOT = 15f

    private const val RICH = 3
    private const val FULL = 2
    private const val MEDIUM = 1
    private const val COMPACT = 0

    private fun blockH(tier: Int) = when (tier) {
        RICH -> H_RICH
        FULL -> H_FULL
        else -> H_MED
    }

    private fun padFor(tier: Int) = if (tier == MEDIUM) 11f else 13f

    /** Height a tier needs for [panels] providers, padding and gap included. */
    internal fun neededHeight(tier: Int, panels: Int) =
        padFor(tier) * 2 + blockH(tier) * panels + MIN_GAP

    /** The richest layout that genuinely fits, or [COMPACT] when none does. */
    internal fun tierFor(h: Float, panels: Int): Int {
        for (candidate in intArrayOf(RICH, FULL, MEDIUM)) {
            if (neededHeight(candidate, panels) <= h) return candidate
        }
        return COMPACT
    }

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
        val hist = UsageRepo.history(ctx)
        // Colours are resolved against the chosen theme, not necessarily the system one.
        val theme = Theme(Settings.themedContext(ctx))
        val opts = optsFrom(ctx)
        // Sizes differ per instance, so each widget is rendered on its own.
        providers.forEach { cls ->
            ids(ctx, cls).forEach { id ->
                runCatching {
                    mgr.updateAppWidget(id, build(ctx, mgr, id, cls, snap, hist, theme, opts, refreshing))
                }
            }
        }
    }

    private fun build(
        ctx: Context,
        mgr: AppWidgetManager,
        id: Int,
        cls: Class<*>,
        snap: Snapshot,
        hist: List<Triple<Long, Int, Int>>,
        t: Theme,
        o: Opts,
        refreshing: Boolean,
    ): RemoteViews {
        val box = mgr.getAppWidgetOptions(id)
        val wDp = box.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0)
            .takeIf { it > 0 }?.toFloat() ?: 250f
        val hDp = box.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0)
            .takeIf { it > 0 }?.toFloat() ?: 120f

        val style = when (cls) {
            BarsWidgetProvider::class.java -> Style.BARS
            PercentWidgetProvider::class.java -> Style.RINGS
            GraphWidgetProvider::class.java -> Style.GRAPH
            else -> Style.DETAIL
        }
        val (bmp, footer) = render(ctx, style, wDp, hDp, snap, hist, refreshing, t, o)

        val rv = RemoteViews(ctx.packageName, R.layout.widget_canvas)
        rv.setImageViewBitmap(R.id.widget_canvas, bmp)
        rv.setOnClickPendingIntent(R.id.widget_root, tapPI(ctx, snap))
        // The "Open app" hit region only exists while the footer is actually drawn.
        rv.setViewVisibility(R.id.widget_settings, if (footer) View.VISIBLE else View.GONE)
        if (footer) rv.setOnClickPendingIntent(R.id.widget_settings, openAppPI(ctx, 3))
        return rv
    }

    enum class Style { DETAIL, BARS, RINGS, GRAPH }

    /**
     * Draws one widget at a given dp size. Separate from [build] so tests can render
     * the real thing to a bitmap without an AppWidgetManager.
     *
     * @return the bitmap and whether the footer (and its tap region) was drawn.
     */
    internal fun render(
        ctx: Context,
        style: Style,
        wDp: Float,
        hDp: Float,
        snap: Snapshot,
        hist: List<Triple<Long, Int, Int>>,
        refreshing: Boolean = false,
        theme: Theme? = null,
        o: Opts = Opts(),
    ): Pair<Bitmap, Boolean> {
        val t = theme ?: Theme(Settings.themedContext(ctx))
        val scale = scaleFor(ctx, wDp, hDp)
        val bmp = Bitmap.createBitmap(
            max(1, (wDp * scale).roundToInt()),
            max(1, (hDp * scale).roundToInt()),
            Bitmap.Config.ARGB_8888,
        )
        val canvas = Canvas(bmp)
        canvas.scale(scale, scale)
        val pen = Pen(canvas, t)

        var footer = false
        when (style) {
            Style.BARS -> drawBars(pen, wDp, hDp, snap, t, o)
            Style.RINGS -> drawRings(pen, wDp, hDp, snap, t, o)
            Style.GRAPH -> drawGraph(pen, wDp, hDp, snap, hist, t, o)
            Style.DETAIL -> footer = drawDetail(pen, wDp, hDp, snap, hist, t, o, refreshing)
        }
        return bmp to footer
    }

    /** The providers the user wants, paired with their history series. */
    private fun visible(
        snap: Snapshot, hist: List<Triple<Long, Int, Int>>, t: Theme, o: Opts,
    ): List<Panel> = buildList {
        if (o.showClaude) add(Panel(snap.claude, "Claude", t.claude, hist.map { it.first to it.second }))
        if (o.showCodex) add(Panel(snap.codex, "Codex", t.codex, hist.map { it.first to it.third }))
    }

    private class Panel(
        val state: ProviderState,
        val name: String,
        val color: Int,
        val series: List<Pair<Long, Int>>,
    )

    /** Keeps the bitmap crisp but well under the memory a RemoteViews update may carry. */
    private fun scaleFor(ctx: Context, wDp: Float, hDp: Float): Float {
        var s = ctx.resources.displayMetrics.density.coerceIn(1.5f, 3f)
        while (wDp * s * hDp * s > 520_000f && s > 1.25f) s -= 0.25f
        return s
    }

    // --- pending intents --------------------------------------------------

    private fun refreshPI(ctx: Context): PendingIntent {
        val i = Intent(ctx, UsageWidgetProvider::class.java)
            .setAction(UsageWidgetProvider.ACTION_MANUAL_REFRESH)
        return PendingIntent.getBroadcast(
            ctx, 1, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun openAppPI(ctx: Context, rc: Int): PendingIntent =
        PendingIntent.getActivity(
            ctx, rc, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun tapPI(ctx: Context, snap: Snapshot): PendingIntent =
        if (snap.claude.configured || snap.codex.configured) refreshPI(ctx) else openAppPI(ctx, 2)

    // --- detail -----------------------------------------------------------

    /** @return true when the footer (and therefore its tap region) was drawn. */
    private fun drawDetail(
        g: Pen, w: Float, h: Float, snap: Snapshot,
        hist: List<Triple<Long, Int, Int>>, t: Theme, o: Opts, refreshing: Boolean,
    ): Boolean {
        g.card(w, h, o.opacity)
        val panels = visible(snap, hist, t, o)
        if (panels.size == 1) return drawSolo(g, w, h, panels[0], t, o, snap, refreshing)

        val n = panels.size
        val tier = tierFor(h, n)
        if (tier == COMPACT) { drawCompact(g, w, h, panels, t); return false }

        val pad = padFor(tier)
        val x = pad
        val cw = w - pad * 2
        val bh = blockH(tier)
        val slack = h - pad * 2 - bh * n
        val foot = slack >= MIN_GAP + FOOT

        // Spend leftover height on the chart rather than leaving a dead zone:
        // a widget dragged tall should show more, not the same thing with a gap.
        var free = slack - if (foot) FOOT else 0f
        val gap = min(24f, max(MIN_GAP, free / max(1, n)))
        free -= gap * (n - 1)
        val stretch =
            if (tier == RICH && o.sparkline) min(90f, max(0f, free / n - 4f)) else 0f
        val bhEff = bh + stretch
        val used = bhEff * n + gap * (n - 1)
        val y = pad + if (foot) 0f else max(0f, (h - pad * 2 - used) / 2f)

        panels.forEachIndexed { i, p ->
            val by = y + i * (bhEff + gap)
            if (i > 0) g.line(x, by - gap / 2f, cw, t.rule)
            drawBlock(g, x, by, cw, p, t, o, tier, stretch)
        }

        if (foot) footer(g, x, cw, h - pad - 1f, t, snap, refreshing)
        else staleDot(g, w, t, snap)
        return foot
    }

    private fun footer(
        g: Pen, x: Float, cw: Float, fy: Float, t: Theme, snap: Snapshot, refreshing: Boolean,
    ) {
        g.refreshIcon(x + 4f, fy - 4.5f, 4f, t.faint)
        val stamp = if (refreshing) "updating…"
        else if (snap.fetchedAt > 0) "updated " + clock(snap.fetchedAt) else "not updated yet"
        g.text(stamp, x + 12f, fy - 1f, 9f, 500, t.faint)
        g.text("Open app", x + cw, fy - 1f, 9f, 500, t.faint, Paint.Align.RIGHT)
    }

    private fun staleDot(g: Pen, w: Float, t: Theme, snap: Snapshot) {
        // No room to spell out the timestamp — flag it only when it actually matters.
        if (snap.fetchedAt > 0 && System.currentTimeMillis() - snap.fetchedAt > 60 * 60_000L) {
            g.circle(w - 11f, 11f, 2.6f, t.warn)
        }
    }

    /**
     * One provider on its own. With the whole widget to itself there is room to
     * give every window a full row instead of squeezing them into chips.
     */
    private fun drawSolo(
        g: Pen, w: Float, h: Float, p: Panel, t: Theme, o: Opts,
        snap: Snapshot, refreshing: Boolean,
    ): Boolean {
        val pad = 14f
        val x = pad
        val cw = w - pad * 2
        val b = binding(p.state)

        g.circle(x + 4f, pad + 6f, 4f, if (p.state.configured) p.color else t.faint)
        var cx = x + 13f
        cx += g.text(p.name, cx, pad + 10.5f, 13f, 700, if (p.state.configured) p.color else t.dim, tracking = .035f) + 7f
        val plan = if (p.state.configured) prettyPlan(p.state.plan) else null
        if (plan != null) cx += g.chip(plan, cx, pad - 1f, p.color) + 7f

        if (b == null) {
            val msg = when {
                !p.state.configured -> "Tap to sign in"
                p.state.error != null -> shortError(p.state.error)
                else -> "No data yet"
            }
            g.text(msg, x, pad + 38f, 13f, 500, if (p.state.error != null) t.warn else t.dim)
            return false
        }

        val sc = t.status(b.pct, p.color)
        val proj = if (o.projection) projection(b, p.series) else null
        val head = if (proj != null) "on pace to cap " + clock(proj)
        else "resets " + clock(b.resetsAt) + " · " + left(b.resetsAt) + " left"
        if (g.measure(head, 10f, 500) <= x + cw - cx) {
            g.text(head, x + cw, pad + 10.5f, 10f, if (proj != null) 600 else 500,
                if (proj != null) t.warn else t.faint, Paint.Align.RIGHT)
        }

        // Hero, sized up now that it is not sharing the widget — but kept modest on
        // short widgets so the window rows still get a look in.
        val tight = h < 132f
        val hy = pad + if (tight) 14f else 18f
        val heroSize = if (tight) 29f else 35f
        val nw = g.text("${b.pct}", x, hy + heroSize * .77f, heroSize, 700, sc, tracking = -.017f)
        g.text("%", x + nw + 3f, hy + heroSize * .77f, heroSize * .43f, 700, sc)
        val bx = x + nw + if (tight) 22f else 26f
        g.bar(bx, hy + heroSize * .31f, x + cw - bx, if (tight) 10f else 12f, b.pct, sc)
        g.text(windowName(b.label), x, hy + heroSize * .77f + 15f, 10f, 500, t.dim, tracking = .02f)

        // Every other window gets a real row.
        val rest = p.state.windows.filter { it !== b }
        var ry = hy + heroSize * .77f + if (tight) 22f else 25f
        rest.forEach { v ->
            if (ry + 13f > h - pad) return@forEach
            g.text(v.label, x, ry + 8f, 9.5f, 600, t.dim, tracking = .02f)
            val lx = x + 46f
            val rw = max(20f, cw - 46f - 92f)
            g.bar(lx, ry + 3f, rw, 6f, v.pct, t.status(v.pct, p.color))
            g.text("${v.pct}%", lx + rw + 34f, ry + 8f, 10f, 700, t.text, Paint.Align.RIGHT)
            g.text(left(v.resetsAt), x + cw, ry + 8f, 9f, 500, t.faint, Paint.Align.RIGHT)
            ry += 15f
        }

        // Whatever the rows left over is spent in order: footer first (it is the
        // cheapest), then the sparkline gets the rest, and neither is drawn unless
        // it genuinely fits. Reserving space up front is what caused overlaps.
        val remaining = h - pad - ry
        val foot = remaining >= 20f
        val forSpark = remaining - if (foot) 16f else 0f
        if (o.sparkline && forSpark >= 24f) {
            sparkline(g, x, ry + 3f, cw, forSpark - 7f, p.series, p.color, t)
        }
        if (foot) footer(g, x, cw, h - pad - 1f, t, snap, refreshing) else staleDot(g, w, t, snap)
        return foot
    }

    private fun drawBlock(
        g: Pen, x: Float, y: Float, w: Float, p: Panel, t: Theme, o: Opts, tier: Int,
        stretch: Float = 0f,
    ) {
        val st = p.state
        val name = p.name
        val color = p.color
        val series = p.series
        val med = tier == MEDIUM
        val b = binding(st)

        g.circle(x + 3.5f, y + 5.5f, 3.5f, if (st.configured) color else t.faint)
        var cx = x + 12f
        cx += g.text(name, cx, y + 9.5f, 11.5f, 700, if (st.configured) color else t.dim, tracking = .035f) + 6f
        val plan = if (st.configured) prettyPlan(st.plan) else null
        if (plan != null) cx += g.chip(plan, cx, y - 1.5f, color) + 6f

        if (b == null) {
            val msg = when {
                !st.configured -> "Tap to sign in"
                st.error != null -> shortError(st.error)
                else -> "No data yet"
            }
            g.text(msg, x, y + if (med) 30f else 34f, if (med) 11f else 12f, 500,
                if (st.error != null) t.warn else t.dim)
            return
        }

        val sc = t.status(b.pct, color)
        val proj = if (o.projection) projection(b, series) else null
        val head = if (proj != null) "on pace to cap " + clock(proj)
        else "resets " + clock(b.resetsAt) + " · " + left(b.resetsAt) + " left"
        val headWeight = if (proj != null) 600 else 500
        val headColor = if (proj != null) t.warn else t.faint
        // Medium carries the reset on its own meta line, so only the taller tiers
        // put it in the header — otherwise it reads twice.
        val wantHead = !med || proj != null
        val headW = g.measure(head, 9.5f, headWeight)
        val headInHeader = wantHead && headW <= x + w - cx
        if (headInHeader) {
            g.text(head, x + w, y + 9.5f, 9.5f, headWeight, headColor, Paint.Align.RIGHT)
        }

        if (med) {
            val pw = g.text("${b.pct}%", x + w, y + 24f, 13.5f, 700, sc, Paint.Align.RIGHT)
            g.bar(x, y + 15f, w - pw - 10f, 9f, b.pct, sc)
            g.text(
                shortWindow(b.label) + " · resets " + clock(b.resetsAt) + " · " + left(b.resetsAt) + " left",
                x, y + 35f, 9f, 500, t.faint
            )
            return
        }

        // Hero: big number with the bar alongside it — stacking it wasted the width.
        val nw = g.text("${b.pct}", x, y + 37f, 29f, 700, sc, tracking = -.017f)
        g.text("%", x + nw + 2f, y + 37f, 12.5f, 700, sc)
        val bx = x + nw + 21f
        g.bar(bx, y + 23f, x + w - bx, 10f, b.pct, sc)

        val my = y + 43f
        val lw = g.text(windowName(b.label), x, my + 8f, 9.5f, 500, t.dim, tracking = .02f)
        val room = w - lw - 14f
        if (wantHead && !headInHeader && headW <= room) {
            // A wide plan chip crowded the header out; when it comes to it, knowing
            // when the limit lifts beats listing the other windows.
            g.text(head, x + w, my + 8f, 9.5f, headWeight, headColor, Paint.Align.RIGHT)
        } else {
            drawSecondary(g, x + w, my + 8f, room, st.windows.filter { it !== b }, color, t)
        }

        if (tier == RICH && o.sparkline) {
            sparkline(g, x, y + H_FULL + 2f, w, SPARK - 6f + stretch, series, color, t)
        }
    }

    /** Right-aligned group of the non-binding windows; degrades until it fits. */
    private fun drawSecondary(
        g: Pen, rightX: Float, baseY: Float, maxW: Float, rest: List<Win>, color: Int, t: Theme,
    ) {
        if (rest.isEmpty() || maxW < 40f) return
        fun width(items: List<Win>, withBar: Boolean): Float =
            items.sumOf {
                (g.measure(it.label, 9f, 600, .02f) + (if (withBar) 22f else 0f) + 4f +
                    g.measure("${it.pct}%", 9.5f, 700)).toDouble()
            }.toFloat() + (items.size - 1) * 11f

        for (withBar in booleanArrayOf(true, false)) {
            for (n in rest.size downTo 1) {
                val items = rest.take(n)
                if (width(items, withBar) > maxW) continue
                var rx = rightX
                items.reversed().forEach { v ->
                    rx -= g.text("${v.pct}%", rx, baseY, 9.5f, 700, t.text, Paint.Align.RIGHT) + 4f
                    if (withBar) {
                        g.bar(rx - 18f, baseY - 5.5f, 18f, 4.5f, v.pct, t.status(v.pct, color))
                        rx -= 22f
                    }
                    rx -= g.text(v.label, rx, baseY, 9f, 600, t.dim, Paint.Align.RIGHT, .02f) + 11f
                }
                return
            }
        }
    }

    /** Compact: providers side by side, one glance each. */
    private fun drawCompact(g: Pen, w: Float, h: Float, panels: List<Panel>, t: Theme) {
        val pad = 13f
        val gap = 16f
        val n = max(1, panels.size)
        val colW = (w - pad * 2 - gap * (n - 1)) / n
        // Below this there is no room for a name and a number on the same line.
        val roomy = colW >= 92f
        val single = h >= 56f

        panels.forEachIndexed { i, p ->
            val x = pad + i * (colW + gap)
            val b = binding(p.state)
            val sc = if (b != null) t.status(b.pct, p.color) else t.faint
            val cy = h / 2f
            val nameColor = if (p.state.configured) p.color else t.dim
            if (roomy) {
                g.circle(x + 3.5f, cy - 10f, 3.5f, if (p.state.configured) p.color else t.faint)
                g.text(p.name, x + 12f, cy - 6.5f, 10f, 700, nameColor, tracking = .03f)
                g.text(if (b != null) "${b.pct}%" else "--", x + colW, cy - 6.5f, 12.5f, 700, sc, Paint.Align.RIGHT)
            } else {
                // Too narrow for both: the number is what matters, keep only a colour cue.
                g.circle(x + 3.5f, cy - 9f, 3.5f, if (p.state.configured) p.color else t.faint)
                g.text(if (b != null) "${b.pct}%" else "--", x + colW, cy - 5.5f, 12f, 700, sc, Paint.Align.RIGHT)
            }
            g.bar(x, cy, colW, 8f, b?.pct ?: 0, sc)
            if (single) {
                val sub = if (b != null) {
                    val full = shortWindow(b.label) + " · " + left(b.resetsAt) + " left"
                    if (g.measure(full, 8.5f, 500) <= colW) full else left(b.resetsAt) + " left"
                } else "tap to sign in"
                g.text(sub, x, cy + 18f, 8.5f, 500, t.faint)
            }
        }
    }

    /** Trend of the binding window over the last 12h. No axes — shape only. */
    private fun sparkline(
        g: Pen, x: Float, y: Float, w: Float, h: Float,
        series: List<Pair<Long, Int>>, color: Int, t: Theme,
    ) {
        val now = System.currentTimeMillis()
        val span = 12 * 3600_000L
        val pts = series.filter { it.first >= now - span && it.second >= 0 }
        if (pts.size < 2) return
        // Scale to the data's own range so a flat-but-high series still shows shape.
        val lo = max(0f, (pts.minOf { it.second } - 4).toFloat())
        val hi = max(lo + 8f, (pts.maxOf { it.second } + 4).toFloat())
        fun px(ms: Long) = x + (ms - (now - span)).toFloat() / span * w
        fun py(v: Int) = y + h - (v.toFloat().coerceIn(lo, hi) - lo) / (hi - lo) * h

        val area = Path().apply {
            moveTo(px(pts.first().first), y + h)
            pts.forEach { lineTo(px(it.first), py(it.second)) }
            lineTo(px(pts.last().first), y + h)
            close()
        }
        val line = Path().apply {
            pts.forEachIndexed { i, p -> if (i == 0) moveTo(px(p.first), py(p.second)) else lineTo(px(p.first), py(p.second)) }
        }
        g.clipped(x, y, w, h, 4f) {
            g.path(area, LinearGradient(0f, y, 0f, y + h,
                blend(color, t.bg, .5f), blend(color, t.bg, .94f), Shader.TileMode.CLAMP))
            g.stroke(line, color, 1.7f)
        }
        val last = pts.last()
        g.circle(min(px(last.first), x + w - 2f), py(last.second), 2.4f, color)
    }

    // --- rings ------------------------------------------------------------

    private fun drawRings(g: Pen, w: Float, h: Float, snap: Snapshot, t: Theme, o: Opts) {
        g.card(w, h, o.opacity)
        val panels = visible(snap, hist = emptyList(), t = t, o = o)
        val n = max(1, panels.size)
        // Squeezed short, the caption is the first thing to go — a clipped label
        // below the dial is worse than no label at all.
        val showLabel = h >= 84f
        val showSub = h >= 100f
        val bottom = if (showSub) 26f else if (showLabel) 15f else 0f
        // One ring gets the whole card, so it can be much larger.
        val r = min((w / n - 34f) / 2f, (h - bottom - 14f) / 2f).coerceAtLeast(10f)
        panels.forEachIndexed { i, p ->
            val cx = if (n == 1) w / 2f else w * (if (i == 1) .73f else .27f)
            val cy = (h - bottom) / 2f + 2f
            val st = p.state
            val name = p.name.uppercase()
            val color = p.color
            val b = binding(st)
            val th = max(5f, r * .26f)
            g.arc(cx, cy, r, 135f, 270f, t.track, th)
            if (b != null && b.pct > 0) {
                g.arc(cx, cy, r, 135f, 270f * b.pct.coerceIn(0, 100) / 100f, t.status(b.pct, color), th)
            }
            val ns = r * .62f
            val label = if (b != null) "${b.pct}" else "--"
            val nw = g.measure(label, ns, 700, -.017f)
            val pw = if (b != null) g.measure("%", r * .26f, 700) else 0f
            val off = (nw + pw + 1.5f) / 2f
            val c = if (b != null) t.status(b.pct, color) else t.faint
            g.text(label, cx - off, cy + ns * .34f, ns, 700, c, tracking = -.017f)
            if (b != null) g.text("%", cx - off + nw + 1.5f, cy + ns * .34f, r * .26f, 700, c)
            if (showLabel) g.text(name, cx, cy + r + 11f, 9f, 700, t.dim, Paint.Align.CENTER, .05f)
            if (showSub) {
                g.text(
                    if (b != null) left(b.resetsAt) + " left" else "tap to sign in",
                    cx, cy + r + 22f, 8.5f, 500, t.faint, Paint.Align.CENTER
                )
            }
        }
    }

    // --- slim bars --------------------------------------------------------

    private fun drawBars(g: Pen, w: Float, h: Float, snap: Snapshot, t: Theme, o: Opts) {
        g.card(w, h, o.opacity)
        val pad = 14f
        val cw = w - pad * 2
        val panels = visible(snap, hist = emptyList(), t = t, o = o)
        val rowH = 23f
        val top = h / 2f - rowH * panels.size / 2f + 1f

        // Columns are budgeted from the width actually available rather than fixed
        // offsets, which used to go negative and push text past the right edge.
        val nameW = min(56f, max(0f, cw * .26f))
        val showName = nameW >= 40f
        val pctW = min(40f, cw * .18f)
        val resetW = if (cw >= 210f) min(52f, cw * .2f) else 0f
        val barX = pad + if (showName) nameW else 12f
        val barW = max(16f, pad + cw - resetW - pctW - 6f - barX)

        panels.forEachIndexed { i, p ->
            val y = top + i * rowH
            val b = binding(p.state)
            val sc = if (b != null) t.status(b.pct, p.color) else t.faint
            g.circle(pad + 3f, y + 5f, 3f, if (p.state.configured) p.color else t.faint)
            if (showName) {
                g.text(p.name, pad + 11f, y + 8.5f, 9.5f, 700,
                    if (p.state.configured) p.color else t.dim, tracking = .03f)
            }
            g.bar(barX, y + 2f, barW, 8f, b?.pct ?: 0, sc)
            g.text(if (b != null) "${b.pct}%" else "--", barX + barW + pctW, y + 8.5f, 11f, 700,
                t.text, Paint.Align.RIGHT)
            if (resetW > 0f) {
                g.text(if (b != null) left(b.resetsAt) else "—", pad + cw, y + 8.5f, 8.5f, 500,
                    t.faint, Paint.Align.RIGHT)
            }
        }
    }

    // --- 24h history ------------------------------------------------------

    private fun drawGraph(
        g: Pen, w: Float, h: Float, snap: Snapshot,
        hist: List<Triple<Long, Int, Int>>, t: Theme, o: Opts,
    ) {
        g.card(w, h, o.opacity)
        val panels = visible(snap, hist, t, o)
        // Short or narrow, the chrome is dropped in order of expendability so the
        // plot itself always keeps a usable area instead of collapsing to a sliver.
        val showHeader = h >= 92f
        val showXAxis = h >= 84f
        val showYAxis = w >= 200f
        val padL = 14f
        val padR = if (showYAxis) 32f else 12f
        val padT = if (showHeader) 27f else 12f
        val padB = if (showXAxis) 17f else 10f
        val gx = padL; val gy = padT
        val gw = max(20f, w - padL - padR)
        val gh = max(16f, h - padT - padB)
        val now = System.currentTimeMillis()
        val span = 24 * 3600_000L
        val t0 = now - span

        if (showHeader) {
            g.text("Last 24 hours", padL, 17f, 10f, 700, t.text, tracking = .03f)
            var lx = w - padR
            panels.reversed().forEach { p ->
                val b = binding(p.state)
                val c = p.color
                val s = if (b != null) "${p.name} ${b.pct}%" else p.name
                val tw = g.measure(s, 9f, 600, .02f)
                g.text(s, lx, 17f, 9f, 600, c, Paint.Align.RIGHT, .02f)
                g.circle(lx - tw - 7f, 13.5f, 2.6f, c)
                lx -= tw + 17f
            }
        }

        intArrayOf(0, 50, 100).forEach { v ->
            val y = gy + (100 - v) / 100f * gh
            g.line(gx, y, gw, t.rule)
            if (showYAxis) g.text("$v%", gx + gw + 5f, y + 3.2f, 8.5f, 500, t.faint)
        }
        if (showXAxis) {
            g.text("24h ago", gx, h - 6f, 8.5f, 500, t.faint)
            g.text("12h", gx + gw / 2f, h - 6f, 8.5f, 500, t.faint, Paint.Align.CENTER)
            g.text("now", gx + gw, h - 6f, 8.5f, 500, t.faint, Paint.Align.RIGHT)
        }

        fun px(ms: Long) = gx + (ms - t0).toFloat() / span * gw
        fun py(v: Int) = gy + (100 - v.coerceIn(0, 100)) / 100f * gh

        // Taller series first so the shorter one stays visible on top.
        val series = panels
            .map { p -> p.color to p.series.filter { it.first >= t0 && it.second >= 0 } }
            .filter { it.second.size >= 2 }
            .sortedByDescending { it.second.last().second }

        if (series.isEmpty()) {
            g.text("Collecting history…", gx + gw / 2f, gy + gh / 2f + 4f, 11f, 500, t.faint, Paint.Align.CENTER)
            return
        }
        series.forEach { (c, pts) ->
            val area = Path().apply {
                moveTo(px(pts.first().first), py(0))
                pts.forEach { lineTo(px(it.first), py(it.second)) }
                lineTo(px(pts.last().first), py(0))
                close()
            }
            val line = Path().apply {
                pts.forEachIndexed { i, p ->
                    if (i == 0) moveTo(px(p.first), py(p.second)) else lineTo(px(p.first), py(p.second))
                }
            }
            g.path(area, LinearGradient(0f, gy, 0f, gy + gh,
                blend(c, t.bg, .58f), blend(c, t.bg, .96f), Shader.TileMode.CLAMP))
            g.stroke(line, c, 2f)
            val last = pts.last()
            g.circle(px(last.first), py(last.second), 5.4f, t.bg)
            g.circle(px(last.first), py(last.second), 3.2f, c)
        }
    }

    // --- data helpers -----------------------------------------------------

    /** The binding constraint: the fullest window is what actually limits you. */
    internal fun binding(state: ProviderState): Win? = state.windows.maxByOrNull { it.pct }

    /**
     * Linear burn over recent history projected forward to 100%. Null unless the
     * window would realistically cap before it resets.
     */
    internal fun projection(b: Win, series: List<Pair<Long, Int>>): Long? {
        val now = System.currentTimeMillis()
        val pts = series.filter { it.first >= now - 110 * 60_000L && it.second >= 0 }
        if (pts.size < 3) return null
        val first = pts.first()
        val last = pts.last()
        val hours = (last.first - first.first) / 3600_000f
        if (hours <= .25f) return null
        val rate = (last.second - first.second) / hours
        if (rate <= 2.5f) return null
        val at = now + ((100 - last.second) / rate * 3600_000f).toLong()
        if (b.resetsAt > 0 && at >= b.resetsAt) return null
        return at
    }

    private fun clock(ms: Long): String =
        if (ms <= 0) "--:--" else SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))

    /** Time until reset: "25m", "3h 40m", "2d 5h". */
    fun left(ms: Long): String {
        if (ms <= 0) return "—"
        val diff = ms - System.currentTimeMillis()
        if (diff <= 0) return "now"
        val m = diff / 60_000
        return when {
            m < 60 -> "${m}m"
            m < 24 * 60 -> "${m / 60}h ${m % 60}m"
            else -> "${m / (24 * 60)}d ${m % (24 * 60) / 60}h"
        }
    }

    /** The old widget showed a bare "5h" / "7d". Say what it means. */
    fun windowName(label: String): String {
        Regex("^(\\d+)([hd])$").find(label.lowercase())?.let { m ->
            val n = m.groupValues[1]
            return if (m.groupValues[2] == "h") "$n-hour window" else "$n-day window"
        }
        return when (label.lowercase()) {
            "weekly" -> "weekly window"
            "primary" -> "5-hour window"
            "now" -> "current window"
            else -> "$label · 7-day"
        }
    }

    private fun shortWindow(label: String) = windowName(label).removeSuffix(" window")

    /** "plus" -> "Plus", "chatgpt_pro" -> "ChatGPT Pro". */
    internal fun prettyPlan(plan: String?): String? {
        val p = plan?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return p.split('_', '-', ' ').filter { it.isNotEmpty() }.joinToString(" ") { word ->
            if (word.equals("chatgpt", true)) "ChatGPT"
            else word.lowercase().replaceFirstChar { it.uppercase() }
        }
    }

    private fun shortError(e: String): String = when {
        e.contains("sign in", true) || e.contains("expired", true) -> "Sign-in expired"
        e.contains("429") -> "Rate limited"
        else -> "Update failed"
    }

    // --- theme ------------------------------------------------------------

    internal class Theme(ctx: Context) {
        val bg = ctx.getColor(R.color.widget_bg)
        val stroke = ctx.getColor(R.color.widget_stroke)
        val text = ctx.getColor(R.color.text)
        val dim = ctx.getColor(R.color.text2)
        val faint = ctx.getColor(R.color.text3)
        val track = ctx.getColor(R.color.track)
        val rule = ctx.getColor(R.color.rule)
        val claude = ctx.getColor(R.color.claude)
        val codex = ctx.getColor(R.color.codex)
        val warn = ctx.getColor(R.color.warn)
        val red = ctx.getColor(R.color.red)
        val chipBg = ctx.getColor(R.color.chip_bg)

        /** Provider identity colour until the number starts to matter, then amber, then red. */
        fun status(pct: Int, base: Int) = when {
            pct >= 90 -> red
            pct >= 75 -> warn
            else -> base
        }
    }

    // --- canvas primitives ------------------------------------------------

    private class Pen(val c: Canvas, val t: Theme) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { fontFeatureSettings = "tnum" }
        private val r = RectF()

        private fun face(weight: Int): Typeface = when {
            weight >= 700 -> BOLD
            weight >= 600 -> MEDIUM
            else -> REGULAR
        }

        fun text(
            s: String, x: Float, y: Float, size: Float, weight: Int, color: Int,
            align: Paint.Align = Paint.Align.LEFT, tracking: Float = 0f,
        ): Float {
            p.shader = null
            p.style = Paint.Style.FILL
            p.typeface = face(weight)
            p.textSize = size
            p.color = color
            p.textAlign = align
            p.letterSpacing = tracking
            c.drawText(s, x, y, p)
            return p.measureText(s)
        }

        fun measure(s: String, size: Float, weight: Int, tracking: Float = 0f): Float {
            p.typeface = face(weight)
            p.textSize = size
            p.letterSpacing = tracking
            return p.measureText(s)
        }

        fun rrect(x: Float, y: Float, w: Float, h: Float, radius: Float, color: Int, shader: Shader? = null) {
            if (w <= 0f || h <= 0f) return
            r.set(x, y, x + w, y + h)
            p.style = Paint.Style.FILL
            p.shader = shader
            p.color = color
            p.letterSpacing = 0f
            val rad = min(radius, min(w, h) / 2f)
            c.drawRoundRect(r, rad, rad, p)
            p.shader = null
        }

        fun card(w: Float, h: Float, opacityPct: Int = 100) {
            val a = (opacityPct.coerceIn(0, 100) * 255 / 100)
            rrect(0f, 0f, w, h, 22f, withAlpha(t.bg, a))
            r.set(.5f, .5f, w - .5f, h - .5f)
            p.style = Paint.Style.STROKE
            p.strokeWidth = 1f
            p.color = withAlpha(t.stroke, Color.alpha(t.stroke) * a / 255)
            p.shader = null
            c.drawRoundRect(r, 21.5f, 21.5f, p)
            p.style = Paint.Style.FILL
        }

        fun bar(x: Float, y: Float, w: Float, h: Float, pct: Int, color: Int) {
            if (w <= 0f) return
            rrect(x, y, w, h, h / 2f, t.track)
            val f = pct.coerceIn(0, 100) / 100f
            if (f <= 0f) return
            val fw = max(h, w * f)
            rrect(x, y, fw, h, h / 2f, color,
                LinearGradient(x, 0f, x + fw, 0f, blend(color, Color.WHITE, .24f), color, Shader.TileMode.CLAMP))
        }

        fun chip(s: String, x: Float, y: Float, color: Int?): Float {
            val h = 12.5f
            val w = measure(s, 8.5f, 700, .035f) + 9f
            rrect(x, y, w, h, h / 2f, if (color != null) blend(color, t.bg, .74f) else t.chipBg)
            text(s, x + 4.5f, y + h - 4f, 8.5f, 700, color ?: t.dim, tracking = .035f)
            return w
        }

        fun circle(cx: Float, cy: Float, radius: Float, color: Int) {
            p.style = Paint.Style.FILL
            p.shader = null
            p.color = color
            c.drawCircle(cx, cy, radius, p)
        }

        fun line(x: Float, y: Float, w: Float, color: Int) {
            p.style = Paint.Style.FILL
            p.shader = null
            p.color = color
            c.drawRect(x, y - .5f, x + w, y + .5f, p)
        }

        fun arc(cx: Float, cy: Float, radius: Float, start: Float, sweep: Float, color: Int, width: Float) {
            if (sweep <= 0f) return
            r.set(cx - radius, cy - radius, cx + radius, cy + radius)
            p.style = Paint.Style.STROKE
            p.shader = null
            p.color = color
            p.strokeWidth = width
            p.strokeCap = Paint.Cap.ROUND
            c.drawArc(r, start, sweep, false, p)
            p.style = Paint.Style.FILL
        }

        fun path(path: Path, shader: Shader) {
            p.style = Paint.Style.FILL
            p.shader = shader
            p.color = Color.WHITE
            c.drawPath(path, p)
            p.shader = null
        }

        fun stroke(path: Path, color: Int, width: Float) {
            p.style = Paint.Style.STROKE
            p.shader = null
            p.color = color
            p.strokeWidth = width
            p.strokeCap = Paint.Cap.ROUND
            p.strokeJoin = Paint.Join.ROUND
            c.drawPath(path, p)
            p.style = Paint.Style.FILL
        }

        fun clipped(x: Float, y: Float, w: Float, h: Float, radius: Float, body: () -> Unit) {
            c.save()
            val clip = Path().apply { addRoundRect(RectF(x, y, x + w, y + h), radius, radius, Path.Direction.CW) }
            c.clipPath(clip)
            body()
            c.restore()
        }

        /** Small circular-arrow mark — drawn, not a text glyph. */
        fun refreshIcon(cx: Float, cy: Float, radius: Float, color: Int) {
            r.set(cx - radius, cy - radius, cx + radius, cy + radius)
            p.style = Paint.Style.STROKE
            p.shader = null
            p.color = color
            p.strokeWidth = 1.3f
            p.strokeCap = Paint.Cap.ROUND
            c.drawArc(r, -30f, 285f, false, p)
            p.style = Paint.Style.FILL
            val head = Path().apply {
                moveTo(cx + radius - 1.8f, cy - radius * .5f - 1.5f)
                lineTo(cx + radius + 1.6f, cy - radius * .5f - .4f)
                lineTo(cx + radius - 1f, cy - radius * .5f + 2.1f)
                close()
            }
            c.drawPath(head, p)
        }

        companion object {
            val BOLD: Typeface = Typeface.create("sans-serif", Typeface.BOLD)
            val MEDIUM: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            val REGULAR: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        }
    }
}
