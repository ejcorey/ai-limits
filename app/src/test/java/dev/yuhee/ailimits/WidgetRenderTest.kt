package dev.yuhee.ailimits

import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Renders every widget style at every layout tier through the real Android
 * graphics stack and writes the results to build/widget-shots for eyeballing.
 * Robolectric's NATIVE graphics mode uses the same Skia that runs on a device,
 * so what lands in those PNGs is what the launcher will draw.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class WidgetRenderTest {

    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val out = File("build/widget-shots").apply { mkdirs() }

    private val hour = 3_600_000L
    private val now = System.currentTimeMillis()

    /** 24h of rising usage, sampled every 30 min — the shape the app accumulates. */
    private val history: List<HistoryPoint> = buildList {
        var t = now - 24 * hour
        while (t <= now) {
            val k = (t - (now - 24 * hour)).toFloat() / (24 * hour)
            add(HistoryPoint(t, (9 + 58 * k).toInt(), (6 + 34 * k).toInt(), (4 + 20 * k).toInt()))
            t += 30 * 60_000L
        }
    }

    private fun snap(
        claudeWins: List<Win> = listOf(
            Win("5h", 68, now + 3 * hour + 40 * 60_000),
            Win("7d", 31, now + 4 * 24 * hour),
            Win("Opus", 12, now + 4 * 24 * hour),
        ),
        codexWins: List<Win> = listOf(
            Win("5h", 41, now + hour + 12 * 60_000),
            Win("7d", 22, now + 2 * 24 * hour),
        ),
        claudeErr: String? = null,
        codexConfigured: Boolean = true,
        geminiWins: List<Win> = listOf(
            Win("Pro", 37, now + 9 * hour, remaining = 1_240_000, unit = "TOKENS"),
            Win("Flash", 12, now + 9 * hour, remaining = 8_900_000, unit = "TOKENS"),
        ),
    ) = Snapshot(
        // Freshly fetched, so the ordinary shots show healthy colours; the staleness
        // test supplies its own older timestamps. Leaving these at 0 made every single
        // screenshot render amber, which hides the cue it exists to show.
        ProviderState(true, claudeWins, claudeErr, null, now - 4 * 60_000),
        ProviderState(codexConfigured, codexWins, null, "plus", now - 4 * 60_000),
        now - 4 * 60_000,
        ProviderState(true, geminiWins, null, "free-tier", now - 4 * 60_000),
    )

    /** Widgets are overwhelmingly seen on a dark home screen, so that is the default. */
    private fun dark() = org.robolectric.RuntimeEnvironment.setQualifiers("+night")

    private fun shoot(
        name: String,
        style: WidgetRenderer.Style,
        w: Float,
        h: Float,
        s: Snapshot = snap(),
        o: WidgetRenderer.Opts = WidgetRenderer.Opts(),
    ) {
        val (bmp, _) = WidgetRenderer.render(ctx, style, w, h, s, history, o = o)
        File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue("$name should have pixels", bmp.width > 0 && bmp.height > 0)
        // A blank canvas means the draw silently no-oped.
        val px = IntArray(bmp.width * bmp.height)
        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        assertTrue("$name rendered nothing", px.any { it != 0 })
    }

    @Test
    fun detailTiers() {
        dark()
        shoot("detail-rich-264x232", WidgetRenderer.Style.DETAIL, 264f, 232f)
        shoot("detail-full-264x160", WidgetRenderer.Style.DETAIL, 264f, 160f)
        shoot("detail-full-264x147", WidgetRenderer.Style.DETAIL, 264f, 147f)
        shoot("detail-wide-320x152", WidgetRenderer.Style.DETAIL, 320f, 152f)
        shoot("detail-medium-264x116", WidgetRenderer.Style.DETAIL, 264f, 116f)
        shoot("detail-compact-264x72", WidgetRenderer.Style.DETAIL, 264f, 72f)
    }

    @Test
    fun otherStyles() {
        dark()
        shoot("bars-264x76", WidgetRenderer.Style.BARS, 264f, 76f)
        shoot("rings-180x110", WidgetRenderer.Style.RINGS, 180f, 110f)
        shoot("rings-264x120", WidgetRenderer.Style.RINGS, 264f, 120f)
        shoot("graph-264x132", WidgetRenderer.Style.GRAPH, 264f, 132f)
        shoot("graph-320x152", WidgetRenderer.Style.GRAPH, 320f, 152f)
    }

    @Test
    fun edgeStates() {
        dark()
        shoot("state-signedout", WidgetRenderer.Style.DETAIL, 264f, 160f,
            snap(codexWins = emptyList(), codexConfigured = false))
        shoot("state-error", WidgetRenderer.Style.DETAIL, 264f, 160f,
            snap(claudeWins = emptyList(), claudeErr = "Sign in expired"))
        shoot("state-nearcap", WidgetRenderer.Style.DETAIL, 264f, 160f,
            snap(
                claudeWins = listOf(
                    Win("5h", 94, now + 36 * 60_000),
                    Win("7d", 78, now + 2 * 24 * hour),
                    Win("Opus", 61, now + 2 * 24 * hour),
                ),
                codexWins = listOf(Win("5h", 83, now + 24 * 60_000), Win("7d", 57, now + 24 * hour)),
            ))
        // Long plan name + three secondary windows: the tightest the meta row gets.
        shoot("state-crowded", WidgetRenderer.Style.DETAIL, 250f, 160f,
            Snapshot(
                ProviderState(
                    true,
                    listOf(
                        Win("5h", 100, now + 5 * hour),
                        Win("7d", 88, now + 6 * 24 * hour),
                        Win("Opus", 77, now + 6 * 24 * hour),
                        Win("Sonnet", 66, now + 6 * 24 * hour),
                    ),
                    null,
                ),
                ProviderState(true, listOf(Win("5h", 5, now + 9 * hour)), null, "chatgpt_business"),
                now - 90 * 60_000,
            ))
    }

    /**
     * The launcher lets a user drag a widget to any cell count, so every style has
     * to survive the extremes rather than only the sizes it was designed against.
     */
    @Test
    fun resizeExtremes() {
        dark()
        val sizes = listOf(
            120f to 40f,    // smallest the manifest allows anyone to shrink to
            150f to 80f,
            180f to 60f,
            250f to 45f,    // very wide and very short
            420f to 70f,
            640f to 480f,   // largest the manifest now allows
            640f to 70f,    // full-width single strip
            110f to 110f,   // one One-UI cell square
            90f to 36f,     // the very smallest any style permits
            200f to 480f,   // narrow and very tall
        )
        for (style in WidgetRenderer.Style.entries) {
            for ((w, h) in sizes) {
                shoot("resize-${style.name.lowercase()}-${w.toInt()}x${h.toInt()}", style, w, h)
            }
        }
    }

    /**
     * The images the launcher's widget picker shows, rendered by the real renderer into
     * build/widget-previews for copying into res/drawable-nodpi.
     *
     * Hand-made previews go stale silently — a user picks a style from a picture the app
     * no longer draws. Generating them from the same code path as the widget itself means
     * refreshing them is one command, and they can never show a layout that no longer
     * exists.
     */
    @Test
    fun widgetPickerPreviews() {
        dark()
        val dir = File("build/widget-previews").apply { mkdirs() }
        // Roughly the cell footprint each style declares as its target.
        val sizes = mapOf(
            WidgetRenderer.Style.DETAIL to (264f to 176f),
            WidgetRenderer.Style.BARS to (264f to 80f),
            WidgetRenderer.Style.RINGS to (220f to 120f),
            WidgetRenderer.Style.GRAPH to (264f to 140f),
            WidgetRenderer.Style.BATTERY to (198f to 96f),
            WidgetRenderer.Style.COUNTDOWN to (198f to 96f),
            WidgetRenderer.Style.TICKER to (264f to 52f),
            WidgetRenderer.Style.PICK to (110f to 110f),
            WidgetRenderer.Style.HORIZON to (264f to 120f),
            WidgetRenderer.Style.RUNWAY to (264f to 120f),
        )
        val o = WidgetRenderer.Opts(showGemini = true)
        for (style in WidgetRenderer.Style.entries) {
            val (w, h) = sizes.getValue(style)
            val (bmp, _) = WidgetRenderer.render(ctx, style, w, h, snap(), history, o = o)
            File(dir, "preview_${style.name.lowercase()}.png").outputStream()
                .use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        // getValue above throws on a style with no declared size, so a new style cannot
        // ship without one — which is how the old parallel label list went stale.
        assertTrue("every style needs a preview size", sizes.size == WidgetRenderer.Style.entries.size)
    }

    /**
     * Every style must actually PAINT the amber staleness cue when a provider's numbers
     * have gone old — asserted against the pixels, not asserted in a release note.
     *
     * "Every style shows the staleness cue" has been claimed and been false twice in this
     * app (Rings and Graph the first time, Horizon the second), each time because it was
     * checked by reading the code rather than by looking at the output. This counts the
     * warn-coloured pixels instead.
     */
    @Test
    fun everyStyleShowsTheStalenessCue() {
        dark()
        val warn = ctx.getColor(R.color.warn)
        // All three have gone quiet. Staleness has to be asserted on every provider
        // because Pick draws only ONE of them — the one with the most headroom — so a
        // fixture where just one provider is old proves nothing about that style.
        // The fresh comparison below is what stops a style passing by painting amber
        // unconditionally.
        val stale = Snapshot(
            ProviderState(true, listOf(Win("5h", 68, now + 3 * hour, lengthMs = 5 * hour)), null, null, now - 3 * hour),
            ProviderState(true, listOf(Win("5h", 41, now + hour, lengthMs = 5 * hour)), null, "plus", now - 3 * hour),
            now - 3 * hour,
            ProviderState(true, listOf(Win("Pro", 37, now + 9 * hour)), null, "free-tier", now - 3 * hour),
        )
        val fresh = snap()
        val o = WidgetRenderer.Opts(showGemini = true)

        fun warnPixels(s: Snapshot, style: WidgetRenderer.Style, tag: String): Int {
            val (bmp, _) = WidgetRenderer.render(ctx, style, 264f, 160f, s, history, o = o)
            File(out, "cue-$tag-${style.name.lowercase()}.png").outputStream()
                .use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val px = IntArray(bmp.width * bmp.height)
            bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
            // Antialiased glyph edges blend toward the background, so the core pixels are
            // matched with a small tolerance rather than exactly.
            return px.count { p ->
                kotlin.math.abs(android.graphics.Color.red(p) - android.graphics.Color.red(warn)) < 12 &&
                    kotlin.math.abs(android.graphics.Color.green(p) - android.graphics.Color.green(warn)) < 12 &&
                    kotlin.math.abs(android.graphics.Color.blue(p) - android.graphics.Color.blue(warn)) < 12 &&
                    android.graphics.Color.alpha(p) > 200
            }
        }

        val report = WidgetRenderer.Style.entries.map { style ->
            Triple(style, warnPixels(stale, style, "stale"), warnPixels(fresh, style, "fresh"))
        }
        val bad = report.filter { (_, stalePx, freshPx) -> stalePx <= freshPx + 20 }
        assertTrue(
            "styles with no staleness cue: " +
                bad.joinToString { (s, a, b) -> "$s(stale=$a fresh=$b)" } +
                " | warn=#${Integer.toHexString(warn)}",
            bad.isEmpty(),
        )
    }

    /** Every height in the resizable range must draw without throwing. */
    @Test
    fun everyHeightInRangeRenders() {
        dark()
        var n = 0
        var h = 40f
        while (h <= 480f) {
            WidgetRenderer.render(ctx, WidgetRenderer.Style.DETAIL, 264f, h, snap(), history)
            WidgetRenderer.render(ctx, WidgetRenderer.Style.DETAIL, 150f, h, snap(), history)
            n++
            h += 4f
        }
        assertTrue("expected a sweep", n > 60)
    }

    /** The widened envelope: every style at the new ceiling and floor, all providers on. */
    @Test
    fun enlargedEnvelope() {
        dark()
        val o = WidgetRenderer.Opts(showGemini = true)
        for (style in WidgetRenderer.Style.entries) {
            val n = style.name.lowercase()
            shoot("big-$n-640x480", style, 640f, 480f, o = o)
            shoot("big-$n-640x120", style, 640f, 120f, o = o)
            shoot("big-$n-96x44", style, 96f, 44f, o = o)
        }
    }

    /** Gemini reports real token counts; they must reach the widget, and be droppable. */
    @Test
    fun tokenCounts() {
        dark()
        val onlyGemini = WidgetRenderer.Opts(showClaude = false, showCodex = false, showGemini = true)
        shoot("tokens-solo-264x200", WidgetRenderer.Style.DETAIL, 264f, 200f, o = onlyGemini)
        shoot("tokens-trio-264x232", WidgetRenderer.Style.DETAIL, 264f, 232f,
            o = WidgetRenderer.Opts(showGemini = true))
        shoot("tokens-battery-264x110", WidgetRenderer.Style.BATTERY, 264f, 110f, o = onlyGemini)
        shoot("tokens-off-264x200", WidgetRenderer.Style.DETAIL, 264f, 200f,
            o = onlyGemini.copy(tokens = false))
        shoot("pace-off-264x200", WidgetRenderer.Style.DETAIL, 264f, 200f,
            o = onlyGemini.copy(pace = false))
    }

    /**
     * One provider going quiet must be visible. Before this, a revoked token showed
     * hours-old percentages under a fresh "updated 14:32" with no cue at all, and six
     * of the seven styles had no staleness indication whatsoever.
     */
    @Test
    fun oneProviderGoneQuiet() {
        dark()
        val stale = Snapshot(
            // Claude last answered three hours ago; the others are current.
            ProviderState(true, listOf(Win("5h", 68, now + 3 * hour)), "Sign in expired", null, now - 3 * hour),
            ProviderState(true, listOf(Win("5h", 41, now + hour)), null, "plus", now),
            now,
            ProviderState(true, listOf(Win("Pro", 37, now + 9 * hour, 1_240_000, "TOKENS")), null, "free-tier", now),
        )
        val o = WidgetRenderer.Opts(showGemini = true)
        for (style in WidgetRenderer.Style.entries) {
            shoot("stale-${style.name.lowercase()}", style, 264f, 160f, stale, o)
        }
        shoot("stale-detail-tall", WidgetRenderer.Style.DETAIL, 264f, 232f, stale, o)
    }

    /**
     * The sizes an adversarial review showed overlapping: each is a size the manifest
     * actually declares, several of them defaults rather than extremes.
     */
    @Test
    fun declaredMinimumsDoNotOverlap() {
        dark()
        val trio = WidgetRenderer.Opts(showGemini = true)
        // Graph legend used to print straight over its own title here — two providers,
        // declared minWidth, nothing unusual.
        shoot("min-graph-250x120", WidgetRenderer.Style.GRAPH, 250f, 120f)
        shoot("min-graph-140x70", WidgetRenderer.Style.GRAPH, 140f, 70f, o = trio)
        // Bars had no height adaptation: row 0 sat above the top edge.
        shoot("min-bars-250x40", WidgetRenderer.Style.BARS, 250f, 40f, o = trio)
        shoot("min-bars-110x40", WidgetRenderer.Style.BARS, 110f, 40f, o = trio)
        // Countdown/battery sub-lines and ring captions collided across columns.
        shoot("min-countdown-250x100", WidgetRenderer.Style.COUNTDOWN, 250f, 100f, o = trio)
        shoot("min-battery-180x110", WidgetRenderer.Style.BATTERY, 180f, 110f, o = trio)
        shoot("min-rings-100x88", WidgetRenderer.Style.RINGS, 100f, 88f, o = trio)
        shoot("min-ticker-90x60", WidgetRenderer.Style.TICKER, 90f, 60f, o = trio)
        // Detail: compact columns, the solo hero at the declared 48dp floor, and the
        // height where the footer used to be drawn over the last block.
        shoot("min-detail-120x48", WidgetRenderer.Style.DETAIL, 120f, 48f, o = trio)
        shoot("min-detail-180x180", WidgetRenderer.Style.DETAIL, 180f, 180f, o = trio)
        shoot("min-solo-120x48", WidgetRenderer.Style.DETAIL, 120f, 48f,
            o = WidgetRenderer.Opts(showCodex = false))
    }

    /** The bitmap budget must hold even at sizes the launcher can impose below API 31. */
    @Test
    fun oversizedWidgetStaysInsideTheBudget() {
        for ((w, h) in listOf(1000 to 600, 1200 to 800, 2000 to 1200)) {
            val (pxW, pxH) = WidgetRenderer.bitmapSize(3f, w.toFloat(), h.toFloat())
            assertTrue(
                "${w}x$h -> ${pxW}x$pxH = ${pxW * pxH}px, over budget",
                pxW.toLong() * pxH <= WidgetRenderer.PIXEL_BUDGET.toLong() + 1,
            )
        }
        // And the declared maxima, where independent rounding used to overshoot.
        for ((w, h) in listOf(640 to 480, 640 to 300, 640 to 200)) {
            val (pxW, pxH) = WidgetRenderer.bitmapSize(3f, w.toFloat(), h.toFloat())
            assertTrue("${w}x$h overshoots", pxW.toLong() * pxH <= WidgetRenderer.PIXEL_BUDGET.toLong() + 1)
        }
    }

    @Test
    fun soloProvider() {
        dark()
        val onlyClaude = WidgetRenderer.Opts(showClaude = true, showCodex = false)
        val onlyCodex = WidgetRenderer.Opts(showClaude = false, showCodex = true)
        shoot("solo-claude-264x160", WidgetRenderer.Style.DETAIL, 264f, 160f, o = onlyClaude)
        shoot("solo-claude-264x232", WidgetRenderer.Style.DETAIL, 264f, 232f, o = onlyClaude)
        shoot("solo-claude-264x104", WidgetRenderer.Style.DETAIL, 264f, 104f, o = onlyClaude)
        shoot("solo-codex-264x160", WidgetRenderer.Style.DETAIL, 264f, 160f, o = onlyCodex)
        shoot("solo-rings-180x110", WidgetRenderer.Style.RINGS, 180f, 110f, o = onlyClaude)
        shoot("solo-bars-264x60", WidgetRenderer.Style.BARS, 264f, 60f, o = onlyClaude)
        shoot("solo-graph-264x132", WidgetRenderer.Style.GRAPH, 264f, 132f, o = onlyClaude)
    }

    /** The One UI cell footprints a Galaxy S24 Ultra actually snaps widgets to. */
    @Test
    fun s24UltraGrid() {
        dark()
        // One UI width for n cells ≈ 70n−30 → 2:110, 3:180, 4:250, 5:320
        val widths = intArrayOf(110, 180, 250, 320)
        val heights = intArrayOf(75, 145, 215, 285)
        for (w in widths) for (h in heights) {
            shoot("s24-detail-${w}x$h", WidgetRenderer.Style.DETAIL, w.toFloat(), h.toFloat())
        }
        shoot("s24-bars-320x75", WidgetRenderer.Style.BARS, 320f, 75f)
        shoot("s24-rings-180x145", WidgetRenderer.Style.RINGS, 180f, 145f)
        shoot("s24-graph-320x215", WidgetRenderer.Style.GRAPH, 320f, 215f)
        shoot("s24-battery-180x75", WidgetRenderer.Style.BATTERY, 180f, 75f)
        shoot("s24-countdown-250x75", WidgetRenderer.Style.COUNTDOWN, 250f, 75f)
        shoot("s24-ticker-250x75", WidgetRenderer.Style.TICKER, 250f, 75f)
    }

    @Test
    fun newStyles() {
        dark()
        shoot("battery-264x76", WidgetRenderer.Style.BATTERY, 264f, 76f)
        shoot("battery-264x110", WidgetRenderer.Style.BATTERY, 264f, 110f)
        shoot("countdown-264x76", WidgetRenderer.Style.COUNTDOWN, 264f, 76f)
        shoot("countdown-264x110", WidgetRenderer.Style.COUNTDOWN, 264f, 110f)
        shoot("ticker-264x44", WidgetRenderer.Style.TICKER, 264f, 44f)
        shoot("ticker-264x60", WidgetRenderer.Style.TICKER, 264f, 60f)
    }

    /** Every style with all three providers on. */
    @Test
    fun threeProviders() {
        dark()
        val o = WidgetRenderer.Opts(showGemini = true)
        for (style in WidgetRenderer.Style.entries) {
            shoot("trio-${style.name.lowercase()}-264x160", style, 264f, 160f, o = o)
            shoot("trio-${style.name.lowercase()}-264x76", style, 264f, 76f, o = o)
        }
        shoot("trio-detail-264x232", WidgetRenderer.Style.DETAIL, 264f, 232f, o = o)
    }

    /** Hiding windows changes which one leads. */
    @Test
    fun windowSelection() {
        dark()
        // Claude's binding window is 5h (68%); hiding it must promote 7d (31%).
        val o = WidgetRenderer.Opts(hiddenClaude = setOf("5h", "Opus"))
        shoot("winsel-weekly-only", WidgetRenderer.Style.DETAIL, 264f, 160f, o = o)
        val (bmp, _) = WidgetRenderer.render(ctx, WidgetRenderer.Style.DETAIL, 264f, 160f, snap(), history, o = o)
        assertTrue(bmp.width > 0)
    }

    @Test
    fun displayOptions() {
        dark()
        shoot("opt-translucent", WidgetRenderer.Style.DETAIL, 264f, 160f,
            o = WidgetRenderer.Opts(opacity = 55))
        shoot("opt-no-sparkline", WidgetRenderer.Style.DETAIL, 264f, 232f,
            o = WidgetRenderer.Opts(sparkline = false))
        shoot("opt-sparkline", WidgetRenderer.Style.DETAIL, 264f, 232f,
            o = WidgetRenderer.Opts(sparkline = true))
    }

    @Test
    fun lightTheme() {
        // qualifiers flip day/night for the whole resource lookup
        org.robolectric.RuntimeEnvironment.setQualifiers("+notnight")
        shoot("light-detail-264x160", WidgetRenderer.Style.DETAIL, 264f, 160f)
        shoot("light-graph-264x132", WidgetRenderer.Style.GRAPH, 264f, 132f)
        shoot("light-rings-264x120", WidgetRenderer.Style.RINGS, 264f, 120f)
    }
}
