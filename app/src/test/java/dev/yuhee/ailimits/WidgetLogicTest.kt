package dev.yuhee.ailimits

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure decisions behind the widget: what a window is called, which one leads,
 * and how long until it resets. No Android needed.
 */
class WidgetLogicTest {

    @Test
    fun `window labels are spelled out`() {
        assertEquals("5-hour window", WidgetRenderer.windowName("5h"))
        assertEquals("7-day window", WidgetRenderer.windowName("7d"))
        assertEquals("weekly window", WidgetRenderer.windowName("weekly"))
        // Model-specific Claude windows keep their name and gain the period.
        assertEquals("Opus · 7-day", WidgetRenderer.windowName("Opus"))
        assertEquals("Sonnet · 7-day", WidgetRenderer.windowName("Sonnet"))
        // Codex derives its label from a duration, so arbitrary numbers must work.
        assertEquals("3-hour window", WidgetRenderer.windowName("3h"))
        assertEquals("30-day window", WidgetRenderer.windowName("30d"))
    }

    @Test
    fun `time remaining reads naturally at every scale`() {
        val now = System.currentTimeMillis()
        assertEquals("—", WidgetRenderer.left(0))
        assertEquals("now", WidgetRenderer.left(now - 1000))
        assertTrue(WidgetRenderer.left(now + 25 * 60_000).endsWith("m"))
        assertEquals("3h 40m", WidgetRenderer.left(now + (3 * 60 + 40) * 60_000 + 500))
        assertEquals("2d 5h", WidgetRenderer.left(now + (2 * 24 * 60 + 5 * 60) * 60_000 + 500))
    }

    @Test
    fun `a widget always fits the height it claims`() {
        // The invariant the layout rests on: whichever tier is chosen for a height,
        // two blocks plus padding and gap must not exceed that height.
        for (h in 40..320) {
            val tier = WidgetRenderer.tierFor(h.toFloat(), panels = 2)
            val needed = WidgetRenderer.neededHeight(tier, panels = 2)
            assertTrue(
                "tier $tier needs $needed but only $h available",
                tier == 0 || needed <= h,
            )
        }
    }

    @Test
    fun `taller widgets never pick a poorer layout`() {
        var previous = 0
        for (h in 40..320) {
            val tier = WidgetRenderer.tierFor(h.toFloat(), panels = 2)
            assertTrue("tier went backwards at ${h}dp", tier >= previous)
            previous = tier
        }
    }

    @Test
    fun `a single provider unlocks a richer layout sooner`() {
        // One panel needs half the block budget, so 100dp is enough for the full
        // treatment where two providers would still be stuck on compact.
        assertTrue(
            WidgetRenderer.tierFor(100f, panels = 1) >
                WidgetRenderer.tierFor(100f, panels = 2)
        )
    }

    @Test
    fun `plan names are tidied for display`() {
        assertEquals("Plus", WidgetRenderer.prettyPlan("plus"))
        assertEquals("Pro", WidgetRenderer.prettyPlan("PRO"))
        assertEquals("ChatGPT Business", WidgetRenderer.prettyPlan("chatgpt_business"))
        assertEquals("Team Seat", WidgetRenderer.prettyPlan("team-seat"))
        assertEquals(null, WidgetRenderer.prettyPlan(""))
        assertEquals(null, WidgetRenderer.prettyPlan(null))
    }

    @Test
    fun `the binding window is the fullest one, not the first`() {
        val now = System.currentTimeMillis()
        val state = ProviderState(
            true,
            listOf(
                Win("5h", 12, now + 3600_000),
                Win("7d", 81, now + 5 * 86_400_000L),
                Win("Opus", 44, now + 5 * 86_400_000L),
            ),
            null,
        )
        assertEquals("7d", WidgetRenderer.binding(state)?.label)
    }

    @Test
    fun `projection only warns when the cap arrives before the reset`() {
        val now = System.currentTimeMillis()
        // Climbing ~14 pct/hour from 40 to 68: 32 points left is a little over 2h.
        val climbing = (0..8).map { i ->
            (now - (8 - i) * 15 * 60_000L) to (40 + i * 3.5).toInt()
        }

        // Reset is 6h out, so the cap lands first — worth warning about.
        val soon = WidgetRenderer.projection(Win("5h", 68, now + 6 * 3600_000L), climbing)
        assertTrue("expected a projection", soon != null)
        assertTrue("projection should land before the reset", soon!! < now + 6 * 3600_000L)

        // Same burn, but the window resets in 20 minutes — nothing to warn about.
        val resetsFirst = WidgetRenderer.projection(Win("5h", 68, now + 20 * 60_000L), climbing)
        assertEquals(null, resetsFirst)

        // Flat usage never projects.
        val flat = (0..8).map { i -> (now - (8 - i) * 15 * 60_000L) to 68 }
        assertEquals(null, WidgetRenderer.projection(Win("5h", 68, now + 6 * 3600_000L), flat))

        // Too few samples to draw a line through.
        val sparse = listOf((now - 60_000L) to 60, now to 68)
        assertEquals(null, WidgetRenderer.projection(Win("5h", 68, now + 6 * 3600_000L), sparse))
    }

    @Test
    fun `providers cannot both be hidden`() {
        val o = WidgetRenderer.Opts(showClaude = true, showCodex = false)
        assertTrue(o.solo)
        assertFalse(WidgetRenderer.Opts().solo)
    }
}
