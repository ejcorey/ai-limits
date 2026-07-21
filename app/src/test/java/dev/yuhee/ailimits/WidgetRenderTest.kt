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

    private fun shoot(name: String, style: WidgetRenderer.Style, w: Float, h: Float, s: Snapshot = snap()) {
        val (bmp, _) = WidgetRenderer.render(ctx, style, w, h, s, history)
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

    @Test
    fun lightTheme() {
        // qualifiers flip day/night for the whole resource lookup
        org.robolectric.RuntimeEnvironment.setQualifiers("+notnight")
        shoot("light-detail-264x160", WidgetRenderer.Style.DETAIL, 264f, 160f)
        shoot("light-graph-264x132", WidgetRenderer.Style.GRAPH, 264f, 132f)
        shoot("light-rings-264x120", WidgetRenderer.Style.RINGS, 264f, 120f)
    }
}
