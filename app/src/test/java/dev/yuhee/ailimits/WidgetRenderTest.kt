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
    private val history: List<Triple<Long, Int, Int>> = buildList {
        var t = now - 24 * hour
        while (t <= now) {
            val k = (t - (now - 24 * hour)).toFloat() / (24 * hour)
            add(Triple(t, (9 + 58 * k).toInt(), (6 + 34 * k).toInt()))
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
    ) = Snapshot(
        ProviderState(true, claudeWins, claudeErr),
        ProviderState(codexConfigured, codexWins, null, "plus"),
        now - 4 * 60_000,
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
            480f to 380f,   // largest the manifest now allows
            480f to 70f,    // full-width single strip
            110f to 110f,   // one One-UI cell square
            200f to 380f,   // narrow and very tall
        )
        for (style in WidgetRenderer.Style.entries) {
            for ((w, h) in sizes) {
                shoot("resize-${style.name.lowercase()}-${w.toInt()}x${h.toInt()}", style, w, h)
            }
        }
    }

    /** Every height in the resizable range must draw without throwing. */
    @Test
    fun everyHeightInRangeRenders() {
        dark()
        var n = 0
        var h = 40f
        while (h <= 380f) {
            WidgetRenderer.render(ctx, WidgetRenderer.Style.DETAIL, 264f, h, snap(), history)
            WidgetRenderer.render(ctx, WidgetRenderer.Style.DETAIL, 150f, h, snap(), history)
            n++
            h += 4f
        }
        assertTrue("expected a sweep", n > 60)
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

    /** Gemini turned on: three panels, and an honest note where the percentage would be. */
    @Test
    fun geminiOptional() {
        dark()
        val base = snap()
        val s = Snapshot(base.claude, base.codex, now - 4 * 60_000,
            ProviderState(true, emptyList(), null, "AI Pro"))
        val three = WidgetRenderer.Opts(showClaude = true, showCodex = true, showGemini = true)
        shoot("gemini-detail-3-264x300", WidgetRenderer.Style.DETAIL, 264f, 300f, s, three)
        shoot("gemini-detail-3-320x232", WidgetRenderer.Style.DETAIL, 320f, 232f, s, three)
        shoot("gemini-rings-3-320x130", WidgetRenderer.Style.RINGS, 320f, 130f, s, three)
        shoot("gemini-bars-3-264x104", WidgetRenderer.Style.BARS, 264f, 104f, s, three)
        val solo = WidgetRenderer.Opts(showClaude = false, showCodex = false, showGemini = true)
        shoot("gemini-solo-264x160", WidgetRenderer.Style.DETAIL, 264f, 160f, s, solo)
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
