package dev.yuhee.ailimits

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure decisions behind the widget: what a window is called, which one leads,
 * and how long until it resets. No Android needed.
 */
class WidgetLogicTest {

    // --- what a null projection is allowed to mean ------------------------
    // Runway used to read "all within budget" whenever no lane projected, but
    // projection() also returns null for too few samples, too short a span, and a burn
    // below its noise floor. canProject separates "measured and safe" from "cannot say".

    @Test
    fun `canProject is false without enough recent samples`() {
        val now = System.currentTimeMillis()
        assertFalse("no history at all", WidgetRenderer.canProject(emptyList(), now))
        assertFalse(
            "two samples is not enough to fit a line",
            WidgetRenderer.canProject(listOf(now - 60 * 60_000L to 10, now to 40), now)
        )
        assertFalse(
            "three samples inside 15 minutes span too little time",
            WidgetRenderer.canProject(
                listOf(now - 14 * 60_000L to 10, now - 7 * 60_000L to 20, now to 30), now
            )
        )
        // Samples older than the 110-minute window do not count toward the total.
        assertFalse(
            "old samples must not prop up a thin recent window",
            WidgetRenderer.canProject(
                listOf(now - 5 * 3600_000L to 1, now - 4 * 3600_000L to 2, now to 30), now
            )
        )
    }

    @Test
    fun `canProject is true once there is a real recent span`() {
        val now = System.currentTimeMillis()
        assertTrue(
            WidgetRenderer.canProject(
                listOf(now - 90 * 60_000L to 10, now - 45 * 60_000L to 30, now to 50), now
            )
        )
    }

    /**
     * The case that made the old headline lie: a window pinned at 100% has a burn rate of
     * zero, so it projects nothing — yet it is the state the user most needs told about.
     */
    @Test
    fun `a maxed-out window projects nothing even though it is measurable`() {
        val now = System.currentTimeMillis()
        val flatAtFull = listOf(
            now - 90 * 60_000L to 100, now - 45 * 60_000L to 100, now to 100
        )
        assertTrue("the samples themselves are fine", WidgetRenderer.canProject(flatAtFull, now))
        assertNull(
            "a flat line has no slope to project along",
            WidgetRenderer.projection(Win("5h", 100, now + 3 * 3600_000L), flatAtFull)
        )
    }

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

    // ---- v2.7 metrics -----------------------------------------------------

    @Test
    fun `counts are formatted compactly`() {
        assertEquals("0", WidgetRenderer.compactCount(0))
        assertEquals("950", WidgetRenderer.compactCount(950))
        assertEquals("1K", WidgetRenderer.compactCount(1_000))
        assertEquals("12.4K", WidgetRenderer.compactCount(12_400))
        assertEquals("3.2M", WidgetRenderer.compactCount(3_200_000))
        assertEquals("1B", WidgetRenderer.compactCount(1_000_000_000))
    }

    /**
     * The rule that keeps this honest: a count is shown only when the provider sent one.
     * Claude/Codex windows carry no amount, so nothing must be conjured for them.
     */
    @Test
    fun `a count is only shown when the provider reported one`() {
        assertEquals(null, WidgetRenderer.remainingText(Win("5h", 68, 0)))
        assertEquals(
            "1.2M tokens left",
            WidgetRenderer.remainingText(Win("Pro", 40, 0, remaining = 1_200_000, unit = "TOKENS")),
        )
        // A genuinely exhausted window is a real zero, not "unknown".
        assertEquals(
            "0 requests left",
            WidgetRenderer.remainingText(Win("Pro", 100, 0, remaining = 0, unit = "requests")),
        )
        // An unfamiliar unit is echoed rather than dropped or guessed at.
        assertEquals(
            "500 widgets left",
            WidgetRenderer.remainingText(Win("Pro", 50, 0, remaining = 500, unit = "widgets")),
        )
    }

    @Test
    fun `pace compares spend against the elapsed window`() {
        val now = 1_700_000_000_000L
        val fiveH = 5 * 3600_000L
        // Halfway through a 5-hour window having spent 50% is exactly on pace.
        val half = Win("5h", 50, now + fiveH / 2)
        assertEquals(1.0f, WidgetRenderer.pace(half, now)!!, 0.02f)
        assertEquals("on pace", WidgetRenderer.paceText(half, now)!!.first)

        // Same point in the window but 90% spent: badly ahead, and worth colouring.
        val hot = Win("5h", 90, now + fiveH / 2)
        assertTrue(WidgetRenderer.pace(hot, now)!! > 1.7f)
        assertTrue("a steep pace should be flagged", WidgetRenderer.paceText(hot, now)!!.second)

        // Barely used at the same point: under pace.
        assertEquals("under pace", WidgetRenderer.paceText(Win("5h", 10, now + fiveH / 2), now)!!.first)
    }

    @Test
    fun `pace stays silent when it would be meaningless`() {
        val now = 1_700_000_000_000L
        val fiveH = 5 * 3600_000L
        // Just after a reset any usage divides by almost zero — 1% in would read as 20x.
        assertEquals(null, WidgetRenderer.pace(Win("5h", 1, now + fiveH - 60_000), now))
        // Unknown window length means no elapsed fraction.
        assertEquals(null, WidgetRenderer.pace(Win("Pro", 50, now + fiveH), now))
        // No reset time at all.
        assertEquals(null, WidgetRenderer.pace(Win("5h", 50, 0), now))
    }

