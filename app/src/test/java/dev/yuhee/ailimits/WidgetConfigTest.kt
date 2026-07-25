package dev.yuhee.ailimits

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Per-widget configuration, and above all the guarantee that a widget WITHOUT a record
 * keeps rendering exactly as it did — that is the one way this feature could break every
 * home screen on update, so it is asserted rather than reasoned about.
 *
 * Robolectric because [WidgetConfig] serialises through org.json, which is a throwing
 * stub on a plain JVM.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WidgetConfigTest {

    private val ctx = ApplicationProvider.getApplicationContext<Context>()

    /** THE migration guarantee: no record means inherit, byte for byte. */
    @Test
    fun `a widget with no record inherits the app settings exactly`() {
        // Deliberately move every global off its default first: if optsFor quietly
        // substituted code defaults, this is where it would show.
        Settings.setShowGemini(ctx, true)
        Settings.setShowCodex(ctx, false)
        Settings.setOpacity(ctx, 55)
        Settings.setShowProjection(ctx, false)
        Settings.setShowSparkline(ctx, false)
        Settings.setShowTokens(ctx, false)
        Settings.setShowPace(ctx, false)
        Settings.setHiddenWindows(ctx, "cl", setOf("7d"))

        val global = WidgetRenderer.optsFrom(ctx)
        assertEquals("no stored record must change nothing", global, WidgetRenderer.optsFor(null, global))
        assertEquals(global, WidgetRenderer.optsFor(ctx, 4242))
    }

    @Test
    fun `only the fields a widget overrides differ from the app settings`() {
        Settings.setShowGemini(ctx, true)
        Settings.setOpacity(ctx, 90)
        val global = WidgetRenderer.optsFrom(ctx)

        val merged = WidgetRenderer.optsFor(WidgetConfig(showGemini = false, opacity = 60), global)
        assertFalse(merged.showGemini)
        assertEquals(60, merged.opacity)
        // Untouched fields still track the app.
        assertEquals(global.showClaude, merged.showClaude)
        assertEquals(global.sparkline, merged.sparkline)
        assertEquals(global.hiddenCodex, merged.hiddenCodex)
    }

    /** A widget showing nothing at all is not a state worth supporting. */
    @Test
    fun `turning every provider off falls back to the app settings`() {
        val global = WidgetRenderer.optsFrom(ctx)
        val merged = WidgetRenderer.optsFor(
            WidgetConfig(showClaude = false, showCodex = false, showGemini = false), global
        )
        assertEquals(global, merged)
        assertTrue(merged.shown > 0)
    }

    @Test
    fun `absent fields survive a json round trip as absent`() {
        val cfg = WidgetConfig(style = WidgetRenderer.Style.RUNWAY, opacity = 70)
        val back = WidgetConfig.fromJson(cfg.toJson())
        assertEquals(WidgetRenderer.Style.RUNWAY, back.style)
        assertEquals(70, back.opacity)
        // The whole safety property: everything untouched must come back null, not false.
        assertNull(back.showClaude)
        assertNull(back.sparkline)
        assertNull(back.hiddenGemini)
    }

    @Test
    fun `an empty set of hidden windows is an override, not an absence`() {
        // Distinct from null: it means "this widget shows every window" even when the app
        // hides one. Collapsing it to null would silently re-apply the global hide.
        val back = WidgetConfig.fromJson(WidgetConfig(hiddenClaude = emptySet()).toJson())
        assertNotNull(back.hiddenClaude)
        assertEquals(emptySet<String>(), back.hiddenClaude)
    }

    @Test
    fun `unreadable json degrades to inherit rather than throwing`() {
        val back = WidgetConfig.fromJson("{not json at all")
        assertTrue(back.inheritsEverything)
    }

    @Test
    fun `saving a record that overrides nothing removes it`() {
        WidgetConfigStore.save(ctx, 7, WidgetConfig(opacity = 50))
        assertNotNull(WidgetConfigStore.load(ctx, 7))
        WidgetConfigStore.save(ctx, 7, WidgetConfig())
        assertNull("an all-inherit record is just an absent one", WidgetConfigStore.load(ctx, 7))
    }

    /** Hosts recycle appWidgetIds, so a leaked record would land on someone else's widget. */
    @Test
    fun `reap drops records for widgets that no longer exist`() {
        WidgetConfigStore.save(ctx, 11, WidgetConfig(opacity = 50))
        WidgetConfigStore.save(ctx, 12, WidgetConfig(opacity = 60))
        WidgetConfigStore.reap(ctx, setOf(12))
        assertNull(WidgetConfigStore.load(ctx, 11))
        assertNotNull(WidgetConfigStore.load(ctx, 12))
    }

    @Test
    fun `reaping does not touch the other keys in the same prefs file`() {
        Settings.setOpacity(ctx, 77)
        Prefs.setRefreshMinutes(ctx, 15)
        WidgetConfigStore.save(ctx, 21, WidgetConfig(opacity = 50))
        WidgetConfigStore.reap(ctx, emptySet())
        assertEquals(77, Settings.opacity(ctx))
        assertEquals(15, Prefs.refreshMinutes(ctx))
    }

    @Test
    fun `every style has a receiver whose default style is itself`() {
        // A style reachable only through an override would never appear in the picker.
        val defaults = WidgetRenderer.Style.entries.map { style ->
            style to WidgetRenderer.Style.entries.count { it == style }
        }
        assertEquals(WidgetRenderer.Style.entries.size, defaults.size)
        listOf(
            UsageWidgetProvider::class.java to WidgetRenderer.Style.DETAIL,
            BarsWidgetProvider::class.java to WidgetRenderer.Style.BARS,
            PercentWidgetProvider::class.java to WidgetRenderer.Style.RINGS,
            GraphWidgetProvider::class.java to WidgetRenderer.Style.GRAPH,
            BatteryWidgetProvider::class.java to WidgetRenderer.Style.BATTERY,
            CountdownWidgetProvider::class.java to WidgetRenderer.Style.COUNTDOWN,
            TickerWidgetProvider::class.java to WidgetRenderer.Style.TICKER,
            PickWidgetProvider::class.java to WidgetRenderer.Style.PICK,
            HorizonWidgetProvider::class.java to WidgetRenderer.Style.HORIZON,
            RunwayWidgetProvider::class.java to WidgetRenderer.Style.RUNWAY,
        ).forEach { (cls, style) ->
            assertEquals(cls.simpleName, style, WidgetRenderer.defaultStyleFor(cls))
        }
    }
}
