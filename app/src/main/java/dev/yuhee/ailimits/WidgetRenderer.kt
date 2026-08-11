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
import java.util.Calendar
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
        val opacity: Int = 100,
        val projection: Boolean = true,
        val sparkline: Boolean = true,
        val tokens: Boolean = true,
        val pace: Boolean = true,
        /**
         * Give every selected limit its own panel instead of one panel per provider.
         * Off by default: turning it on for an existing widget would silently split
         * one row into three or four.
         */
        val perWindow: Boolean = false,
        val hiddenClaude: Set<String> = emptySet(),
        val hiddenCodex: Set<String> = emptySet(),
    ) {
        val shown: Int get() =
            (if (showClaude) 1 else 0) + (if (showCodex) 1 else 0)
        val solo: Boolean get() = shown == 1
    }

    fun optsFrom(ctx: Context) = Opts(
        showClaude = Settings.showClaude(ctx),
        showCodex = Settings.showCodex(ctx),
        opacity = Settings.opacity(ctx),
        projection = Settings.showProjection(ctx),
        sparkline = Settings.showSparkline(ctx),
        tokens = Settings.showTokens(ctx),
        pace = Settings.showPace(ctx),
        perWindow = Settings.perWindow(ctx),
        hiddenClaude = Settings.hiddenWindows(ctx, "cl"),
        hiddenCodex = Settings.hiddenWindows(ctx, "cx"),
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
        PickWidgetProvider::class.java,
        HorizonWidgetProvider::class.java,
        RunwayWidgetProvider::class.java,
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
        val global = optsFrom(ctx)
        val live = mutableSetOf<Int>()
        // Sizes and now settings differ per instance, so each widget is rendered on its own.
        providers.forEach { cls ->
            ids(ctx, cls).forEach { id ->
                live.add(id)
                if (onlyId != null && id != onlyId) return@forEach
                try {
                    val cfg = WidgetConfigStore.load(ctx, id)
                    val opts = optsFor(cfg, global)
                    mgr.updateAppWidget(id, build(ctx, mgr, id, cls, cfg, snap, hist, theme, opts, refreshing))
                } catch (e: Throwable) {
                    // One bad widget must not stop the others, but a silent failure
                    // leaves a blank widget with no way to find out why.
                    Log.e("Auspex", "widget $id (${cls.simpleName}) failed to render", e)
                }
            }
        }
        // Only a full pass has seen every live id; reaping from a single-widget redraw
        // would delete every other widget's configuration.
        if (onlyId == null) WidgetConfigStore.reap(ctx, live)
    }

    /**
     * Three-tier read: this widget's own choice, else the app-wide setting, else the code
     * default. A widget with no record of its own returns [global] *identically* — that is
     * what keeps every widget placed before this feature existed rendering as it did.
     */
    internal fun optsFor(cfg: WidgetConfig?, global: Opts): Opts {
        if (cfg == null) return global
        val merged = global.copy(
            showClaude = cfg.showClaude ?: global.showClaude,
            showCodex = cfg.showCodex ?: global.showCodex,
            opacity = cfg.opacity ?: global.opacity,
            projection = cfg.projection ?: global.projection,
            sparkline = cfg.sparkline ?: global.sparkline,
            tokens = cfg.tokens ?: global.tokens,
            pace = cfg.pace ?: global.pace,
            perWindow = cfg.perWindow ?: global.perWindow,
            hiddenClaude = cfg.hiddenClaude ?: global.hiddenClaude,
            hiddenCodex = cfg.hiddenCodex ?: global.hiddenCodex,
        )
        // Settings.setShown enforces this globally; a per-instance record has to be held
        // to the same rule, since a widget showing no providers at all just reads broken.
        return if (merged.shown == 0) global else merged
    }

    internal fun optsFor(ctx: Context, id: Int): Opts =
        optsFor(WidgetConfigStore.load(ctx, id), optsFrom(ctx))

    private fun build(
        ctx: Context,
        mgr: AppWidgetManager,
        id: Int,
        cls: Class<*>,
        cfg: WidgetConfig?,
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

        // The receiver decides the style only until the user overrides it for this instance.
        val style = cfg?.style ?: defaultStyleFor(cls)
        return compose(ctx, style, wDp, hDp, snap, hist, t, o, refreshing)
    }

    /**
     * The style a placed widget would draw with no override — i.e. the one implied by the
     * receiver the user picked in the launcher. Resolved by simple name because that is
     * all [AppWidgetProviderInfo] carries.
     */
    fun defaultStyleForWidget(ctx: Context, id: Int): Style {
        val name = AppWidgetManager.getInstance(ctx).getAppWidgetInfo(id)?.provider?.className
        val cls = providers.firstOrNull { it.name == name } ?: return Style.DETAIL
        return defaultStyleFor(cls)
    }

    /** dp size the launcher currently gives a widget, with the same fallbacks [build] uses. */
    fun sizeOf(ctx: Context, id: Int): Pair<Float, Float> {
        val box = AppWidgetManager.getInstance(ctx).getAppWidgetOptions(id)
        val w = box?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0)?.takeIf { it > 0 }?.toFloat() ?: 250f
        val h = box?.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0)?.takeIf { it > 0 }?.toFloat() ?: 120f
        return w to h
    }

    /** Every placed widget of ours, newest provider order. */
    fun allWidgetIds(ctx: Context): List<Int> = providers.flatMap { ids(ctx, it).toList() }

    /**
     * The receiver that natively draws a style — the inverse of [defaultStyleFor].
     *
     * Used to pin a widget from inside the app. It has to be the *matching* receiver
     * rather than any of them, because the provider XML is what fixes a widget's default
     * and minimum size, and pinning a Ticker through the Detail receiver would give it
     * Detail's 4x3 footprint.
     */
    fun providerClassFor(style: Style): Class<*> =
        providers.firstOrNull { defaultStyleFor(it) == style } ?: UsageWidgetProvider::class.java

    internal fun defaultStyleFor(cls: Class<*>): Style = when (cls) {
        BarsWidgetProvider::class.java -> Style.BARS
        PercentWidgetProvider::class.java -> Style.RINGS
        GraphWidgetProvider::class.java -> Style.GRAPH
        BatteryWidgetProvider::class.java -> Style.BATTERY
        CountdownWidgetProvider::class.java -> Style.COUNTDOWN
        TickerWidgetProvider::class.java -> Style.TICKER
        PickWidgetProvider::class.java -> Style.PICK
        HorizonWidgetProvider::class.java -> Style.HORIZON
        RunwayWidgetProvider::class.java -> Style.RUNWAY
        else -> Style.DETAIL
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

    /**
     * The human name travels with the constant. It used to live in a parallel list in
     * MainActivity, which is exactly the kind of pairing that goes stale the moment a
     * style is added — as it did.
     */
    enum class Style(val label: String) {
        DETAIL("Detail"),
        BARS("Slim bars"),
        RINGS("Rings"),
        GRAPH("History"),
        BATTERY("Battery"),
        COUNTDOWN("Countdown"),
        TICKER("Ticker"),
        PICK("Pick"),
        HORIZON("Horizon"),
        RUNWAY("Runway"),
    }

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
        val density = ctx.resources.displayMetrics.density
        val (pxW, pxH) = bitmapSize(density, wDp, hDp)
        val bmp = Bitmap.createBitmap(pxW, pxH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        // Scale from the pixels actually allocated, so a clamped bitmap still maps the
        // full dp canvas rather than cropping it.
        canvas.scale(pxW / wDp.coerceAtLeast(1f), pxH / hDp.coerceAtLeast(1f))
        val pen = Pen(canvas, t)

        // The card is painted once, here, at the widget's true size — the styles below are
        // handed a shorter canvas so the stamp has a strip of its own, and their own card()
        // calls no-op. Below this height there is no room for a second line of type, so the
        // style keeps the whole widget and the stamp is skipped.
        // 56dp is the narrowest any style declares (Pick, at one cell). At that width only
        // the age survives the measure below — a reset duration cannot be set legibly in
        // what is left — but the age is the half that matters most, so it still runs.
        val stampFits = hDp >= 36f && wDp >= 56f
        pen.card(wDp, hDp, o.opacity)
        val bodyH = if (stampFits) hDp - stampH(hDp) else hDp

        when (style) {
            Style.BARS -> drawBars(pen, wDp, bodyH, snap, t, o)
            Style.RINGS -> drawRings(pen, wDp, bodyH, snap, t, o)
            Style.GRAPH -> drawGraph(pen, wDp, bodyH, snap, hist, t, o)
            Style.BATTERY -> drawBattery(pen, wDp, bodyH, snap, t, o)
            Style.COUNTDOWN -> drawCountdown(pen, wDp, bodyH, snap, t, o)
            Style.TICKER -> drawTicker(pen, wDp, bodyH, snap, t, o)
            Style.PICK -> drawPick(pen, wDp, bodyH, snap, t, o)
            Style.HORIZON -> drawHorizon(pen, wDp, bodyH, snap, t, o)
            Style.RUNWAY -> drawRunway(pen, wDp, bodyH, snap, hist, t, o)
            Style.DETAIL -> drawDetail(pen, wDp, bodyH, snap, hist, t, o, refreshing)
        }
        // The stamp is now the sole "Open app" affordance, and it reaches every style
        // rather than only a tall Detail widget.
        val stamped = stampFits && drawStamp(pen, wDp, hDp, snap, t, o, refreshing)
        // A hairline over the stamp, where there is room for structure: it turns the
        // bottom band into a deliberate footer instead of two captions floating near
        // the corners.
        if (stamped && hDp >= 76f) {
            val inset = safeInset(wDp, hDp, 11f)
            pen.line(inset, hDp - stampH(hDp), wDp - inset * 2, t.rule)
        }
        return bmp to stamped
    }

    /**
     * Corner radius of the card.
     *
     * Was a flat 22dp, which is a lot of curve on a 40dp-tall strip: the arc cut into the
     * corners text was being drawn into, so content looked crowded against a shape that
     * was itself too soft. Scaled to the widget now, and capped well below the old value.
     */
    internal fun cardRadius(w: Float, h: Float): Float =
        min(12f, min(w, h) * .14f).coerceAtLeast(6f)

    /**
     * Horizontal inset for anything drawn in the corner band — at least the full corner
     * radius, plus clearance.
     *
     * The previous inset (`radius * .85`) technically cleared the arc, but "technically
     * clear" is what made corner text look cropped: ink ending right where the curve
     * begins reads as a collision even when no pixel overlaps. Insetting past the radius
     * means text always starts on a flat edge.
     */
    internal fun safeInset(w: Float, h: Float, base: Float) =
        max(base, cardRadius(w, h) + 2f)

    /**
     * Height reserved at the bottom for [drawStamp]. Thinner on short widgets, because
     * "even when small" is the point of the stamp — a fixed band would have priced it
     * out of exactly the sizes where a bare percentage is least informative.
     */
    private fun stampH(h: Float) = if (h < 64f) 12f else 16f

    /**
     * How old the numbers are, as one short token: "now", "4m", "2h", "3d".
     *
     * A negative age means the clock moved back, not that the data is fresh — reporting
     * that as "now" is how a staleness check gets inverted, which has happened here before.
     */
    internal fun age(fetchedAt: Long, now: Long = System.currentTimeMillis()): String {
        if (fetchedAt <= 0L) return "never"
        val ms = now - fetchedAt
        if (ms < 0L) return "?"
        val m = ms / 60_000L
        return when {
            m < 1 -> "now"
            m < 60 -> "${m}m"
            m < 60 * 24 -> "${m / 60}h"
            else -> "${m / (60 * 24)}d"
        }
    }

    /**
     * The one line every style carries, however small: when the tightest limit comes back,
     * and how old these numbers are.
     *
     * Both facts used to be reachable only in the styles that happened to have room for
     * them — a percentage with no reset time and no age is a number the user cannot act on
     * or trust. Each half is measured and dropped independently, so a narrow widget loses
     * the reset wording before it loses the age, and never overprints.
     *
     * @return true when anything was drawn, which is also the "Open app" hit region's cue.
     */
    private fun drawStamp(
        g: Pen, w: Float, h: Float, snap: Snapshot, t: Theme, o: Opts, refreshing: Boolean,
    ): Boolean {
        val now = System.currentTimeMillis()
        val panels = visible(snap, emptyList(), t, o)
        val soonest = panels.mapNotNull { p ->
            binding(p.state)?.resetsAt?.takeIf { it > now }
        }.minOrNull()

        val tight = h < 64f
        val pad = safeInset(w, h, if (tight) 9f else 11f)
        val avail = w - pad * 2
        if (avail < 26f) return false
        // Baseline lifted off the bottom edge: descenders used to reach within ~3dp of
        // the border, which is inside the corner band and read as cropped even when the
        // arc itself was cleared horizontally.
        val baseY = h - (if (tight) 5.5f else 7.5f)
        val size = if (tight) 8.5f else 9.5f

        // Right: how old the data is. Drawn first because it is the half that must never
        // be dropped — a stale number that looks current is worse than no reset time.
        val ageText = if (refreshing) "updating…" else "⟳ " + age(snap.fetchedAt, now)
        val stale = isStale(
            // Any configured provider being stale is enough to colour the stamp.
            panels.firstOrNull { isStale(it.state, now, t.staleAfterMs) }?.state
                ?: ProviderState(false, emptyList(), null),
            now, t.staleAfterMs,
        )
        val ageW = g.measure(ageText, size, 600)
        if (ageW > avail) return false
        g.text(ageText, w - pad, baseY, size, 600, if (stale) t.warn else t.faint, Paint.Align.RIGHT)

        // Left: when capacity returns. The long form first, then the bare duration.
        if (soonest != null) {
            val room = avail - ageW - 8f
            val full = "resets " + left(soonest)
            val bare = left(soonest)
            val text = when {
                g.measure(full, size, 500) <= room -> full
                g.measure(bare, size, 500) <= room -> bare
                else -> null
            }
            if (text != null) g.text(text, pad, baseY, size, 500, t.faint)
        }
        return true
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
        fun emit(state: ProviderState, hidden: Set<String>, name: String, color: Int, series: List<Pair<Long, Int>>) {
            val filtered = filterWindows(state, hidden)
            // History tracks the *unfiltered* binding window. If hiding windows changed
            // which one leads, that series describes a different window — so everything
            // derived from it (projection, burn rate, sparkline) is silently dropped by
            // handing the panel no history at all, rather than fabricating a trend.
            val heroLabel = binding(state)?.label

            // A panel per LIMIT rather than per provider. Without this a widget can never
            // put Claude's 5-hour and Claude's weekly side by side: the provider is one
            // panel and only its fullest window is the headline, so the two limits a user
            // most wants to compare are exactly the two that could not both be shown.
            if (o.perWindow && filtered.windows.size > 1) {
                filtered.windows.sortedByDescending { it.pct }.forEach { win ->
                    add(
                        Panel(
                            filtered.copy(windows = listOf(win)),
                            "$name ${win.label}",
                            name,
                            color,
                            // Only the window the history actually tracked may claim it.
                            if (win.label == heroLabel) series else emptyList(),
                        )
                    )
                }
                return
            }

            // When a widget is down to one window, its heading names that window —
            // "Claude 5h", not "Claude". A widget pinned to a single limit is the whole
            // point of the per-widget setup, and "Claude 68%" beside another widget also
            // reading "Claude 31%" is unreadable without saying which limit each one is.
            val only = filtered.windows.singleOrNull()
            val heading = if (only != null) "$name ${only.label}" else name
            val sameHero = binding(filtered)?.label == heroLabel
            add(Panel(filtered, heading, name, color, if (sameHero) series else emptyList()))
        }
        if (o.showClaude) emit(snap.claude, o.hiddenClaude, "Claude", t.claude, hist.map { it.t to it.claude })
        if (o.showCodex) emit(snap.codex, o.hiddenCodex, "Codex", t.codex, hist.map { it.t to it.codex })
    }

    private class Panel(
        val state: ProviderState,
        /** Heading to draw: "Claude 5h" when this panel is down to one window, else "Claude". */
        val name: String,
        /** The provider alone, for the few places too narrow to carry the qualified form. */
        val provider: String,
        val color: Int,
        val series: List<Pair<Long, Int>>,
    )

    /**
     * Pixel budget for one widget bitmap — 400k px ≈ 1.6 MB at 4 bytes per pixel.
     *
     * Deliberately not justified by the Binder transaction limit: that limit is 1 MB per
     * process and a bitmap this size exceeds it, so if it applied nothing here would ever
     * have worked. Large bitmaps are passed out-of-band through ashmem instead, which is
     * why it does. The budget exists to bound allocation — several placed widgets are
     * redrawn together on every refresh — and 1.6 MB is the figure v2.6 shipped and ran
     * on a real device, rather than a number reasoned up from a comment.
     */
    internal const val PIXEL_BUDGET = 400_000f

    /**
     * Keeps the bitmap crisp but inside [PIXEL_BUDGET]. Solved rather than stepped: the
     * old loop bottomed out at 1.25 and silently blew the budget on very large widgets,
     * so the guarantee held only as long as the declared max size happened to stay small.
     */
    internal fun scaleFor(density: Float, wDp: Float, hDp: Float): Float {
        val area = (wDp * hDp).coerceAtLeast(1f)
        val fits = sqrt(PIXEL_BUDGET / area)
        // No 1.0 floor. It looked like a legibility guarantee but was really an escape
        // hatch: past 400,000 dp² the floor won and the bitmap grew unbounded with the
        // widget. maxResizeWidth/Height are API 31, so on API 26-30 the launcher may hand
        // out any size at all, and a 1000x600dp widget allocated 2.3 MB.
        return min(density.coerceIn(1.5f, 3f), fits).coerceAtLeast(0.5f)
    }

    /**
     * Bitmap pixel dimensions for a widget, clamped so the product honours the budget.
     * Rounding each axis independently could overshoot it by a few hundred pixels, and
     * a scale that has bottomed out cannot be relied on to hold the product down.
     */
    internal fun bitmapSize(density: Float, wDp: Float, hDp: Float): Pair<Int, Int> {
        val scale = scaleFor(density, wDp, hDp)
        var w = max(1, (wDp * scale).roundToInt())
        var h = max(1, (hDp * scale).roundToInt())
        val over = (w.toFloat() * h) / PIXEL_BUDGET
        if (over > 1f) {
            val shrink = sqrt(1f / over)
            w = max(1, (w * shrink).toInt())
            h = max(1, (h * shrink).toInt())
        }
        return w to h
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
        if (tier == COMPACT) {
            // With several limits on one widget the dense number strip becomes "68% 31%
            // 12% 41% 22%" — five numbers and nothing saying what any of them is, which
            // defeats the point of choosing those limits. A labelled slim row per limit
            // fits the same height and keeps every one of them identifiable.
            if (n > 2) drawBars(g, w, h, snap, t, o) else drawCompact(g, w, h, panels, t)
            return false
        }

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
        // Reserving the footer only in `free` let the max() floor below hand the space
        // back, so the refresh icon could be drawn over the last block's meta line.
        var free = max(0f, slack - if (foot) FOOT else 0f)
        val canStretch = tier == RICH || tier == FULL
        // Capped tighter than before: a tall widget should spend its slack on the
        // blocks (stats line, taller sparkline) rather than on air between rows, and 20dp
        // gaps read as the widget being half empty.
        val gap = min(13f, max(MIN_GAP, free / max(1, n + 1)))
        free = max(0f, free - gap * (n - 1))
        val stretch = if (canStretch) min(96f, max(0f, free / max(1, n))) else 0f
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

        // The timestamp and the "Open app" region moved to the universal stamp, which every
        // style now carries; drawing a second one here would just say it twice. The stale
        // dot stays, because it is per-provider and the stamp's is whole-widget.
        staleDot(g, w, t, snap)
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

        g.circle(x + 4f, pad + 6f, 4f, dotColor(p.state, p.color, t))
        var cx = x + 13f
        cx += g.text(p.name, cx, pad + 10.5f, 13f, 700, nameColor(p.state, p.color, t), tracking = .035f) + 7f
        val plan = if (p.state.configured) prettyPlan(p.state.plan) else null
        if (plan != null && cx + g.chipWidth(plan) <= x + cw) cx += g.chip(plan, cx, pad - 1f, p.color) + 7f

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
        // Sized against the card, not a single threshold: at the declared 48dp minimum
        // the fixed hero put its own baseline below the bottom edge.
        val tight = h < 140f
        val hy = pad + if (tight) 15f else 20f
        val heroSize = min(if (tight) 33f else 42f, max(15f, (h - hy - pad) * 0.55f))
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

        g.circle(x + 4f, y + 6.5f, 3.8f, dotColor(st, color, t))
        var cx = x + 13f
        cx += g.text(name, cx, y + 11f, 13f, 700, nameColor(st, color, t), tracking = .035f) + 7f
        val plan = if (st.configured) prettyPlan(st.plan) else null
        // A chip drawn past the card edge is worse than no chip.
        if (plan != null && cx + g.chipWidth(plan) <= x + w) cx += g.chip(plan, cx, y - 0.5f, color) + 7f

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
        // Under this the percentage no longer fits its column, and suppressing it left
        // only dots and bars — a widget with no numbers on it. Dense fits the figures.
        if (colW < 34f) { drawDense(g, w, h, panels, t); return }
        // Below this there is no room for a name and a number on the same line.
        val roomy = colW >= 96f
        val single = h >= 60f

        panels.forEachIndexed { i, p ->
            val x = pad + i * (colW + gap)
            val b = binding(p.state)
            val sc = if (b != null) t.status(b.pct, p.color) else t.faint
            val cy = h / 2f
            val nameCol = nameColor(p.state, p.color, t)
            if (roomy) {
                g.circle(x + 4f, cy - 11f, 3.5f, dotColor(p.state, p.color, t))
                g.text(p.name, x + 13f, cy - 7f, 11f, 700, nameCol, tracking = .03f)
                val numW = g.text(if (b != null) "${b.pct}%" else "--", x + colW, cy - 7f, 14f, 700, sc, Paint.Align.RIGHT)
                if (b != null && colW >= 128f) {
                    g.text("USED", x + colW - numW - 5f, cy - 7f, 8f, 700, t.dim, Paint.Align.RIGHT, .06f)
                }
            } else {
                // Too narrow for both: the number is what matters, keep only a colour cue.
                g.circle(x + 4f, cy - 10f, 3.5f, dotColor(p.state, p.color, t))
                g.fitText(if (b != null) "${b.pct}%" else "--", x + colW, cy - 6f, 14f, 700, sc,
                    colW - 10f, Paint.Align.RIGHT)
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
                g.fitText(sub, x, cy + 20f, 10f, 500, t.faint, colW)
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
        // Decided across all the rings, not per ring: the per-column fit check kept
        // whichever caption happened to fit and dropped its neighbours, which reads as
        // a glitch rather than as a deliberate simplification.
        val captionsFit = panels.all {
            g.measure(it.name.uppercase(), 10.5f, 700, .05f) <= w / n - 8f
        }
        // Lowered when the universal stamp took a strip off the bottom: these
        // thresholds were tuned against the full widget height, so leaving them
        // alone silently priced the heading out of the small sizes. The heading is
        // what says WHICH limit a widget is about, so it outranks the sub-line —
        // and the reset it used to carry is now in the stamp anyway.
        val showLabel = h >= 54f && captionsFit
        val showSub = h >= 98f && captionsFit
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
            // Rings had no staleness cue at all; the caption carries it, as in Battery.
            if (showLabel) {
                g.fitText(name, cx, cy + r + 13f, 10.5f, 700, nameColor(st, color, t),
                    w / n - 8f, Paint.Align.CENTER, .05f)
            }
            if (showSub) {
                val sub = when {
                    b != null -> left(b.resetsAt) + " left"
                    !p.state.configured -> "tap to sign in"
                    else -> "no data yet"
                }
                g.fitText(sub, cx, cy + r + 26f, 10f, 500, t.faint, w / n - 8f, Paint.Align.CENTER)
            }
        }
    }

    // --- slim bars --------------------------------------------------------

    private fun drawBars(g: Pen, w: Float, h: Float, snap: Snapshot, t: Theme, o: Opts) {
        g.card(w, h, o.opacity)
        val pad = 14f
        val cw = w - pad * 2
        val panels = visible(snap, hist = emptyList(), t = t, o = o)
        // Rows are sized from the height actually available. A fixed pitch put the first
        // row above the top edge at the declared 40dp minimum with three providers.
        val rowH = ((h - 8f) / panels.size).coerceIn(13f, 26f)
        val top = max(3f, h / 2f - rowH * panels.size / 2f) + (rowH - 26f) / 2f

        // Columns are budgeted from the width actually available rather than fixed
        // offsets, which used to go negative and push text past the right edge.
        // A single-panel widget is usually one pinned to one limit, and its whole point
        // is the heading — so it gets a wider name column and a shorter bar, rather than
        // dropping the name and leaving "68%" with nothing saying what is at 68%.
        val nameW = min(if (panels.size == 1) 96f else 62f, max(0f, cw * (if (panels.size == 1) .50f else .26f)))
        val showName = nameW >= 38f
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
            g.circle(pad + 3.5f, y + 6.5f, 3.4f, dotColor(p.state, p.color, t))
            if (showName) {
                // Bounded by where the bar starts, not merely by the column reservation.
                // minSize matters here: the column reservation and the text budget are
                // computed differently, so a name that "fits the column" could still be
                // refused by fitText and vanish entirely rather than merely shrink.
                //
                // The fallbacks run longest-first, and the WINDOW label outranks the bare
                // provider name: with several limits from one provider on screen the dot
                // colour already says which provider, so "Opus" distinguishes a row where
                // "Claude" would not. A row identified only by a coloured dot is the exact
                // failure this widget exists to avoid.
                val budget = barX - pad - 14f
                val candidates = listOfNotNull(
                    p.name,
                    p.state.windows.singleOrNull()?.label,
                    p.provider,
                )
                val label = candidates.firstOrNull {
                    g.measure(it, 8f, 700, .03f) <= budget
                } ?: candidates.last()
                g.fitText(label, pad + 12f, y + 11f, 11f, 700,
                    nameColor(p.state, p.color, t), budget, tracking = .03f, minSize = 7.5f)
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
    private fun drawDense(
        g: Pen, w: Float, h: Float, panels: List<Panel>, t: Theme, showRemaining: Boolean = false,
    ) {
        data class Seg(
            val pct: String, val color: Int, val state: ProviderState, val base: Int,
            /** "5h", "7d"… — which limit this number belongs to, when it fits. */
            val tag: String,
        )
        val segs = panels.map { p ->
            val b = binding(p.state)
            // showRemaining exists so Battery's fallback keeps Battery's meaning. Without
            // it the same widget showed 65% (left) and, once dragged narrower, 35% (used)
            // with no label change either way.
            val value = when {
                b == null -> "--"
                showRemaining -> "${100 - b.pct}%"
                else -> "${b.pct}%"
            }
            // Only when this panel IS one limit. With several limits on one widget the
            // colour-coded dot says which provider but nothing says which window, and a
            // row of bare percentages is unreadable.
            val tag = p.state.windows.singleOrNull()?.label.orEmpty()
            Seg(value, if (b != null) t.status(b.pct, p.color) else t.faint, p.state, p.color, tag)
        }

        var shownCount = segs.size
        // Tags are all-or-nothing: half-labelled numbers read worse than none.
        var tags = segs.any { it.tag.isNotEmpty() }
        fun segText(s: Seg) = if (tags && s.tag.isNotEmpty()) "${s.tag} ${s.pct}" else s.pct
        fun layout(size: Float, gap: Float, dot: Float): Float =
            segs.take(shownCount).sumOf { (g.measure(segText(it), size, 700) + dot * 2 + 3f).toDouble() }
                .toFloat() + gap * (shownCount - 1)

        // Shrink, then tighten the gap, before giving up and using the smallest.
        var size = 13f
        var gap = 9f
        var dot = 3.2f
        while (size > 8f && layout(size, gap, dot) > w - 10f) {
            size -= .5f
            gap = max(3f, gap - .4f)
            dot = max(1.8f, dot - .12f)
        }

        // Drop the tags before dropping whole limits: a number without its label still
        // shows that the limit exists, whereas removing the segment hides it entirely.
        if (tags && layout(size, gap, dot) > w - 6f) {
            tags = false
            size = 13f; gap = 9f; dot = 3.2f
            while (size > 8f && layout(size, gap, dot) > w - 10f) {
                size -= .5f
                gap = max(3f, gap - .4f)
                dot = max(1.8f, dot - .12f)
            }
        }
        // The loop above gives up at its floor whether or not the result fits, so the
        // last resort is dropping segments rather than painting past both edges.
        while (shownCount > 1 && layout(size, gap, dot) > w - 6f) shownCount--
        val segs2 = segs.take(shownCount)
        var x = (w - layout(size, gap, dot)) / 2f
        val baseY = h / 2f + size * .36f
        segs2.forEach { sg ->
            g.circle(x + dot, baseY - size * .32f, dot, dotColor(sg.state, sg.base, t))
            x += dot * 2 + 3f
            x += g.text(segText(sg), x, baseY, size, 700, sg.color) + gap
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
        if (colW < 46f) { drawDense(g, w, h, panels, t, showRemaining = true); return }
        // Lowered when the universal stamp took a strip off the bottom: these
        // thresholds were tuned against the full widget height, so leaving them
        // alone silently priced the heading out of the small sizes. The heading is
        // what says WHICH limit a widget is about, so it outranks the sub-line —
        // and the reset it used to carry is now in the stamp anyway.
        val showName = h >= 56f
        val showReset = h >= 92f
        val bodyH = min(34f, h * .36f).coerceAtLeast(18f)
        // Clamped to the column, never merely coerced up past it.
        // coerceIn throws when its bounds invert, so the upper bound is floored rather
        // than trusted: it is derived from the column width, and the only thing keeping
        // it above the lower bound today is the bail-out above. A blank widget from an
        // IllegalArgumentException is not a failure mode worth leaving to a constant.
        val maxBody = max(28f, colW - 10f)
        val bodyW = min(colW - 12f, bodyH * 2.3f).coerceAtLeast(28f).coerceAtMost(maxBody)
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
                // The figure inside the body is what is LEFT, so the caption says so —
                // every other style reports percent USED, and an unlabelled number in a
                // battery is the one place the two could be confused.
                val caption = if (b != null) "${p.name} · left" else p.name
                val cs = listOf(caption, p.name, p.provider)
                    .firstOrNull { g.measure(it, 10.5f, 700, .03f) <= colW - 6f } ?: p.provider
                g.text(cs, cx, topY + bodyH + 14f, 10.5f, 700,
                    nameColor(p.state, p.color, t), Paint.Align.CENTER, .03f)
            }
            if (showReset) {
                // A count belongs to one window, so it is named — Battery is otherwise the
                // only style that shows a figure without saying which window it describes.
                val tokens = if (o.tokens && b != null) remainingText(b) else null
                val named = if (tokens != null && b != null) "${b.label} · $tokens" else null
                val sub = when {
                    b == null -> "tap to sign in"
                    named != null && g.measure(named, 9.5f, 500) <= colW - 8f -> named
                    tokens != null && g.measure(tokens, 9.5f, 500) <= colW - 8f -> tokens
                    else -> "resets " + left(b.resetsAt)
                }
                g.fitText(sub, cx, topY + bodyH + 28f, 9.5f, 500, t.faint, colW - 6f, Paint.Align.CENTER)
            }
        }
    }

    // --- countdown --------------------------------------------------------
    // Time-first: the headline is when you get your capacity back, not how much
    // of it is gone. The thin bar underneath is how far through the window you are.

    /** Wall-clock length of a window, inferred from its label; null when unknowable. */
    internal fun windowLengthMs(label: String): Long? {
        Regex("^(\\d+)([mhd])$").find(label.lowercase())?.let { m ->
            val v = m.groupValues[1].toLong()
            val unit = when (m.groupValues[2]) {
                "m" -> 60_000L
                "h" -> 3600_000L
                else -> 86_400_000L
            }
            // A zero span is not a span. Returning 0 here is what produced NaN downstream.
            return (v * unit).takeIf { it > 0L }
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
        // Lowered when the universal stamp took a strip off the bottom: these
        // thresholds were tuned against the full widget height, so leaving them
        // alone silently priced the heading out of the small sizes. The heading is
        // what says WHICH limit a widget is about, so it outranks the sub-line —
        // and the reset it used to carry is now in the stamp anyway.
        val showName = h >= 50f
        val showSub = h >= 88f
        val big = min(colW * .22f, 27f).coerceAtLeast(14f)
        val block = (if (showName) 16f else 0f) + big + (if (showSub) 15f else 0f) + 10f
        val topY = (h - block) / 2f
        val now = System.currentTimeMillis()

        panels.forEachIndexed { i, p ->
            val cx = pad + colW * i + colW / 2f
            val b = binding(p.state)
            var y = topY
            if (showName) {
                val head = listOf(p.name, p.provider)
                    .firstOrNull { g.measure(it, 10.5f, 700, .03f) + 13f <= colW - 4f } ?: p.provider
                g.circle(cx - g.measure(head, 10.5f, 700, .03f) / 2f - 9f, y + 6.5f, 3.4f,
                    dotColor(p.state, p.color, t))
                g.fitText(head, cx, y + 10.5f, 10.5f, 700,
                    nameColor(p.state, p.color, t), colW - 4f, Paint.Align.CENTER, .03f)
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
                // Was drawn unmeasured and collided with the next column at the
                // declared minimum width; the shorter form is tried before giving up.
                val full = "${b.pct}% · " + shortWindow(b.label)
                if (g.fitText(full, cx, y + 9f, 10f, 500, t.dim, colW - 8f, Paint.Align.CENTER) == 0f) {
                    g.fitText("${b.pct}%", cx, y + 9f, 10f, 500, t.dim, colW - 8f, Paint.Align.CENTER)
                }
                y += 15f
            }
            // Elapsed-through-the-window bar, only when the label tells us its length.
            val len = (b.lengthMs ?: windowLengthMs(b.label))?.takeIf { it > 0L }
            if (len != null && b.resetsAt > now) {
                val remainMs = (b.resetsAt - now).coerceAtMost(len)
                val elapsed = (1f - remainMs.toFloat() / len.toFloat()).coerceIn(0f, 1f)
                if (elapsed.isFinite()) {
                    val bw = (colW - 28f).coerceAtLeast(26f)
                    g.bar(cx - bw / 2f, y + 2f, bw, 5f, (elapsed * 100).toInt(), t.status(b.pct, p.color))
                }
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

        class Seg(
            val name: String,
            val pct: String,
            val color: Int,
            val state: ProviderState,
            val base: Int,
        )
        val segs = panels.map { p ->
            val b = binding(p.state)
            Seg(
                p.name,
                if (b != null) "${b.pct}%" else "--",
                if (b != null) t.status(b.pct, p.color) else t.faint,
                p.state,
                p.color,
            )
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
            g.circle(x + 3.4f, y1 - 4f, 3.4f, dotColor(sg.state, sg.base, t))
            x += 10f
            if (withNames) {
                x += g.text(sg.name, x, y1, 11.5f, 600, if (sg.state.configured) t.dim else t.faint, tracking = .01f) + 5f
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
                val full = "next reset " + left(next.second.resetsAt) + " · " + next.first
                if (g.fitText(full, w / 2f, y1 + 18f, 9.5f, 500, t.faint, w - 12f, Paint.Align.CENTER) == 0f) {
                    g.fitText("next " + left(next.second.resetsAt), w / 2f, y1 + 18f, 9.5f, 500,
                        t.faint, w - 12f, Paint.Align.CENTER)
                }
            }
        }
    }

    // --- 24h history ------------------------------------------------------

    // --- pick -------------------------------------------------------------
    // One verdict, no comparison. Every other style answers "how much is left";
    // this answers "which one should I use right now", in the largest type that fits,
    // and it needs no history — so it is the only style that is fully populated one
    // fetch after signing in, and the only one that reads at a 1x1 size.

    /** A duration for an axis or a headline: one unit, no minutes past an hour. */
    internal fun brief(ms: Long): String {
        if (ms <= 0) return "now"
        val m = ms / 60_000L
        return when {
            m < 60 -> "${m}m"
            m < 60 * 24 -> "${m / 60}h"
            else -> "${m / (60 * 24)}d"
        }
    }

    private fun drawPick(g: Pen, w: Float, h: Float, snap: Snapshot, t: Theme, o: Opts) {
        g.card(w, h, o.opacity)
        val panels = visible(snap, hist = emptyList(), t = t, o = o)
        if (panels.isEmpty()) return
        val pad = min(12f, w * .1f)
        val avail = w - pad * 2
        val now = System.currentTimeMillis()

        // Only a signed-in provider that reported a window can be recommended.
        val ranked = panels.mapNotNull { p -> binding(p.state)?.let { p to it } }
            .sortedByDescending { 100 - it.second.pct }
        if (ranked.isEmpty()) {
            val msg = if (panels.any { it.state.configured }) "no data yet" else "tap to sign in"
            g.fitText(msg, w / 2f, h / 2f + 4f, 13f, 600, t.faint, avail, Paint.Align.CENTER)
            return
        }

        val (best, bestWin) = ranked.first()
        val free = (100 - bestWin.pct).coerceIn(0, 100)
        // Under this there is no useful recommendation left to make, so the widget stops
        // pointing somewhere and starts saying how long the wait is.
        val tight = free < 12
        val soonest = ranked.mapNotNull { it.second.resetsAt.takeIf { r -> r > now } }.minOrNull()

        var hero: String
        val heroColor: Int
        var caption: String
        val sub: String?
        // Whether the hero is a percentage — and therefore needs saying WHICH percentage.
        val heroIsPercent: Boolean
        if (tight) {
            heroIsPercent = soonest == null
            hero = if (soonest != null) brief(soonest - now) else "$free%"
            heroColor = if (free < 5) t.red else t.warn
            caption = "all tight"
            sub = if (soonest != null) "until ${ranked.first { it.second.resetsAt == soonest }.first.name} resets"
                  else "${best.name} has the most left"
        } else {
            heroIsPercent = true
            hero = "$free%"
            heroColor = t.status(bestWin.pct, best.color)
            caption = best.name
            sub = "free · " + shortWindow(bestWin.label)
        }

        // Lowered when the universal stamp took a strip off the bottom: these
        // thresholds were tuned against the full widget height, so leaving them
        // alone silently priced the heading out of the small sizes. The heading is
        // what says WHICH limit a widget is about, so it outranks the sub-line —
        // and the reset it used to carry is now in the stamp anyway.
        val showCaption = h >= 48f
        val showSub = h >= 88f && sub != null

        // This number is what is LEFT; every other style in the app reports what is USED.
        // An unqualified "59%" next to a Ticker reading "41%" says the opposite of the
        // truth, so the word "free" is attached to the lowest line actually being drawn —
        // exactly once, never twice.
        if (heroIsPercent && !showSub) {
            val qualified = "$caption · free"
            // Measured before it is chosen: fitText draws nothing at all when a string
            // cannot be made to fit, which would drop the qualifier silently and put the
            // ambiguity straight back.
            if (showCaption && g.measure(qualified, 11f, 700, .04f) <= avail) caption = qualified
            else hero = "$free% free"
        }

        // The hero is sized to the space actually left after the lines that will be drawn,
        // then measured down until it fits the width — never drawn on an assumed size.
        val reserved = (if (showCaption) 17f else 0f) + (if (showSub) 15f else 0f)
        var heroSize = min((h - reserved - 14f) * .78f, avail * .46f).coerceIn(16f, 62f)
        while (heroSize > 14f && g.measure(hero, heroSize, 700, -.02f) > avail) heroSize -= 1f

        val block = (if (showCaption) 17f else 0f) + heroSize + (if (showSub) 15f else 0f)
        var y = (h - block) / 2f

        if (showCaption) {
            val dotR = 3.4f
            val capW = g.measure(caption, 11f, 700, .04f)
            val cx = w / 2f + (if (tight) 0f else dotR + 5f) / 2f
            if (!tight && capW + dotR * 2 + 10f <= avail) {
                g.circle(cx - capW / 2f - 8f, y + 7f, dotR, dotColor(best.state, best.color, t))
            }
            g.fitText(caption, cx, y + 11f, 11f, 700,
                if (tight) t.dim else nameColor(best.state, best.color, t),
                avail, Paint.Align.CENTER, .04f)
            y += 17f
        }
        g.text(hero, w / 2f, y + heroSize * .8f, heroSize, 700, heroColor, Paint.Align.CENTER, -.02f)
        y += heroSize
        if (showSub) g.fitText(sub!!, w / 2f, y + 11f, 10.5f, 500, t.faint, avail, Paint.Align.CENTER)
    }

    // --- horizon ----------------------------------------------------------
    // Every upcoming reset of every window on one forward time axis. This is the only
    // style that shows the windows that are NOT binding — Claude's 7d/Opus/Sonnet, a
    // and a Codex secondary — which the rest of the app discards.

    private fun drawHorizon(g: Pen, w: Float, h: Float, snap: Snapshot, t: Theme, o: Opts) {
        g.card(w, h, o.opacity)
        val panels = visible(snap, hist = emptyList(), t = t, o = o)
        if (panels.isEmpty()) return
        val now = System.currentTimeMillis()
        val pad = 12f

        class Ev(val at: Long, val pct: Int, val label: String, val name: String, val color: Int, val state: ProviderState)
        // resetsAt is 0 when the provider didn't report one or it wouldn't parse; such a
        // window has no place on a time axis, so it is dropped rather than pinned to now.
        val all = panels.flatMap { p ->
            p.state.windows.filter { it.resetsAt > now }
                .map { Ev(it.resetsAt, it.pct, it.label, p.name, p.color, p.state) }
        }
        // A provider can report many windows, which would crowd the axis to uselessness;
        // the fullest windows are the ones worth waiting for.
        val events = all.sortedByDescending { it.pct }.take(8).sortedBy { it.at }

        if (events.isEmpty()) {
            val msg = when {
                panels.none { it.state.configured } -> "tap to sign in"
                panels.any { it.state.windows.isNotEmpty() } -> "no resets known"
                else -> "no data yet"
            }
            g.fitText(msg, w / 2f, h / 2f + 4f, 12f, 600, t.faint, w - pad * 2, Paint.Align.CENTER)
            return
        }

        val next = events.first()
        // Too small for an axis: say the one thing that matters.
        if (w < 130f || h < 46f) {
            val line = "${next.name} ${shortWindow(next.label)} · ${brief(next.at - now)}"
            if (g.fitText(line, w / 2f, h / 2f + 4f, 11f, 600, t.text, w - 10f, Paint.Align.CENTER) == 0f) {
                g.fitText(brief(next.at - now), w / 2f, h / 2f + 4f, 13f, 700, t.text, w - 6f, Paint.Align.CENTER)
            }
            return
        }

        // The header is the most informative thing on this widget — it names the next
        // window by name, which the stems alone cannot. It gets the space before the
        // time labels do, not after.
        val showHead = h >= 54f
        var top = pad
        if (showHead) {
            g.fitText("next back", pad, top + 9f, 9.5f, 700, t.faint, (w - pad * 2) * .34f, tracking = .06f)
            // Amber when the numbers behind this headline are old — the same cue every
            // other style carries. Without it this style would quietly present stale
            // resets as current, which is the one thing a reset time must not do.
            val headColor = if (isStale(next.state, staleAfterMs = t.staleAfterMs)) t.warn else t.dim
            g.fitText(
                "${next.name} ${shortWindow(next.label)} in ${brief(next.at - now)}",
                w - pad, top + 9f, 10f, 600, headColor, (w - pad * 2) * .64f, Paint.Align.RIGHT,
            )
            top += 18f
        }

        val labelH = if (h >= 80f) 13f else 0f
        val baseY = h - pad - labelH
        // A very tall widget should not stretch the stems to absurdity — past this the
        // chart stops gaining information and just gets sparse, so it sits on its axis
        // at a readable height instead.
        val plotH = (baseY - top - 6f).coerceIn(10f, 170f)
        val plotX = pad + 2f
        val plotW = (w - pad * 2 - 4f).coerceAtLeast(20f)
        // The axis always spans at least half an hour so a single imminent reset does not
        // sit on top of the origin, and the last event lands just inside the right edge.
        val span = (events.last().at - now).coerceAtLeast(30 * 60_000L)

        g.line(plotX, baseY, plotW, t.rule)

        var lastRight = -1e9f
        events.forEach { e ->
            val f = ((e.at - now).toFloat() / span.toFloat()).coerceIn(0f, 1f)
            // Inset so a stem at f = 1 keeps its dot inside the card.
            val x = plotX + f * (plotW - 6f) + 3f
            val stem = (plotH * (e.pct.coerceIn(0, 100) / 100f)).coerceAtLeast(4f)
            // dotColor carries the not-configured and stale cases; the colour handed to it
            // is the status-adjusted identity colour, so a fresh, healthy window still
            // reads as its provider and a stale one goes amber like everywhere else.
            val color = dotColor(e.state, t.status(e.pct, e.color), t)
            g.rrect(x - 1.25f, baseY - stem, 2.5f, stem, 1.25f, withAlpha(color, 150))
            g.circle(x, baseY - stem, 3.2f, color)

            if (labelH > 0f) {
                val txt = brief(e.at - now)
                val tw = g.measure(txt, 9f, 600)
                // A centred label on the furthest event always overhangs the right edge —
                // its stem sits 3dp in, so centring demanded a label no wider than 6dp and
                // the one event that sets the axis scale was the one that could never be
                // labelled. It is right-aligned to the edge instead of dropped.
                val overhangs = x + tw / 2f > plotX + plotW
                val leftEdge = if (overhangs) plotX + plotW - tw else x - tw / 2f
                // Clustered resets would overprint each other; a label is skipped rather
                // than drawn on top of its neighbour.
                if (leftEdge > lastRight + 4f && leftEdge >= plotX) {
                    if (overhangs) g.text(txt, plotX + plotW, baseY + 11f, 9f, 600, t.faint, Paint.Align.RIGHT)
                    else g.text(txt, x, baseY + 11f, 9f, 600, t.faint, Paint.Align.CENTER)
                    lastRight = leftEdge + tw
                }
            }
        }
    }

    // --- runway -----------------------------------------------------------
    // Do you run out before the window refills? Per-provider lanes on one shared axis:
    // the bar runs to where the current burn projects exhaustion, the notch is the reset.
    // Bar short of the notch means you make it; past it means you stall first.

    internal class Lane(
        val name: String,
        val color: Int,
        val state: ProviderState,
        val win: Win?,
        /** When the current burn projects this window hitting 100%, or null. */
        val dry: Long?,
        /** Whether a projection could have been computed at all — see [canProject]. */
        val measurable: Boolean,
        /** Provider alone, for the lane label column when the qualified name will not fit. */
        val provider: String = name,
    )

    /**
     * Runway's headline, and whether it is an alarm. Split out of the drawing so the
     * decision can be tested: it used to print "all within budget" whenever no lane
     * projected, which reads as a safety verdict but was really the absence of one —
     * loudest exactly when a window sat pinned at 100%, since a flat line has no slope
     * and therefore no projection.
     */
    internal fun runwayHeadline(lanes: List<Lane>, now: Long): Pair<String, Boolean> {
        val worst = lanes.filter { it.dry != null }.minByOrNull { it.dry!! }
        val full = lanes.filter { (it.win?.pct ?: 0) >= 90 }.maxByOrNull { it.win!!.pct }
        val head = when {
            worst?.win != null && worst.win.resetsAt > now ->
                "${worst.name} runs dry ${brief(worst.win.resetsAt - worst.dry!!)} early"
            worst != null -> "${worst.name} runs dry in ${brief(worst.dry!! - now)}"
            full?.win != null && full.win.pct >= 99 -> "${full.name} is out"
            full?.win != null -> "${full.name} at ${full.win.pct}%"
            lanes.none { it.win != null } -> "runway"
            // "No projection" is not "you are safe": projection() also returns null for too
            // few samples, too short a span, and a burn below its noise floor. Asserting
            // budget safety from any of those states claims a conclusion the data cannot
            // support — the same thing the empty track deliberately refuses to do.
            lanes.none { it.measurable } -> "measuring burn…"
            else -> "all within budget"
        }
        return head to (worst != null || full != null)
    }

    private fun drawRunway(
        g: Pen, w: Float, h: Float, snap: Snapshot, hist: List<HistoryPoint>, t: Theme, o: Opts,
    ) {
        g.card(w, h, o.opacity)
        val panels = visible(snap, hist, t, o)
        if (panels.isEmpty()) return
        val now = System.currentTimeMillis()
        val pad = 12f

        val lanes = panels.map { p ->
            val b = binding(p.state)
            Lane(
                p.name, p.color, p.state, b, b?.let { projection(it, p.series) },
                canProject(p.series, now), p.provider,
            )
        }

        // A shared axis is the whole point — the lanes are only comparable against the
        // same clock. Clipped at 12h so a weekly window doesn't squash everything left.
        val horizon = 12 * 3600_000L
        val furthest = lanes.mapNotNull { l ->
            listOfNotNull(l.win?.resetsAt?.takeIf { it > now }, l.dry).maxOrNull()
        }.maxOrNull()
        val span = (furthest?.minus(now) ?: horizon).coerceIn(30 * 60_000L, horizon)

        val n = lanes.size
        val showHead = h >= 78f
        val headH = if (showHead) 16f else 0f
        val room = h - pad * 2 - headH
        // Capped, then the whole stack is centred in what is left: a 480dp-tall widget
        // with two lanes would otherwise put 220dp of empty card between them.
        val laneH = (room / n).coerceIn(14f, 64f)
        if (laneH < 18f || w < 130f) { drawDense(g, w, h, panels, t); return }

        if (showHead) {
            val (head, alarm) = runwayHeadline(lanes, now)
            g.fitText(head, pad, pad + 9f, 10.5f, 700, if (alarm) t.warn else t.faint,
                w - pad * 2, tracking = .02f)
        }
        var y = pad + headH + (room - laneH * n).coerceAtLeast(0f) / 2f

        val labelW = min(52f, w * .26f)
        val axX = pad + labelW
        val axW = (w - pad - axX).coerceAtLeast(24f)

        lanes.forEach { l ->
            val midY = y + laneH / 2f
            val laneName = if (g.measure(l.name, 10.5f, 700) <= labelW - 6f) l.name else l.provider
            g.fitText(laneName, pad, midY + 3.5f, 10.5f, 700,
                nameColor(l.state, l.color, t), labelW - 6f)

            val trackH = min(8f, laneH * .38f).coerceAtLeast(4f)
            val trackY = midY - trackH / 2f
            g.rrect(axX, trackY, axW, trackH, trackH / 2f, t.track)

            val b = l.win
            if (b == null) {
                g.fitText(if (l.state.configured) "no data" else "sign in",
                    axX + 3f, midY + 3.5f, 9.5f, 500, t.faint, axW - 6f)
                y += laneH
                return@forEach
            }

            fun xOf(at: Long): Float =
                axX + ((at - now).toFloat() / span.toFloat()).coerceIn(0f, 1f) * axW

            // Only a projection may be drawn on this track. Filling it by percent-used
            // when no projection exists would put a proportion and a duration on the same
            // axis, at the same scale, distinguishable only by opacity — the lane would
            // read as a runway that isn't one. With no projection the track stays empty
            // and the note carries the number instead.
            if (l.dry != null) {
                val end = xOf(l.dry)
                if (end > axX + 1f) {
                    g.rrect(axX, trackY, end - axX, trackH, trackH / 2f, t.status(b.pct, l.color))
                }
            }

            // The reset notch: where capacity comes back.
            if (b.resetsAt > now) {
                val rx = xOf(b.resetsAt).coerceAtMost(axX + axW - 1.5f)
                g.rrect(rx - 1f, trackY - 3f, 2f, trackH + 6f, 1f, t.text)
            }

            if (laneH >= 30f) {
                val note = when {
                    l.dry != null && b.resetsAt > now ->
                        "dry in ${brief(l.dry - now)} · ${brief(b.resetsAt - l.dry)} short"
                    l.dry != null -> "dry in ${brief(l.dry - now)}"
                    b.resetsAt > now -> "${b.pct}% · back in ${brief(b.resetsAt - now)}"
                    else -> "${b.pct}% used"
                }
                g.fitText(note, axX, midY + trackH / 2f + 12f, 9.5f, 500, t.faint, axW)
            }
            y += laneH
        }
    }

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
            // The legend identifies the lines; the title merely names the chart. When
            // they compete for the row the title gives way first, and only then does the
            // legend start shortening. Dropping a legend entry leaves a line unlabelled.
            val legendW = panels.sumOf { p ->
                val b = binding(p.state)
                val s2 = if (b != null) "${p.name} ${b.pct}%" else p.name
                (g.measure(s2, 10.5f, 600, .02f) + 19f).toDouble()
            }.toFloat()
            val room = w - padL - padR - legendW - 12f
            val title = listOf("Last 24 hours · USED %", "Last 24 hours", "24h", "")
                .first { it.isEmpty() || g.measure(it, 11.5f, 700, .03f) <= room }
            val titleW = if (title.isEmpty()) 0f else
                g.text(title, padL, 18f, 11.5f, 700, t.text, tracking = .03f)
            var lx = w - padR
            // The legend used to walk leftward unchecked and print straight over the
            // title at the declared minimum width with the default two providers.
            val floorX = padL + titleW + 12f
            panels.reversed().forEach { p ->
                val b = binding(p.state)
                val c = dotColor(p.state, p.color, t)
                val full = if (b != null) "${p.name} ${b.pct}%" else p.name
                val short = if (b != null) "${b.pct}%" else p.name
                val s = if (g.measure(full, 10.5f, 600, .02f) + 11f <= lx - floorX) full else short
                val tw = g.measure(s, 10.5f, 600, .02f)
                if (lx - tw - 11f < floorX) return@forEach
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

    /**
     * True when this provider's own numbers have stopped arriving, whatever the others
     * are doing. A failed refresh keeps the last good windows, so without this a revoked
     * token looked identical to healthy data.
     */
    internal fun isStale(
        st: ProviderState,
        now: Long = System.currentTimeMillis(),
        staleAfterMs: Long = 60 * 60_000L,
    ): Boolean {
        if (!st.configured || st.windows.isEmpty()) return false
        if (st.fetchedAt <= 0L) return true
        val age = now - st.fetchedAt
        // A negative age means the clock moved back, not that the data is fresh; a
        // "> threshold" test alone would have called arbitrarily old data current.
        return age < 0L || age > staleAfterMs
    }

    /**
     * Colour for a provider's identity dot. Amber means "these numbers are old" — the
     * one cue every style can afford, since they all already draw this dot.
     */
    private fun dotColor(st: ProviderState, color: Int, t: Theme): Int = when {
        !st.configured -> t.faint
        isStale(st, staleAfterMs = t.staleAfterMs) -> t.warn
        else -> color
    }

    /**
     * Colour for a provider's NAME, in the styles that draw no identity dot. Same amber
     * signal as [dotColor] so "these numbers are old" is visible in every style, not just
     * the ones that happen to have a dot to tint.
     */
    private fun nameColor(st: ProviderState, color: Int, t: Theme): Int = when {
        !st.configured -> t.dim
        isStale(st, staleAfterMs = t.staleAfterMs) -> t.warn
        else -> color
    }

    /** The binding constraint: the fullest window is what actually limits you. */
    internal fun binding(state: ProviderState): Win? = state.windows.maxByOrNull { it.pct }

    /**
     * Linear burn over recent history projected forward to 100%. Null unless the
     * window would realistically cap before it resets.
     */
    /**
     * Whether there is enough recent history to attempt a projection at all — the sample
     * preconditions of [projection], and nothing else.
     *
     * Kept separate because a null projection has four different meanings and only one of
     * them is "you reach the reset before running out". Callers that want to say something
     * reassuring must first establish that they could have said the opposite.
     */
    internal fun canProject(series: List<Pair<Long, Int>>, now: Long = System.currentTimeMillis()): Boolean {
        val pts = series.filter { it.first >= now - 110 * 60_000L && it.second >= 0 }
        if (pts.size < 3) return false
        return (pts.last().first - pts.first().first) / 3600_000f > .25f
    }

    internal fun projection(b: Win, series: List<Pair<Long, Int>>): Long? {
        val now = System.currentTimeMillis()
        if (!canProject(series, now)) return null
        val pts = series.filter { it.first >= now - 110 * 60_000L && it.second >= 0 }
        val first = pts.first()
        val last = pts.last()
        val hours = (last.first - first.first) / 3600_000f
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
        // Prefer the span the provider reported; the label is only a fallback for
        // snapshots written before it was carried. Zero length is rejected outright —
        // it used to divide by zero, and because every NaN comparison is false the
        // "too early to judge" guard let the result through as a confident "on pace".
        val len = (w.lengthMs ?: windowLengthMs(w.label))?.takeIf { it > 0L } ?: return null
        // A reset already behind us means the window has rolled over: the percentage we
        // hold describes the previous period, so there is nothing honest to compute.
        if (w.resetsAt <= now) return null
        val remain = (w.resetsAt - now).coerceIn(0L, len)
        val elapsed = 1f - remain.toFloat() / len.toFloat()
        if (!elapsed.isFinite() || elapsed < .08f) return null
        val ratio = (w.pct / 100f) / elapsed
        return if (ratio.isFinite()) ratio else null
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
        n < 1_000_000 -> floor1(n, 1_000L) + "K"
        n < 1_000_000_000 -> floor1(n, 1_000_000L) + "M"
        else -> floor1(n, 1_000_000_000L) + "B"
    }

    /**
     * Truncates to one decimal instead of rounding. This figure is what is LEFT, so
     * rounding up would promise capacity that is not there — 1,950 must not read as
     * "2K". Truncating also keeps 999,999 as "999.9K" rather than the nonsensical
     * "1000K" that half-up rounding produced.
     */
    private fun floor1(n: Long, unit: Long): String {
        val tenths = (n * 10) / unit
        val whole = tenths / 10
        val frac = tenths % 10
        return if (frac == 0L) whole.toString() else "$whole.$frac"
    }

    /**
     * Reset as a wall clock, with a weekday once it is far enough out that "22:19"
     * alone would be ambiguous — a 7-day window resetting on Friday reads better as
     * "Fri 09:00" than as a bare time three days away.
     */
    internal fun resetClock(ms: Long, now: Long = System.currentTimeMillis()): String {
        if (ms <= 0) return "--:--"
        // Decided on the calendar, not an elapsed-hours threshold: 19h55m out could be
        // tomorrow morning, where a bare "09:00" reads as a time already past today.
        // Past six days a weekday repeats and reads as *this* week, so use a date.
        val cal = Calendar.getInstance().apply { timeInMillis = ms }
        val today = Calendar.getInstance().apply { timeInMillis = now }
        val sameDay = cal.get(Calendar.YEAR) == today.get(Calendar.YEAR) &&
            cal.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR)
        val fmt = when {
            sameDay -> "HH:mm"
            ms - now < 6 * 86_400_000L -> "EEE HH:mm"
            else -> "d MMM HH:mm"
        }
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
        // "5h+" (an inexact Codex span) falls through to the generic branch on purpose.
        Regex("^(\\d+)([mhd])$").find(label.lowercase())?.let { m ->
            val n = m.groupValues[1].toLong()
            if (n > 0L) {
                val unit = when (m.groupValues[2]) {
                    "m" -> "minute"
                    "h" -> "hour"
                    else -> "day"
                }
                return "$n-$unit window"
            }
        }
        return when (label.lowercase()) {
            "weekly" -> "weekly window"
            "primary" -> "5-hour window"
            "now" -> "current window"
            "daily" -> "daily window"
            // Claude's model-scoped windows are all weekly.
            "opus", "sonnet", "haiku" -> "$label · 7-day"
            // Anything else gets a neutral name —
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

    /** Collapses a provider error to a phrase, dropping any server text it embedded. */
    internal fun shortError(e: String): String = when {
        e.contains("sign in", true) || e.contains("expired", true) -> "Sign-in expired"
        e.contains("429") -> "Rate limited"
        else -> "Update failed"
    }

    // --- theme ------------------------------------------------------------

    internal class Theme(ctx: Context) {
        /**
         * How old data must be before it is called stale. Derived from the user's own
         * refresh interval: a flat hour meant the two-hour setting showed amber for most
         * of every cycle even when every single fetch had succeeded.
         */
        val staleAfterMs: Long = max(60L * 60_000L, Prefs.refreshMinutes(ctx) * 60_000L * 2L)
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
        private var cardDrawn = false

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

        /**
         * Draws only if it fits, stepping the size down first and giving up rather than
         * spilling into a neighbour. Most overlap in this file came from text drawn with
         * no measurement at all, so the guard lives in one place instead of at each site.
         *
         * @return the width drawn, or 0 when nothing was.
         */
        fun fitText(
            s: String, x: Float, y: Float, size: Float, weight: Int, color: Int,
            maxW: Float, align: Paint.Align = Paint.Align.LEFT, tracking: Float = 0f,
            minSize: Float = size - 3f,
        ): Float {
            if (maxW <= 1f) return 0f
            var sz = size
            while (sz > minSize && measure(s, sz, weight, tracking) > maxW) sz -= .5f
            if (measure(s, sz, weight, tracking) > maxW) return 0f
            return text(s, x, y, sz, weight, color, align, tracking)
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

        /**
         * The rounded background. Only the first call paints.
         *
         * Every style opens by drawing its own card at the height it was handed, but the
         * bottom strip is now reserved for the universal stamp, so the height a style sees
         * is smaller than the widget. Painting once, up front, at the true size is what
         * keeps the card behind the stamp instead of stopping short of it — and makes it
         * unnecessary to thread an inset through all ten draw functions.
         */
        fun card(w: Float, h: Float, opacityPct: Int = 100) {
            if (cardDrawn) return
            cardDrawn = true
            val a = (opacityPct.coerceIn(0, 100) * 255 / 100)
            val rad = cardRadius(w, h)
            rrect(0f, 0f, w, h, rad, withAlpha(t.bg, a))
            r.set(.5f, .5f, w - .5f, h - .5f)
            p.style = Paint.Style.STROKE
            p.strokeWidth = 1f
            p.color = withAlpha(t.stroke, Color.alpha(t.stroke) * a / 255)
            p.shader = null
            c.drawRoundRect(r, rad - .5f, rad - .5f, p)
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

        fun chipWidth(s: String): Float = measure(s, 9f, 700, .035f) + 10f

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