    @Test
    fun `window lengths are known only for labelled durations`() {
        assertEquals(5 * 3600_000L, WidgetRenderer.windowLengthMs("5h"))
        assertEquals(7 * 86_400_000L, WidgetRenderer.windowLengthMs("7d"))
        assertEquals(7 * 86_400_000L, WidgetRenderer.windowLengthMs("weekly"))
        assertEquals(null, WidgetRenderer.windowLengthMs("Opus"))
        assertEquals(null, WidgetRenderer.windowLengthMs("Pro"))
    }

    /**
     * The bitmap budget is what keeps a RemoteViews payload clear of
     * TransactionTooLargeException. It must hold across the whole declared resize
     * range — the old stepped loop bottomed out early and quietly broke this.
     */
    @Test
    fun `bitmap stays inside its pixel budget at every declared size`() {
        for (density in floatArrayOf(1.5f, 2f, 2.625f, 3f)) {
            var w = 90
            while (w <= 640) {
                var h = 36
                while (h <= 480) {
                    val s = WidgetRenderer.scaleFor(density, w.toFloat(), h.toFloat())
                    val px = w * s * h * s
                    assertTrue(
                        "${w}x$h @${density} -> scale $s gives ${px.toInt()}px, over budget",
                        px <= WidgetRenderer.PIXEL_BUDGET * 1.001f,
                    )
                    assertTrue("scale must stay legible", s >= 1f)
                    h += 37
                }
                w += 55
            }
        }
    }

    @Test
    fun `small widgets still render at full device density`() {
        // The clamp must only bite on large widgets, never soften a 4x1.
        assertEquals(3f, WidgetRenderer.scaleFor(3f, 250f, 60f))
    }

    @Test
    fun `reset gains a weekday once it is far out`() {
        val now = 1_700_000_000_000L
        // Hours away: a bare clock is unambiguous.
        assertFalse(WidgetRenderer.resetClock(now + 3 * 3600_000L, now).contains(" "))
        // Days away: "22:19" alone would not say which day.
        assertTrue(WidgetRenderer.resetClock(now + 3 * 86_400_000L, now).contains(" "))
        assertEquals("--:--", WidgetRenderer.resetClock(0, now))
    }

    /**
     * A zero-length window divided by zero, and because every NaN comparison is false
     * the "too early to judge" guard let the result through as a confident "on pace".
     */
    @Test
    fun `a zero-length window yields no pace rather than a wrong one`() {
        val now = 1_700_000_000_000L
        assertEquals(null, WidgetRenderer.windowLengthMs("0h"))
        assertEquals(null, WidgetRenderer.pace(Win("0h", 60, now + 10 * 60_000), now))
        assertEquals(null, WidgetRenderer.paceText(Win("0h", 60, now + 10 * 60_000), now))
    }

    /** A window that already reset describes the previous period, so it says nothing. */
    @Test
    fun `an expired window yields no pace`() {
        val now = 1_700_000_000_000L
        val expired = Win("5h", 90, now - 3 * 3600_000L, lengthMs = 5 * 3600_000L)
        assertEquals(null, WidgetRenderer.pace(expired, now))
        assertEquals(null, WidgetRenderer.paceText(expired, now))
    }

    /** The carried length wins over the label, which Codex rounds. */
    @Test
    fun `pace uses the reported span, not the rounded label`() {
        val now = 1_700_000_000_000L
        val thirtySix = 36 * 3600_000L
        // Labelled "2d" by Codex's rounding, but truly 36h and only just started.
        val w = Win("2d", 50, now + thirtySix - 60_000, lengthMs = thirtySix)
        assertEquals("just-started window must stay silent", null, WidgetRenderer.pace(w, now))
        // Without the carried length the label would have implied 48h and spoken.
        assertEquals(48 * 3600_000L, WidgetRenderer.windowLengthMs("2d"))
    }

    /** This figure is what is LEFT, so rounding up would promise capacity that is gone. */
    @Test
    fun `counts round down, never up`() {
        assertEquals("1.9K", WidgetRenderer.compactCount(1_950))
        assertEquals("999.9K", WidgetRenderer.compactCount(999_999))
        assertEquals("1M", WidgetRenderer.compactCount(1_000_000))
        assertEquals("3.2M", WidgetRenderer.compactCount(3_299_999))
    }

    @Test
    fun `a provider whose data stopped arriving is marked stale`() {
        val now = 1_700_000_000_000L
        val wins = listOf(Win("5h", 68, now + 3600_000L))
        assertTrue(WidgetRenderer.isStale(ProviderState(true, wins, null, null, now - 3 * 3600_000L), now))
        assertFalse(WidgetRenderer.isStale(ProviderState(true, wins, null, null, now - 60_000L), now))
        // Never-fetched or signed-out providers are not "stale", they are simply empty.
        assertFalse(WidgetRenderer.isStale(ProviderState(false, emptyList(), null), now))
    }

    @Test
    fun `providers cannot both be hidden`() {
        val o = WidgetRenderer.Opts(showClaude = true, showCodex = false)
        assertTrue(o.solo)
        assertFalse(WidgetRenderer.Opts().solo)
    }
}
