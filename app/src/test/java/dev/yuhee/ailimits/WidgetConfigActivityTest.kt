package dev.yuhee.ailimits

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.RadioButton
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The flat limit checklist — the config surface that finally makes every combination of
 * limits reachable. These drive the real activity, because the previous design also
 * "supported" every combination in the sense that the fields could express one, while the
 * UI made the one people wanted (Claude 5h + Claude weekly + a Codex limit) undiscoverable.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WidgetConfigActivityTest {

    private val ctx = ApplicationProvider.getApplicationContext<Context>()

    /** A stored snapshot with Claude reporting three windows and Codex two. */
    private fun seedSnapshot() {
        val now = System.currentTimeMillis()
        fun win(l: String, p: Int) = JSONObject()
            .put("l", l).put("p", p).put("r", now + 3_600_000L).put("n", -1L).put("u", "").put("len", 0L)
        fun provider(vararg wins: JSONObject) = JSONObject()
            .put("w", JSONArray().also { a -> wins.forEach { a.put(it) } })
            .put("e", "").put("plan", "").put("t", now)
        Prefs.setSnapshot(
            ctx,
            JSONObject()
                .put("claude", provider(win("5h", 68), win("7d", 31), win("Opus", 12)))
                .put("codex", provider(win("5h", 41), win("7d", 22)))
                .put("fetchedAt", now)
                .toString(),
        )
        // configured flags come from stored tokens, not the snapshot
        Prefs.setClaudeTokens(ctx, "t", null, now + 3_600_000L)
        Prefs.setCodexTokens(ctx, CodexTokens("t", null, null, "acct", now + 3_600_000L))
    }

    private fun launch(widgetId: Int = 42): WidgetConfigActivity {
        val intent = Intent(ctx, WidgetConfigActivity::class.java)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
        return Robolectric.buildActivity(WidgetConfigActivity::class.java, intent).setup().get()
    }

    private fun allViews(root: View): List<View> = buildList {
        fun walk(v: View) {
            add(v)
            if (v is ViewGroup) (0 until v.childCount).forEach { walk(v.getChildAt(it)) }
        }
        walk(root)
    }

    private fun checkboxes(a: WidgetConfigActivity): List<CheckBox> =
        allViews(a.findViewById(android.R.id.content))
            .filterIsInstance<CheckBox>()
            .filter { it !is RadioButton && it.text.contains("·") }

    private fun box(a: WidgetConfigActivity, label: String): CheckBox =
        checkboxes(a).first { it.text.startsWith(label) }

    @Test
    fun `every reported limit of both providers is one checkbox in one list`() {
        seedSnapshot()
        val a = launch()
        val labels = checkboxes(a).map { it.text.toString() }
        assertEquals("3 Claude + 2 Codex windows", 5, labels.size)
        listOf(
            "Claude · 5-hour window", "Claude · 7-day window", "Claude · Opus · 7-day",
            "Codex · 5-hour window", "Codex · 7-day window",
        ).forEach { expected ->
            assertTrue("missing $expected in $labels", labels.any { it.startsWith(expected) })
        }
    }

    /** The exact permutation from the bug report: Claude 5h + Claude 7d + Codex 5h. */
    @Test
    fun `claude 5h plus claude weekly plus one codex limit is three ticks`() {
        seedSnapshot()
        val a = launch(widgetId = 77)

        box(a, "Claude · Opus").performClick()          // untick Opus
        box(a, "Codex · 7-day window").performClick()   // untick Codex weekly

        allViews(a.findViewById(android.R.id.content))
            .filterIsInstance<Button>().first { it.text == "Save" }.performClick()

        val saved = WidgetConfigStore.load(ctx, 77)
        assertNotNull("a record must be stored", saved)
        val opts = WidgetRenderer.optsFor(saved, WidgetRenderer.optsFrom(ctx))
        assertTrue(opts.showClaude)
        assertTrue(opts.showCodex)
        assertEquals(setOf("Opus"), opts.hiddenClaude)
        assertEquals(setOf("7d"), opts.hiddenCodex)
    }

    @Test
    fun `the last ticked limit cannot be unticked`() {
        seedSnapshot()
        val a = launch()
        // Untick everything but Claude 5h…
        listOf("Claude · 7-day window", "Claude · Opus", "Codex · 5-hour window", "Codex · 7-day window")
            .forEach { box(a, it).performClick() }
        // …then try to untick the survivor.
        box(a, "Claude · 5-hour window").performClick()
        assertTrue(
            "the final limit must snap back on",
            box(a, "Claude · 5-hour window").isChecked,
        )
    }

    @Test
    fun `unticking every limit of one provider turns that provider off`() {
        seedSnapshot()
        val a = launch(widgetId = 88)
        listOf("Codex · 5-hour window", "Codex · 7-day window").forEach { box(a, it).performClick() }
        allViews(a.findViewById(android.R.id.content))
            .filterIsInstance<Button>().first { it.text == "Save" }.performClick()
        val opts = WidgetRenderer.optsFor(WidgetConfigStore.load(ctx, 88), WidgetRenderer.optsFrom(ctx))
        assertTrue(opts.showClaude)
        assertTrue(!opts.showCodex)
    }

    @Test
    fun `row mode radios write perWindow`() {
        seedSnapshot()
        val a = launch(widgetId = 99)
        allViews(a.findViewById(android.R.id.content))
            .filterIsInstance<RadioButton>().first { it.text.startsWith("One row per limit") }
            .performClick()
        allViews(a.findViewById(android.R.id.content))
            .filterIsInstance<Button>().first { it.text == "Save" }.performClick()
        assertEquals(true, WidgetConfigStore.load(ctx, 99)?.perWindow)
    }
}
