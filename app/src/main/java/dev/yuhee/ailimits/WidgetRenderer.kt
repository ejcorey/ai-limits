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
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

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
        val showGemini: Boolean = false,
        val opacity: Int = 100,
        val projection: Boolean = true,
        val sparkline: Boolean = true,
        val tokens: Boolean = true,
        val pace: Boolean = true,
        val hiddenClaude: Set<String> = emptySet(),
        val hiddenCodex: Set<String> = emptySet(),
        val hiddenGemini: Set<String> = emptySet(),
    ) {
        val shown: Int get() =
            (if (showClaude) 1 else 0) + (if (showCodex) 1 else 0) + (if (showGemini) 1 else 0)
        val solo: Boolean get() = shown == 1
    }

    fun optsFrom(ctx: Context) = Opts(
        showClaude = Settings.showClaude(ctx),
        showCodex = Settings.showCodex(ctx),
        showGemini = Settings.showGemini(ctx),
        opacity = Settings.opacity(ctx),
        projection = Settings.showProjection(ctx),
        sparkline = Settings.showSparkline(ctx),
        tokens = Settings.showTokens(ctx),
        pace = Settings.showPace(ctx),
        hiddenClaude = Settings.hiddenWindows(ctx, "cl"),
        hiddenCodex = Settings.hiddenWindows(ctx, "cx"),
        hiddenGemini = Settings.hiddenWindows(ctx, "gm"),
    )

    // -- tiers -------------------------------------------------------------
    // Declared heights so the layout can guarantee a fit before it draws.
    // Sized for a Galaxy S24 Ultra: physically large widgets, so the type runs
    // bigger than a phone-generic default and the tiers claim a little more height.
    private const val H_FULL = 64f
    private const val H_MED = 44f
    private const val SPARK = 34f
    private const val H_RICH = H_FULL + SPARK
    private const val MIN_GAP = 10f
    private const val FOOT = 16f

    private const val RICH = 3
    private const val FULL = 2
    private const val MEDIUM = 1
    private const val COMPACT = 0

    private fun blockH(tier: Int, sparkline: Boolean = true) = when (tier) {
        // With sparklines switched off the rich tier has nothing extra to draw, so
        // reserving their height would just leave a gap.
        RICH -> if (sparkline) H_RICH else H_FULL
        FULL -> H_FULL
        else -> H_MED
    }

    private fun padFor(tier: Int) = if (tier == MEDIUM) 11f else 13f

    /** Height a tier needs for [panels] providers, padding and gap included. */
    internal fun neededHeight(tier: Int, panels: Int, sparkline: Boolean = true) =
        padFor(tier) * 2 + blockH(tier, sparkline) * panels + MIN_GAP

    /** The richest layout that genuinely fits, or [COMPACT] when none does. */
    internal fun tierFor(h: Float, panels: Int, sparkline: Boolean = true): Int {
        for (candidate in intArrayOf(RICH, FULL, MEDIUM)) {
            if (neededHeight(candidate, panels, sparkline) <= h) return candidate
        }
        return COMPACT
    }

    private val providers = listOf(
        UsageWidgetProvider::class.java,
        BarsWidgetProvider::class.java,
        PercentWidgetProvider::class.java,
        GraphWidgetProvider::class.java,
        BatteryWidgetProvider::class.java,
        CountdownWidgetProvider::class.java,
        TickerWidgetProvider::class.java,
    )

    private fun ids(ctx: Context, cls: Class<*>): IntArray =
        AppWidgetManager.getInstance(ctx).getAppWidgetIds(ComponentName(ctx, cls))

    fun anyWidgets(ctx: Context): Boolean = providers.any { ids(ctx, it).isNotEmpty() }

    fun updateAll(ctx: Context, refreshing: Boolean = false) = update(ctx, refreshing, null)

    /** Redraws a single widget — used while resizing, which fires per drag step. */
    fun updateOne(ctx: Context, appWidgetId: Int) = update(ctx, false, appWidgetId)

    private fun update(ctx: Context, refreshing: Boolean, onlyId: Int?) {
        val mgr = AppWidgetManager.getInstance(ctx)
        val snap = UsageRepo.load(ctx)
        val hist = UsageRepo.history(ctx)
        // Colours are resolved against the chosen theme, not necessarily the system one.
        val theme = Theme(Settings.themedContext(ctx))
        val opts = optsFrom(ctx)
        // Sizes differ per instance, so each widget is rendered on its own.
        providers.forEach { cls ->
            ids(ctx, cls).forEach { id ->
                if (onlyId != null && id != onlyId) return@forEach
                try {
                    mgr.updateAppWidget(id, build(ctx, mgr, id, cls, snap, hist, theme, opts, refreshing))
                } catch (e: Throwable) {
                    // One bad widget must not stop the others, but a silent failure
                    // leaves a blank widget with no way to find out why.
                    Log.e("Auspex", "widget $id (${cls.simpleName}) failed to render", e)
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
        hist: List<HistoryPoint>,
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
            BatteryWidgetProvider::class.java -> Style.BATTERY
            CountdownWidgetProvider::class.java -> Style.COUNTDOWN
            TickerWidgetProvider::class.java -> Style.TICKER
            else -> Style.DETAIL
        }
        return compose(ctx, style, wDp, hDp, snap, hist, t, o, refreshing)
    }

    /**
     * Builds the RemoteViews a launcher will inflate. Split out of [build] so a test
     * can construct and apply the real thing without an AppWidgetManager — inflating
     * it is the only way to catch a view class RemoteViews refuses to accept.
     */
    internal fun compose(
        ctx: Context,
        style: Style,
        wDp: Float,
        hDp: Float,
        snap: Snapshot,
        hist: List<HistoryPoint>,
        t: Theme? = null,
        o: Opts = Opts(),
        refreshing: Boolean = false,
    ): RemoteViews {
        val (bmp, footer) = render(ctx, style, wDp, hDp, snap, hist, refreshing, t, o)

        val rv = RemoteViews(ctx.packageName, R.layout.widget_canvas)
        rv.setImageViewBitmap(R.id.widget_canvas, bmp)
        rv.setOnClickPendingIntent(R.id.widget_root, tapPI(ctx, snap))
        // The "Open app" hit region only exists while the footer is actually drawn.
        rv.setViewVisibility(R.id.widget_settings, if (footer) View.VISIBLE else View.GONE)
        if (footer) rv.setOnClickPendingIntent(R.id.widget_settings, openAppPI(ctx, 3))
        return rv
    }

    enum class Style { DETAIL, BARS, RINGS, GRAPH, BATTERY, COUNTDOWN, TICKER }

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
        hist: List<HistoryPoint>,
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
            Style.BATTERY -> drawBattery(pen, wDp, hDp, snap, t, o)
            Style.COUNTDOWN -> drawCountdown(pen, wDp, hDp, snap, t, o)
            Style.TICKER -> drawTicker(pen, wDp, hDp, snap, t, o)
            Style.DETAIL -> footer = drawDetail(pen, wDp, hDp, snap, hist, t, o, refreshing)
        }
        return bmp to footer
    }

    /**
     * Removes the windows the user chose to hide. Hiding everything hides nothing:
     * a provider with no windows at all would just read as broken.
     */
    internal fun filterWindows(state: ProviderState, hidden: Set<String>): ProviderState {
        if (hidden.isEmpty() || state.windows.isEmpty()) return state
        val kept = state.windows.filter { it.label !in hidden }
        return if (kept.isEmpty()) state else state.copy(windows = kept)
    }

    /** The providers the user wants, window-filtered, paired with their history series. */
    private fun visible(
        snap: Snapshot, hist: List<HistoryPoint>, t: Theme, o: Opts,
    ): List<Panel> = buildList {
        fun panel(state: ProviderState, hidden: Set<String>, name: String, color: Int, series: List<Pair<Long, Int>>): Panel {
            val filtered = filterWindows(state, hidden)
            // History tracks the *unfiltered* binding window. If hiding windows changed
            // which one leads, that series describes a different window — so everything
            // derived from it (projection, burn rate, sparkline) is silently dropped by
            // handing the panel no history at all, rather than fabricating a trend.
            val sameHero = binding(filtered)?.label == binding(state)?.label
            return Panel(filtered, name, color, if (sameHero) series else emptyList())
        }
        if (o.showClaude) add(panel(snap.claude, o.hiddenClaude, "Claude", t.claude, hist.map { it.t to it.claude }))
        if (o.showCodex) add(panel(snap.codex, o.hiddenCodex, "Codex", t.codex, hist.map { it.t to it.codex }))
        if (o.showGemini) add(panel(snap.gemini, o.hiddenGemini, "Gemini", t.gemini, hist.map { it.t to it.gemini }))
    }

    private class Panel(
        val state: ProviderState,
        val name: String,
        val color: Int,
        val series: List<Pair<Long, Int>>,
    )

    /**
     * Pixel budget for one widget bitmap. At 4 bytes per pixel this caps a single
     * RemoteViews payload at ~2 MB, which is what keeps a large widget clear of
     * TransactionTooLargeException.
     */
    internal const val PIXEL_BUDGET = 520_000f

    /**
     * Keeps the bitmap crisp but inside [PIXEL_BUDGET]. Solved rather than stepped: the
     * old loop bottomed out at 1.25 and silently blew the budget on very large widgets,
     * so the guarantee held only as long as the declared max size happened to stay small.
     */
    internal fun scaleFor(density: Float, wDp: Float, hDp: Float): Float {
        val area = (wDp * hDp).coerceAtLeast(1f)
        val fits = sqrt(PIXEL_BUDGET / area)
        // Never below 1.0 — a dp-for-pixel bitmap is soft but still legible, and going
        // finer than that would make a huge widget unreadable to save memory nobody needs.
        return min(density.coerceIn(1.5f, 3f), fits).coerceAtLeast(1f)
    }

    private fun scaleFor(ctx: Context, wDp: Float, hDp: Float): Float =
        scaleFor(ctx.resources.displayMetrics.density, wDp, hDp)

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
        hist: List<HistoryPoint>, t: Theme, o: Opts, refreshing: Boolean,
    ): Boolean {
        g.card(w, h, o.opacity)
        val panels = visible(snap, hist, t, o)
        if (panels.size == 1) return drawSolo(g, w, h, panels[0], t, o, snap, refreshing)

        val n = panels.size
        val tier = tierFor(h, n, o.sparkline)
        if (tier == COMPACT) { drawCompact(g, w, h, panels, t); return false }

        val pad = padFor(tier)
        val x = pad
        val cw = w - pad * 2
        val bh = blockH(tier, o.sparkline)
        val slack = h - pad * 2 - bh * n
        val foot = slack >= MIN_GAP + FOOT

        // Spend leftover height on content rather than leaving a dead zone: a widget
        // dragged tall should show more, not the same thing with a gap. Every FULL/RICH
        // block can absorb surplus — into a stats line, then a taller sparkline — so the
        // gap is kept tight and the slack flows into the blocks instead of between them.
        var free = slack - if (foot) FOOT else 0f
        val canStretch = tier == RICH || tier == FULL
        val gap = min(20f, max(MIN_GAP, free / max(1, n + 1)))
        free -= gap * (n - 1)
        val stretch = if (canStretch) min(96f, max(0f, free / n)) else 0f
        val bhEff = bh + stretch
        val used = bhEff * n + gap * (n - 1)
        val y = pad + max(0f, (h - pad * 2 - (if (foot) FOOT else 0f) - used) / 2f)

        // One hero column width for the whole widget so every provider's bar starts at
        // the same x — the fix for bars that used to jump left/right with the digit count.
        val heroColW = heroColumn(g, panels)

        panels.forEachIndexed { i, p ->
            val by = y + i * (bhEff + gap)
            if (i > 0) g.line(x, by - gap / 2f, cw, t.rule)
            drawBlock(g, x, by, cw, p, t, o, tier, heroColW, stretch)
        }

        if (foot) footer(g, x, cw, h - pad - 1f, t, snap, refreshing)
        else staleDot(g, w, t, snap)
        return foot
    }

    private fun footer(
        g: Pen, x: Float, cw: Float, fy: Float, t: Theme, snap: Snapshot, refreshing: Boolean,
    ) {
        g.refreshIcon(x + 4.5f, fy - 5f, 4.5f, t.faint)
        val stamp = if (refreshing) "updating…"
        else if (snap.fetchedAt > 0) "updated " + clock(snap.fetchedAt) else "not updated yet"
        val stampW = g.text(stamp, x + 14f, fy - 1f, 10.5f, 500, t.faint)
        // "Open app" only when it clears the timestamp — the whole widget taps anyway.
        if (14f + stampW + 12f + g.measure("Open app", 10.5f, 500) <= cw) {
            g.text("Open app", x + cw, fy - 1f, 10.5f, 500, t.faint, Paint.Align.RIGHT)
        }
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
            g.text(noWindowMsg(p), x, pad + 42f, 14f, 500, if (p.state.error != null) t.warn else t.dim)
            return false
        }

        val sc = t.status(b.pct, p.color)
        val proj = if (o.projection) projection(b, p.series) else null
        val head = if (proj != null) "on pace to cap " + clock(proj)
        else "resets " + resetClock(b.resetsAt) + " · " + left(b.resetsAt) + " left"
        // Measure at the weight it is actually drawn at — the warning is heavier than
        // the plain reset text, so measuring at 500 let it overflow the fit guard.
        val headWeight = if (proj != null) 600 else 500
        if (g.measure(head, 11f, headWeight) <= x + cw - cx) {
            g.text(head, x + cw, pad + 11f, 11f, headWeight,
                if (proj != null) t.warn else t.faint, Paint.Align.RIGHT)
        }

        // Hero, sized up now that it is not sharing the widget — but kept modest on
        // short widgets so the window rows still get a look in.
        val tight = h < 140f
        val hy = pad + if (tight) 15f else 20f
        val heroSize = if (tight) 33f else 42f
        val heroBase = hy + heroSize * .77f
        // "USED" eyebrow, inline left of the number, so the headline reads "USED 68%".
        val ew = g.text("USED", x, heroBase - heroSize * .30f, 10.5f, 700, t.dim, tracking = .06f)
        val hx = x + ew + 8f
        val nw = g.text("${b.pct}", hx, heroBase, heroSize, 700, sc, tracking = -.017f)
        g.text("%", hx + nw + 3f, heroBase, heroSize * .43f, 700, sc)
        val bx = hx + nw + if (tight) 24f else 30f
        g.bar(bx, heroBase - heroSize * .30f - 5.5f, x + cw - bx, if (tight) 11f else 13f, b.pct, sc)
        val nameY = heroBase + 17f
        g.text(windowName(b.label), x, nameY, 11f, 500, t.dim, tracking = .02f)
        // Fill the room beside the window name with what is left and how fast it is going.
        // Prefer a real count over a percentage, then append the widest trend phrase that
        // fits beside the window name — burn and pace both, if there is room for both.
        val tokens = if (o.tokens) remainingText(b) else null
        val headroom = tokens ?: "${100 - b.pct}% left"
        val burn = burnText(p.series)
        val pace = if (o.pace) paceText(b) else null
        val both = if (burn != null && pace != null) {
            (burn.first + " · " + pace.first) to (burn.second || pace.second)
        } else null
        val room = cw - g.measure(windowName(b.label), 11f, 500) - 14f
        val trend = listOfNotNull(both, burn, pace)
            .firstOrNull { g.measure(headroom + " · " + it.first, 11f, 500) <= room }
        val stat = headroom + (trend?.let { " · " + it.first } ?: "")
        g.text(stat, x + cw, nameY, 11f, 500,
            if (trend?.second == true) t.warn else t.faint, Paint.Align.RIGHT)

        // Every other window gets a real row, its bar in a column shared with the others.
        val rest = p.state.windows.filter { it !== b }
        var ry = nameY + 12f
        val lx = x + 54f
        val rw = max(24f, cw - 54f - 104f)
        rest.forEach { v ->
            if (ry + 15f > h - pad) return@forEach
            g.text(v.label, x, ry + 9f, 11f, 600, t.dim, tracking = .02f)
            g.bar(lx, ry + 3f, rw, 7f, v.pct, t.status(v.pct, p.color))
            g.text("${v.pct}%", lx + rw + 40f, ry + 9f, 11.5f, 700, t.text, Paint.Align.RIGHT)
            g.text(left(v.resetsAt), x + cw, ry + 9f, 10f, 500, t.faint, Paint.Align.RIGHT)
            ry += 17f
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

    /** Width reserved for the "USED nn%" hero across all panels, so every bar starts aligned. */
    private fun heroColumn(g: Pen, panels: List<Panel>): Float {
        val eyebrow = g.measure("USED", 10.5f, 700, .06f)
        val pctW = g.measure("%", 15f, 700)
        val maxPct = panels.mapNotNull { binding(it.state)?.pct }.maxOrNull() ?: 88
        val numW = g.measure(maxPct.toString(), 34f, 700, -.017f)
        return eyebrow + 7f + numW + 2f + pctW + 16f
    }

    private fun drawBlock(
        g: Pen, x: Float, y: Float, w: Float, p: Panel, t: Theme, o: Opts, tier: Int,
        heroColW: Float, stretch: Float = 0f,
    ) {
        val st = p.state
        val name = p.name
        val color = p.color
        val series = p.series
        val med = tier == MEDIUM
        val b = binding(st)

        g.circle(x + 4f, y + 6.5f, 3.8f, if (st.configured) color else t.faint)
        var cx = x + 13f
        cx += g.text(name, cx, y + 11f, 13f, 700, if (st.configured) color else t.dim, tracking = .035f) + 7f
        val plan = if (st.configured) prettyPlan(st.plan) else null
        if (plan != null) cx += g.chip(plan, cx, y - 0.5f, color) + 7f

        if (b == null) {
            g.text(noWindowMsg(p), x, y + if (med) 34f else 42f, if (med) 12.5f else 13.5f, 500,
                if (st.error != null) t.warn else t.dim)
            return
        }

        val sc = t.status(b.pct, color)
        val proj = if (o.projection) projection(b, series) else null
        val head = if (proj != null) "on pace to cap " + clock(proj)
        else "resets " + resetClock(b.resetsAt) + " · " + left(b.resetsAt) + " left"
        val headWeight = if (proj != null) 600 else 500
        val headColor = if (proj != null) t.warn else t.faint
        // Medium carries the reset on its own meta line, so only the taller tiers
        // put it in the header — otherwise it reads twice.
        val wantHead = !med || proj != null
        val headW = g.measure(head, 11f, headWeight)
        val headInHeader = wantHead && headW <= x + w - cx
        if (headInHeader) {
            g.text(head, x + w, y + 11f, 11f, headWeight, headColor, Paint.Align.RIGHT)
        }

        if (med) {
            // Reserve the right column for the widest possible "USED 100%" so every bar
            // ends at the same x regardless of the value — bars line up across providers.
            val rightCol = g.measure("100%", 16f, 700) + g.measure("USED", 9f, 700, .06f) + 12f
            val pw = g.text("${b.pct}%", x + w, y + 29f, 16f, 700, sc, Paint.Align.RIGHT)
            g.text("USED", x + w - pw - 6f, y + 29f, 9f, 700, t.dim, Paint.Align.RIGHT, .06f)
            // A stub bar reads as noise; below a useful width the number carries alone.
            if (w - rightCol >= 32f) g.bar(x, y + 19f, w - rightCol, 10f, b.pct, sc)
            // Drop meta segments from the end until the line fits a narrow widget. A real
            // count replaces the percentage when the provider gave us one.
            val left = 100 - b.pct
            val headroom = (if (o.tokens) remainingText(b) else null) ?: "$left% left"
            val meta = listOf(
                shortWindow(b.label) + " · " + headroom + " · resets " + resetClock(b.resetsAt),
                headroom + " · resets " + resetClock(b.resetsAt),
                headroom,
                "$left% left",
            ).firstOrNull { g.measure(it, 10f, 500) <= w } ?: "$left% left"
            g.text(meta, x, y + 41f, 10f, 500, t.faint)
            return
        }

        // Hero: the number is left-aligned after a vertically-centred "USED" eyebrow, and
        // the bar starts at a column shared by every provider (heroColW), so the bars line
        // up no matter whether a value is one, two or three digits — the main unevenness.
        val heroBase = y + 44f
        val numCenter = heroBase - 34f * .34f
        g.text("USED", x, numCenter + 10.5f * .34f, 10.5f, 700, t.dim, tracking = .06f)
        val eyebrowW = g.measure("USED", 10.5f, 700, .06f)
        val hx = x + eyebrowW + 7f
        val nw = g.text("${b.pct}", hx, heroBase, 34f, 700, sc, tracking = -.017f)
        val pcw = g.text("%", hx + nw + 2f, heroBase, 15f, 700, sc)
        // The bar shares the row only when it genuinely fits; on a narrow widget it is
        // dropped rather than drawn over the number, and the big "USED nn%" carries alone.
        val bx = x + heroColW
        if (bx >= hx + nw + 2f + pcw + 8f && x + w - bx >= 44f) {
            g.bar(bx, numCenter - 5.5f, x + w - bx, 11f, b.pct, sc)
        }

        val my = y + 58f
        val lw = g.text(windowName(b.label), x, my, 11f, 500, t.dim, tracking = .02f)
        val room = w - lw - 16f
        if (wantHead && !headInHeader && headW <= room) {
            // A wide plan chip crowded the header out; when it comes to it, knowing
            // when the limit lifts beats listing the other windows.
            g.text(head, x + w, my, 11f, headWeight, headColor, Paint.Align.RIGHT)
        } else {
            drawSecondary(g, x + w, my, room, st.windows.filter { it !== b }, color, t)
        }

        // Everything under the meta line is opportunistic, spent to fill the block so a
        // tall or sparkline-less widget shows more rather than leaving a void. When a
        // sparkline is wanted it is the RICH signature and takes the space first, with a
        // stats line (how much is left, how fast it is burning) lifted above it only when
        // there is comfortably room for both. Otherwise the stats line alone fills
        // whatever height the block was stretched to.
        val wantSpark = tier == RICH && o.sparkline
        val blockBottom = y + blockH(tier, o.sparkline) + stretch
        var top = y + 64f
        if (wantSpark) {
            var sh = blockBottom - top - 2f
            if (sh >= 34f) { drawStats(g, x, top + 9f, w, b, series, t, o); top += 17f; sh -= 17f }
            if (sh >= 16f) sparkline(g, x, top, w, blockBottom - top - 2f, series, color, t)
        } else if (blockBottom - top >= 7f) {
            drawStats(g, x, top + 9f, w, b, series, t, o)
        }
    }

    private fun noWindowMsg(p: Panel): String = when {
        !p.state.configured -> "Tap to sign in"
        p.state.error != null -> shortError(p.state.error!!)
        else -> "No data yet"
    }

    /** Right-aligned group of the non-binding windows; degrades until it fits. */
    private fun drawSecondary(
        g: Pen, rightX: Float, baseY: Float, maxW: Float, rest: List<Win>, color: Int, t: Theme,
    ) {
        if (rest.isEmpty() || maxW < 44f) return
        fun width(items: List<Win>, withBar: Boolean): Float =
            items.sumOf {
                (g.measure(it.label, 10f, 600, .02f) + (if (withBar) 24f else 0f) + 5f +
                    g.measure("${it.pct}%", 11f, 700)).toDouble()
            }.toFloat() + (items.size - 1) * 12f

        for (withBar in booleanArrayOf(true, false)) {
            for (n in rest.size downTo 1) {
                val items = rest.take(n)
                if (width(items, withBar) > maxW) continue
                var rx = rightX
                items.reversed().forEach { v ->
                    rx -= g.text("${v.pct}%", rx, baseY, 11f, 700, t.text, Paint.Align.RIGHT) + 5f
                    if (withBar) {
                        g.bar(rx - 20f, baseY - 6f, 20f, 5f, v.pct, t.status(v.pct, color))
                        rx -= 24f
                    }
                    rx -= g.text(v.label, rx, baseY, 10f, 600, t.dim, Paint.Align.RIGHT, .02f) + 12f
                }
                return
            }
        }
    }

    /** Compact: providers side by side, one glance each. */
    private fun drawCompact(g: Pen, w: Float, h: Float, panels: List<Panel>, t: Theme) {
        val pad = 13f
        val gap = 18f
        val n = max(1, panels.size)
        val colW = (w - pad * 2 - gap * (n - 1)) / n
        // Below this there is no room for a name and a number on the same line.
        val roomy = colW >= 96f
        val single = h >= 60f

        panels.forEachIndexed { i, p ->
            val x = pad + i * (colW + gap)
            val b = binding(p.state)
            val sc = if (b != null) t.status(b.pct, p.color) else t.faint
            val cy = h / 2f
            val nameColor = if (p.state.configured) p.color else t.dim
            if (roomy) {
                g.circle(x + 4f, cy - 11f, 3.5f, if (p.state.configured) p.color else t.faint)
                g.text(p.name, x + 13f, cy - 7f, 11f, 700, nameColor, tracking = .03f)
                val numW = g.text(if (b != null) "${b.pct}%" else "--", x + colW, cy - 7f, 14f, 700, sc, Paint.Align.RIGHT)
                if (b != null && colW >= 128f) {
                    g.text("USED", x + colW - numW - 5f, cy - 7f, 8f, 700, t.dim, Paint.Align.RIGHT, .06f)
                }
            } else {
                // Too narrow for both: the number is what matters, keep only a colour cue.
                g.circle(x + 4f, cy - 10f, 3.5f, if (p.state.configured) p.color else t.faint)
                g.text(if (b != null) "${b.pct}%" else "--", x + colW, cy - 6f, 14f, 700, sc, Paint.Align.RIGHT)
            }
            g.bar(x, cy, colW, 9f, b?.pct ?: 0, sc)
            if (single) {
                val sub = when {
                    b != null -> {
                        val full = shortWindow(b.label) + " · " + left(b.resetsAt) + " left"
                        if (g.measure(full, 10f, 500) <= colW) full else left(b.resetsAt) + " left"
                    }
                    !p.state.configured -> "tap to sign in"
                    else -> "no data yet"
                }
                g.text(sub, x, cy + 20f, 10f, 500, t.faint)
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
        val showLabel = h >= 88f
        val showSub = h >= 104f
        val bottom = if (showSub) 30f else if (showLabel) 17f else 0f
        // One ring gets the whole card, so it can be much larger.
        val r = min((w / n - 30f) / 2f, (h - bottom - 14f) / 2f).coerceAtLeast(10f)
        panels.forEachIndexed { i, p ->
            // Evenly spaced across the width so two or three dials both sit centred.
            val cx = if (n == 1) w / 2f else w * (i + 0.5f) / n
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
            // "USED" above the dial number, so the reading is unambiguous even at a glance.
            if (b != null && r >= 26f) {
                g.text("USED", cx, cy - ns * .5f, min(8.5f, r * .18f), 700, t.dim, Paint.Align.CENTER, .02f)
            }
            g.text(label, cx - off, cy + ns * .34f, ns, 700, c, tracking = -.017f)
            if (b != null) g.text("%", cx - off + nw + 1.5f, cy + ns * .34f, r * .26f, 700, c)
            if (showLabel) g.text(name, cx, cy + r + 13f, 10.5f, 700, t.dim, Paint.Align.CENTER, .05f)
            if (showSub) {
                val sub = when {
                    b != null -> left(b.resetsAt) + " left"
                    !p.state.configured -> "tap to sign in"
                    else -> "no data yet"
                }
                g.text(sub, cx, cy + r + 26f, 10f, 500, t.faint, Paint.Align.CENTER)
            }
        }
    }

    // --- slim bars --------------------------------------------------------

    private fun drawBars(g: Pen, w: Float, h: Float, snap: Snapshot, t: Theme, o: Opts) {
        g.card(w, h, o.opacity)
        val pad = 14f
        val cw = w - pad * 2
        val panels = visible(snap, hist = emptyList(), t = t, o = o)
        val rowH = 26f
        val top = h / 2f - rowH * panels.size / 2f + 1f

        // Columns are budgeted from the width actually available rather than fixed
        // offsets, which used to go negative and push text past the right edge.
        val nameW = min(62f, max(0f, cw * .26f))
        val showName = nameW >= 44f
        // Wide enough to spell out "USED nn%"; otherwise the number stands alone.
        val showUsed = cw >= 210f
        val pctW = if (showUsed) min(86f, cw * .30f) else min(46f, cw * .18f)
        val resetW = if (cw >= 224f) min(58f, cw * .2f) else 0f
        val barX = pad + if (showName) nameW else 12f
        val barW = max(18f, pad + cw - resetW - pctW - 6f - barX)

        panels.forEachIndexed { i, p ->
            val y = top + i * rowH
            val b = binding(p.state)
            val sc = if (b != null) t.status(b.pct, p.color) else t.faint
            g.circle(pad + 3.5f, y + 6.5f, 3.4f, if (p.state.configured) p.color else t.faint)
            if (showName) {
                g.text(p.name, pad + 12f, y + 11f, 11f, 700,
                    if (p.state.configured) p.color else t.dim, tracking = .03f)
            }
            g.bar(barX, y + 4f, barW, 9f, b?.pct ?: 0, sc)
            val numRight = barX + barW + pctW
            val numW = g.text(if (b != null) "${b.pct}%" else "--", numRight, y + 11f, 13f, 700,
                t.text, Paint.Align.RIGHT)
            if (b != null && showUsed) {
                g.text("USED", numRight - numW - 5f, y + 11f, 8.5f, 700, t.dim,
                    Paint.Align.RIGHT, .06f)
            }
            if (resetW > 0f) {
                g.text(if (b != null) left(b.resetsAt) else "—", pad + cw, y + 11f, 10f, 500,
                    t.faint, Paint.Align.RIGHT)
            }
        }
    }


    // --- battery ----------------------------------------------------------
    // The inverse framing of every other style: not how much is spent, but how
    // much fuel is left before the provider runs dry.

    /**
     * The floor shared by the three compact styles: a dot and a percentage per provider
     * on one centred line, with the type stepped down until it genuinely fits. Battery
     * and Countdown fall back to this once their column is too narrow to draw their own
     * figure without colliding with the neighbour.
     */
    private fun drawDense(g: Pen, w: Float, h: Float, panels: List<Panel>, t: Theme) {
        data class Seg(val pct: String, val color: Int, val configured: Boolean)
        val segs = panels.map { p ->
            val b = binding(p.state)
            Seg(
                if (b != null) "${b.pct}%" else "--",
                if (b != null) t.status(b.pct, p.color) else t.faint,
                p.state.configured,
            )
        }

        fun layout(size: Float, gap: Float, dot: Float): Float =
            segs.sumOf { (g.measure(it.pct, size, 700) + dot * 2 + 3f).toDouble() }.toFloat() +
                gap * (segs.size - 1)

        // Shrink, then tighten the gap, before giving up and using the smallest.
        var size = 13f
        var gap = 9f
        var dot = 3.2f
        while (size > 8f && layout(size, gap, dot) > w - 10f) {
            size -= .5f
            gap = max(3f, gap - .4f)
            dot = max(1.8f, dot - .12f)
        }

        var x = (w - layout(size, gap, dot)) / 2f
        val baseY = h / 2f + size * .36f
        segs.forEach { sg ->
            g.circle(x + dot, baseY - size * .32f, dot, if (sg.configured) sg.color else t.faint)
            x += dot * 2 + 3f
            x += g.text(sg.pct, x, baseY, size, 700, sg.color) + gap
        }
    }

    private fun drawBattery(g: Pen, w: Float, h: Float, snap: Snapshot, t: Theme, o: Opts) {
        g.card(w, h, o.opacity)
        val panels = visible(snap, hist = emptyList(), t = t, o = o)
        if (panels.isEmpty()) return
        val n = panels.size
        val pad = 12f
        val colW = (w - pad * 2) / n
        // A battery needs room for its body, cap and the gap to its neighbour. Below
        // that the bodies used to overlap and the percentages ran together.
        if (colW < 46f) { drawDense(g, w, h, panels, t); return }
        val showName = h >= 74f
        val showReset = h >= 100f
        val bodyH = min(34f, h * .36f).coerceAtLeast(18f)
        // Clamped to the column, never merely coerced up past it.
        val bodyW = min(colW - 12f, bodyH * 2.3f).coerceIn(28f, colW - 10f)
        val block = bodyH + (if (showName) 17f else 0f) + (if (showReset) 14f else 0f)
        val topY = (h - block) / 2f

        panels.forEachIndexed { i, p ->
            val cx = pad + colW * i + colW / 2f
            val b = binding(p.state)
            val sc = if (b != null) t.status(b.pct, p.color) else t.faint
            val bx = cx - bodyW / 2f

            g.strokeRR(bx, topY, bodyW, bodyH, 5f, if (p.state.configured) t.dim else t.faint, 1.7f)
            g.rrect(bx + bodyW + 2.5f, topY + bodyH * .3f, 4f, bodyH * .4f, 2f, t.dim)

            if (b != null) {
                val remaining = (100 - b.pct).coerceIn(0, 100)
                val fw = (bodyW - 8f) * remaining / 100f
                if (fw > 1f) g.rrect(bx + 4f, topY + 4f, fw, bodyH - 8f, 3f, sc)
                // Only label the fill when the glyphs fit inside the body.
                val lbl = "$remaining%"
                val ls = if (g.measure(lbl, 13f, 700) <= bodyW - 8f) 13f else 10.5f
                if (g.measure(lbl, ls, 700) <= bodyW - 5f) {
                    g.text(lbl, cx, topY + bodyH / 2f + ls * .35f, ls, 700, t.text, Paint.Align.CENTER)
                }
            } else {
                g.text("--", cx, topY + bodyH / 2f + 4.5f, 12f, 700, t.faint, Paint.Align.CENTER)
            }
            if (showName) {
                g.text(p.name, cx, topY + bodyH + 14f, 10.5f, 700,
                    if (p.state.configured) p.color else t.dim, Paint.Align.CENTER, .03f)
            }
            if (showReset) {
                // With a real count available, "1.2M tokens left" beats repeating the reset.
                val tokens = if (o.tokens && b != null) remainingText(b) else null
                val sub = when {
                    b == null -> "tap to sign in"
                    tokens != null && g.measure(tokens, 9.5f, 500) <= colW - 8f -> tokens
                    else -> "resets " + left(b.resetsAt)
                }
                g.text(sub, cx, topY + bodyH + 28f, 9.5f, 500, t.faint, Paint.Align.CENTER)
            }
        }
    }

    // --- countdown --------------------------------------------------------
    // Time-first: the headline is when you get your capacity back, not how much
    // of it is gone. The thin bar underneath is how far through the window you are.

    /** Wall-clock length of a window, inferred from its label; null when unknowable. */
    internal fun windowLengthMs(label: String): Long? {
        Regex("^(\\d+)([hd])$").find(label.lowercase())?.let { m ->
            val v = m.groupValues[1].toLong()
            return v * if (m.groupValues[2] == "h") 3600_000L else 86_400_000L
        }
        return when (label.lowercase()) {
            "weekly" -> 7 * 86_400_000L
            "daily" -> 86_400_000L
            "primary" -> 5 * 3600_000L
            else -> null
        }
    }

    private fun drawCountdown(g: Pen, w: Float, h: Float, snap: Snapshot, t: Theme, o: Opts) {
        g.card(w, h, o.opacity)
        val panels = visible(snap, hist = emptyList(), t = t, o = o)
        if (panels.isEmpty()) return
        val n = panels.size
        val pad = 12f
        val colW = (w - pad * 2) / n
        // "3h 39m" at a legible size needs roughly this much column; under it the
        // durations from adjacent providers used to overprint each other.
        if (colW < 58f) { drawDense(g, w, h, panels, t); return }
        val showName = h >= 66f
        val showSub = h >= 92f
        val big = min(colW * .22f, 27f).coerceAtLeast(14f)
        val block = (if (showName) 16f else 0f) + big + (if (showSub) 15f else 0f) + 10f
        val topY = (h - block) / 2f
        val now = System.currentTimeMillis()

        panels.forEachIndexed { i, p ->
            val cx = pad + colW * i + colW / 2f
            val b = binding(p.state)
            var y = topY
            if (showName) {
                g.circle(cx - g.measure(p.name, 10.5f, 700, .03f) / 2f - 9f, y + 6.5f, 3.4f,
                    if (p.state.configured) p.color else t.faint)
                g.text(p.name, cx, y + 10.5f, 10.5f, 700,
                    if (p.state.configured) p.color else t.dim, Paint.Align.CENTER, .03f)
                y += 16f
            }
            if (b == null) {
                g.text(if (p.state.configured) "no data" else "sign in", cx, y + big * .8f,
                    12f, 500, t.faint, Paint.Align.CENTER)
                return@forEachIndexed
            }
            val urgent = b.resetsAt in 1 until now + 30 * 60_000L
            val dur = left(b.resetsAt)
            // Step the figure down rather than let it spill into the next column.
            var ds = big
            while (ds > 10f && g.measure(dur, ds, 700, -.01f) > colW - 8f) ds -= .5f
            g.text(dur, cx, y + big * .8f, ds, 700,
                if (urgent) t.warn else t.text, Paint.Align.CENTER, -.01f)
            y += big + 5f
            if (showSub) {
                g.text("${b.pct}% · " + shortWindow(b.label), cx, y + 9f, 10f, 500, t.dim, Paint.Align.CENTER)
                y += 15f
            }
            // Elapsed-through-the-window bar, only when the label tells us its length.
            val len = windowLengthMs(b.label)
            if (len != null && b.resetsAt > now) {
                val remainMs = (b.resetsAt - now).coerceAtMost(len)
                val elapsed = 1f - remainMs.toFloat() / len
                val bw = (colW - 28f).coerceAtLeast(26f)
                g.bar(cx - bw / 2f, y + 2f, bw, 5f, (elapsed * 100).toInt(), t.status(b.pct, p.color))
            }
        }
    }

    // --- ticker -----------------------------------------------------------
    // The densest possible readout: one line of text, every provider, no chrome.

    private fun drawTicker(g: Pen, w: Float, h: Float, snap: Snapshot, t: Theme, o: Opts) {
        g.card(w, h, o.opacity)
        val panels = visible(snap, hist = emptyList(), t = t, o = o)
        if (panels.isEmpty()) return
        val twoLine = h >= 56f
        val y1 = if (twoLine) h / 2f - 3f else h / 2f + 4.5f

        class Seg(val name: String, val pct: String, val color: Int, val configured: Boolean)
        val segs = panels.map { p ->
            val b = binding(p.state)
            Seg(p.name, if (b != null) "${b.pct}%" else "--",
                if (b != null) t.status(b.pct, p.color) else t.faint, p.state.configured)
        }
        val sep = "   ·   "

        fun width(withNames: Boolean): Float {
            var total = 0f
            segs.forEachIndexed { i, sg ->
                total += 10f // dot + gap
                if (withNames) total += g.measure(sg.name, 11.5f, 600, .01f) + 5f
                total += g.measure(sg.pct, 13f, 700)
                if (i < segs.size - 1) total += g.measure(sep, 11f, 500)
            }
            return total
        }

        // Names are a luxury; when even the numbers alone will not fit, hand off to the
        // font-fitting routine rather than drawing past both edges.
        val withNames = width(true) <= w - 24f
        if (!withNames && width(false) > w - 14f) {
            drawDense(g, w, h, panels, t)
            return
        }
        var x = (w - width(withNames)) / 2f
        segs.forEachIndexed { i, sg ->
            g.circle(x + 3.4f, y1 - 4f, 3.4f, if (sg.configured) sg.color else t.faint)
            x += 10f
            if (withNames) {
                x += g.text(sg.name, x, y1, 11.5f, 600, if (sg.configured) t.dim else t.faint, tracking = .01f) + 5f
            }
            x += g.text(sg.pct, x, y1, 13f, 700, sg.color)
            if (i < segs.size - 1) x += g.text(sep, x, y1, 11f, 500, t.faint)
        }

        if (twoLine) {
            // The soonest reset is the next event that matters, whoever owns it.
            val next = panels.mapNotNull { p ->
                binding(p.state)?.takeIf { it.resetsAt > 0 }?.let { p.name to it }
            }.minByOrNull { it.second.resetsAt }
            if (next != null) {
                g.text(
                    "next reset " + left(next.second.resetsAt) + " · " + next.first,
                    w / 2f, y1 + 18f, 9.5f, 500, t.faint, Paint.Align.CENTER
                )
            }
        }
    }

    // --- 24h history ------------------------------------------------------

    private fun drawGraph(
        g: Pen, w: Float, h: Float, snap: Snapshot,
        hist: List<HistoryPoint>, t: Theme, o: Opts,
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
            g.text("Last 24 hours · USED %", padL, 18f, 11.5f, 700, t.text, tracking = .03f)
            var lx = w - padR
            panels.reversed().forEach { p ->
                val b = binding(p.state)
                val c = p.color
                val s = if (b != null) "${p.name} ${b.pct}%" else p.name
                val tw = g.measure(s, 10.5f, 600, .02f)
                g.text(s, lx, 18f, 10.5f, 600, c, Paint.Align.RIGHT, .02f)
                g.circle(lx - tw - 8f, 14f, 2.8f, c)
                lx -= tw + 19f
            }
        }

        intArrayOf(0, 50, 100).forEach { v ->
            val y = gy + (100 - v) / 100f * gh
            g.line(gx, y, gw, t.rule)
            if (showYAxis) g.text("$v%", gx + gw + 5f, y + 3.4f, 9.5f, 500, t.faint)
        }
        if (showXAxis) {
            g.text("24h ago", gx, h - 6f, 9.5f, 500, t.faint)
            g.text("12h", gx + gw / 2f, h - 6f, 9.5f, 500, t.faint, Paint.Align.CENTER)
            g.text("now", gx + gw, h - 6f, 9.5f, 500, t.faint, Paint.Align.RIGHT)
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

    /**
     * Recent consumption of the binding window, in percentage points per hour —
     * the "burn" the user reads to know how fast the quota is going. Averaged over
     * up to the last 3h; null when there isn't enough recent signal to be honest.
     */
    internal fun burnRate(series: List<Pair<Long, Int>>): Float? {
        val now = System.currentTimeMillis()
        val pts = series.filter { it.first >= now - 3 * 3600_000L && it.second >= 0 }
        if (pts.size < 2) return null
        val first = pts.first()
        val last = pts.last()
        val hours = (last.first - first.first) / 3600_000f
        if (hours < .4f) return null
        return (last.second - first.second) / hours
    }

    /** Burn as a phrase, plus whether it is steep enough to colour. Null = say nothing. */
    private fun burnText(series: List<Pair<Long, Int>>): Pair<String, Boolean>? {
        val r = burnRate(series) ?: return null
        return when {
            r >= 1f -> "burning ${r.roundToInt()}%/hr" to (r >= 8f)
            r <= -1f -> "easing off" to false
            else -> "holding steady" to false
        }
    }

    /**
     * A supporting line under the hero. The left half says how much is left — as a real
     * count when the provider reports one, otherwise as a percentage. The right half is
     * the most informative thing that fits: how fast it is burning, else how the spend
     * compares with the clock.
     */
    private fun drawStats(
        g: Pen, x: Float, baseY: Float, w: Float, b: Win, series: List<Pair<Long, Int>>,
        t: Theme, o: Opts,
    ) {
        val tokens = if (o.tokens) remainingText(b) else null
        val leftLabel = tokens ?: "${100 - b.pct}% left"
        val leftW = g.text(leftLabel, x, baseY, 11f, 600, t.dim)

        // Burn (how fast) and pace (whether that speed is sustainable) answer different
        // questions, so both are offered and the widest combination that fits is used.
        // Picking one over the other would have made pace nearly unreachable, since burn
        // is available as soon as an hour of history exists.
        val burn = burnText(series)
        val pace = if (o.pace) paceText(b) else null
        val both = if (burn != null && pace != null) {
            (burn.first + " · " + pace.first) to (burn.second || pace.second)
        } else null
        val room = w - leftW - 12f
        val right = listOfNotNull(both, burn, pace)
            .firstOrNull { g.measure(it.first, 11f, 500) <= room } ?: return
        g.text(right.first, x + w, baseY, 11f, 500, if (right.second) t.warn else t.faint, Paint.Align.RIGHT)
    }

    /**
     * Spend measured against the clock: 1.0 means the window is being consumed exactly
     * as fast as it refills, 2.0 means twice that. Needs a window whose length is known
     * and enough of it elapsed to be meaningful — early in a window the ratio is wild
     * (1% into a 5-hour window, any usage looks like 50x), so it stays silent until 8%.
     */
    internal fun pace(w: Win, now: Long = System.currentTimeMillis()): Float? {
        val len = windowLengthMs(w.label) ?: return null
        if (w.resetsAt <= 0) return null
        val remain = (w.resetsAt - now).coerceIn(0L, len)
        val elapsed = 1f - remain.toFloat() / len
        if (elapsed < .08f) return null
        return (w.pct / 100f) / elapsed
    }

    /** Pace as a phrase, plus whether it is steep enough to colour. */
    internal fun paceText(w: Win, now: Long = System.currentTimeMillis()): Pair<String, Boolean>? {
        val r = pace(w, now) ?: return null
        return when {
            r >= 1.15f -> String.format(Locale.US, "%.1fx pace", r) to (r >= 1.5f)
            r <= .85f -> "under pace" to false
            else -> "on pace" to false
        }
    }

    /**
     * A count still available, when the provider actually reports one. Claude and Codex
     * publish percentages only, so this is null for them by construction rather than
     * back-computed from a guessed allowance.
     */
    internal fun remainingText(w: Win): String? {
        val n = w.remaining ?: return null
        val unit = w.unit?.lowercase()
        val noun = when {
            unit == null -> ""
            unit.contains("token") -> " tokens"
            unit.contains("request") -> " requests"
            unit.contains("credit") -> " credits"
            else -> " " + unit.replace('_', ' ')
        }
        return compactCount(n) + noun + " left"
    }

    /** 950 -> "950", 12_400 -> "12.4K", 3_200_000 -> "3.2M". */
    internal fun compactCount(n: Long): String = when {
        n < 1_000 -> n.toString()
        n < 1_000_000 -> trimZero(n / 1_000.0) + "K"
        n < 1_000_000_000 -> trimZero(n / 1_000_000.0) + "M"
        else -> trimZero(n / 1_000_000_000.0) + "B"
    }

    private fun trimZero(v: Double): String {
        val s = String.format(Locale.US, "%.1f", v)
        return if (s.endsWith(".0")) s.dropLast(2) else s
    }

    /**
     * Reset as a wall clock, with a weekday once it is far enough out that "22:19"
     * alone would be ambiguous — a 7-day window resetting on Friday reads better as
     * "Fri 09:00" than as a bare time three days away.
     */
    internal fun resetClock(ms: Long, now: Long = System.currentTimeMillis()): String {
        if (ms <= 0) return "--:--"
        val far = ms - now >= 20 * 3600_000L
        val fmt = if (far) "EEE HH:mm" else "HH:mm"
        return SimpleDateFormat(fmt, Locale.getDefault()).format(Date(ms))
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
            "daily" -> "daily window"
            // Claude's model-scoped windows are all weekly.
            "opus", "sonnet", "haiku" -> "$label · 7-day"
            // Anything else (e.g. Gemini's per-model buckets) gets a neutral name —
            // guessing a cadence we don't know would be wrong.
            else -> "$label limit"
        }
    }

    private fun shortWindow(label: String) = windowName(label).removeSuffix(" window")

    /** "plus" -> "Plus", "chatgpt_pro" -> "ChatGPT Pro". */
    internal fun prettyPlan(plan: String?): String? {
        val p = plan?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val pretty = p.split('_', '-', ' ').filter { it.isNotEmpty() }.joinToString(" ") { word ->
            if (word.equals("chatgpt", true)) "ChatGPT"
            else word.lowercase().replaceFirstChar { it.uppercase() }
        }
        // The plan string comes straight from the API; cap it so an unexpectedly long
        // value cannot push the chip past the edge of the card.
        return if (pretty.length <= 18) pretty else pretty.take(17) + "…"
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
        val gemini = ctx.getColor(R.color.gemini)
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

        fun strokeRR(x: Float, y: Float, w: Float, h: Float, radius: Float, color: Int, width: Float) {
            if (w <= 0f || h <= 0f) return
            r.set(x, y, x + w, y + h)
            p.style = Paint.Style.STROKE
            p.shader = null
            p.color = color
            p.strokeWidth = width
            val rad = min(radius, min(w, h) / 2f)
            c.drawRoundRect(r, rad, rad, p)
            p.style = Paint.Style.FILL
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
