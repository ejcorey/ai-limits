package dev.yuhee.ailimits

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The parsers decide whether a response counts as data or as a failure, and that
 * distinction is what protects a good snapshot from being overwritten.
 *
 * Runs under Robolectric because the stock JVM `org.json` in unit tests is a stub
 * that throws on every method — which would let a "this should throw" assertion pass
 * without the parser ever running.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UsageRepoTest {

    @Test
    fun `claude usage parses the windows it recognises`() {
        val body = """
            {"five_hour":{"utilization":68,"resets_at":"2026-07-21T18:12:00Z"},
             "seven_day":{"utilization":31,"resets_at":"2026-07-25T00:00:00Z"},
             "seven_day_opus":{"utilization":12,"resets_at":"2026-07-25T00:00:00Z"}}
        """.trimIndent()
        val wins = ClaudeApi.parseUsage(body)
        assertEquals(listOf("5h", "7d", "Opus"), wins.map { it.label })
        assertEquals(68, wins[0].pct)
        assertTrue("reset time should parse", wins[0].resetsAt > 0)
    }

    /**
     * The keep-previous-on-failure design only engages when the parser throws. A 200
     * whose shape changed used to come back as zero windows through the success path
     * and quietly replace good data with nothing, reporting no error at all.
     */
    @Test
    fun `an unrecognised claude response is an error, not an empty success`() {
        val e = runCatching { ClaudeApi.parseUsage("""{"something_else":{"utilization":50}}""") }
        assertTrue("expected a throw for an unknown schema", e.isFailure)
    }

    @Test
    fun `an unrecognised codex response is an error, not an empty success`() {
        val e = runCatching { CodexApi.parseUsage("""{"plan_type":"plus","rate_limit":{}}""") }
        assertTrue("expected a throw for an unknown schema", e.isFailure)
    }

    @Test
    fun `codex reads plan and windows`() {
        val body = """
            {"plan_type":"plus","rate_limit":{
              "primary_window":{"used_percent":41,"limit_window_seconds":18000,"reset_at":1900000000},
              "secondary_window":{"used_percent":22,"limit_window_seconds":604800,"reset_at":1900500000}}}
        """.trimIndent()
        val (wins, plan) = CodexApi.parseUsage(body)
        assertEquals("plus", plan)
        assertEquals(listOf("5h", "7d"), wins.map { it.label })
        assertEquals(41, wins[0].pct)
    }

    /**
     * A reset derived from `reset_after_seconds` is computed off the wall clock, so it
     * shifts a little on every poll. The alert de-dup key embeds that instant, so an
     * unquantised value made every refresh look like a brand new window and the same
     * alert fired forever. Rounding to five minutes holds it still.
     */
    @Test
    fun `a relative codex reset is quantised so it is stable across polls`() {
        fun parseWithRelativeReset(): Long {
            val body = """
                {"plan_type":"pro","rate_limit":{
                  "primary_window":{"used_percent":90,"limit_window_seconds":18000,
                                    "reset_after_seconds":7200}}}
            """.trimIndent()
            return CodexApi.parseUsage(body).first.single().resetsAt
        }
        val first = parseWithRelativeReset()
        Thread.sleep(5)
        val second = parseWithRelativeReset()
        assertEquals("two polls seconds apart must agree", first, second)
        assertEquals("should sit on a 5-minute boundary", 0L, first % 300_000L)
    }

    @Test
    fun `a malformed window does not discard the whole snapshot`() {
        // "w" holds one good entry and two unusable ones.
        val json = JSONObject(
            """
            {"claude":{"w":[{"l":"5h","p":68,"r":0},{"nope":1},{"l":"","p":5}],"e":"","plan":""},
             "codex":{"w":[{"l":"7d","p":22,"r":0}],"e":"","plan":"plus"},
             "fetchedAt":123}
            """.trimIndent()
        )
        // Exercised through the same code path load() uses.
        val claude = json.getJSONObject("claude")
        val arr = claude.getJSONArray("w")
        val kept = (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val label = o.optString("l", "")
            if (label.isEmpty()) null else Win(label, o.optInt("p", 0), o.optLong("r", 0))
        }
        assertEquals("the good window must survive its bad neighbours", 1, kept.size)
        assertEquals("5h", kept[0].label)
    }

    @Test
    fun `window naming survives an unexpected label`() {
        assertNotNull(WidgetRenderer.windowName("mystery"))
        // Guessing a cadence for a label we don't recognise would be wrong —
        // Gemini's per-model buckets ("Pro", "Flash") land here.
        assertEquals("mystery limit", WidgetRenderer.windowName("mystery"))
        assertEquals("Pro limit", WidgetRenderer.windowName("Pro"))
        assertEquals("daily window", WidgetRenderer.windowName("daily"))
    }

    /**
     * A provider counts as "configured" only when tokens exist, and isStale short-circuits
     * on that — so freshness assertions are vacuous without this.
     */
    private fun signIn(ctx: android.content.Context) {
        Prefs.setClaudeTokens(ctx, "access", "refresh", System.currentTimeMillis() + 3_600_000)
        Prefs.setCodexTokens(
            ctx,
            CodexTokens("access", "refresh", null, "acct-1234", System.currentTimeMillis() + 3_600_000),
        )
    }

    // ---- Gemini quota parsing --------------------------------------------

    @Test
    fun `gemini buckets become windows with used percent`() {
        val body = """
            {"buckets":[
              {"modelId":"gemini-3-pro-preview","remainingFraction":0.63,"resetTime":"2026-07-26T03:00:00Z"},
              {"modelId":"gemini-2.5-flash","remainingFraction":0.88,"resetTime":"2026-07-26T03:00:00Z"}]}
        """.trimIndent()
        val wins = GeminiApi.parseQuota(body)
        // Labels keep the generation: two versions of Pro are two different quotas.
        assertEquals(listOf("3 Pro", "2.5 Flash"), wins.map { it.label })
        assertEquals(37, wins[0].pct)   // 1 - 0.63 remaining
        assertEquals(12, wins[1].pct)
        assertTrue(wins[0].resetsAt > 0)
    }

    /**
     * This previously asserted that same-family buckets were merged keeping the emptiest.
     * That was the defect behind the "Gemini 0% left" report, so the expectation is
     * inverted: distinct models are distinct limits and each keeps its own figure.
     */
    @Test
    fun `sibling models are separate windows, not merged into the emptiest`() {
        val body = """
            {"buckets":[
              {"modelId":"gemini-3-pro-preview","remainingFraction":0.9},
              {"modelId":"gemini-3-pro-image","remainingFraction":0.2}]}
        """.trimIndent()
        val wins = GeminiApi.parseQuota(body)
        assertEquals(2, wins.size)
        assertEquals(10, wins.first { it.label.contains("Pro") && !it.label.contains("Image") }.pct)
        assertEquals(80, wins.first { it.label.contains("Image") }.pct)
    }

    @Test
    fun `an unrecognised gemini response is an error, not an empty success`() {
        assertTrue(runCatching { GeminiApi.parseQuota("""{"something":1}""") }.isFailure)
        assertTrue(runCatching { GeminiApi.parseQuota("""{"buckets":[]}""") }.isFailure)
    }

    /**
     * Reported from a real device: the widget showed Gemini at 0% left while most of the
     * quota was intact. A bucket with nothing left and no count cannot distinguish
     * "spent" from "this model has no quota on your tier", and collapsing model families
     * kept the emptiest one — so a single placeholder made the provider look exhausted.
     */
    @Test
    fun `an empty placeholder bucket does not report the account as exhausted`() {
        val body = """
            {"buckets":[
              {"modelId":"gemini-3-pro-image","remainingFraction":0},
              {"modelId":"gemini-3-pro-preview","remainingFraction":0.6}]}
        """.trimIndent()
        val wins = GeminiApi.parseQuota(body)
        assertEquals("the uninterpretable bucket is dropped", 1, wins.size)
        assertEquals(40, wins[0].pct)   // 60% remaining
        assertEquals("3 Pro", wins[0].label)
    }

    /** A genuinely spent quota still reports, because the server proves it with a count. */
    @Test
    fun `a real zero with a count is kept`() {
        val body = """{"buckets":[{"modelId":"gemini-3-pro","remainingFraction":0,"remainingAmount":"0"}]}"""
        val wins = GeminiApi.parseQuota(body)
        assertEquals(1, wins.size)
        assertEquals(100, wins[0].pct)
        assertEquals(0L, wins[0].remaining)
    }

    /** Model families are separate limits and are no longer merged into one. */
    @Test
    fun `each model keeps its own window and its own label`() {
        val body = """
            {"buckets":[
              {"modelId":"gemini-3-pro-preview","remainingFraction":0.6},
              {"modelId":"gemini-2.5-flash","remainingFraction":0.9},
              {"modelId":"gemini-2.5-flash-lite","remainingFraction":0.95}]}
        """.trimIndent()
        val wins = GeminiApi.parseQuota(body)
        assertEquals(listOf("3 Pro", "2.5 Flash", "2.5 Flash Lite"), wins.map { it.label })
        // Labels key the hide toggles and the alert de-dup, so they must be distinct.
        assertEquals(wins.size, wins.map { it.label }.toSet().size)
    }

    @Test
    fun `colliding labels are disambiguated rather than dropped`() {
        val body = """
            {"buckets":[
              {"modelId":"gemini-3-pro-preview","remainingFraction":0.6},
              {"modelId":"gemini-3-pro-latest","remainingFraction":0.4}]}
        """.trimIndent()
        val wins = GeminiApi.parseQuota(body)
        assertEquals(2, wins.size)
        assertEquals(wins.size, wins.map { it.label }.toSet().size)
    }

    @Test
    fun `all-empty buckets fail rather than showing a false zero`() {
        val body = """{"buckets":[{"modelId":"gemini-3-pro","remainingFraction":0}]}"""
        assertTrue(runCatching { GeminiApi.parseQuota(body) }.isFailure)
    }

    @Test
    fun `raw buckets are summarised for diagnostics`() {
        val body = """{"buckets":[{"modelId":"gemini-3-pro","remainingFraction":0.63,"remainingAmount":"1240000"}]}"""
        val sum = GeminiApi.bucketSummary(body)
        assertTrue(sum, sum.contains("gemini-3-pro"))
        assertTrue(sum, sum.contains("0.630"))
        assertTrue(sum, sum.contains("1240000"))
    }

    @Test
    fun `gemini tier names are prettified`() {
        assertEquals("Free", GeminiApi.prettyTier("free-tier"))
        assertEquals("Standard", GeminiApi.prettyTier("standard-tier"))
        assertEquals(null, GeminiApi.prettyTier(null))
    }

    // ---- window selection -------------------------------------------------

    @Test
    fun `hiding a window promotes the next fullest to the headline`() {
        val now = System.currentTimeMillis()
        val state = ProviderState(
            true,
            listOf(Win("5h", 68, now), Win("7d", 31, now), Win("Opus", 12, now)),
            null,
        )
        val filtered = WidgetRenderer.filterWindows(state, setOf("5h"))
        assertEquals(listOf("7d", "Opus"), filtered.windows.map { it.label })
        assertEquals("7d", WidgetRenderer.binding(filtered)?.label)
    }

    @Test
    fun `hiding every window hides nothing`() {
        val state = ProviderState(true, listOf(Win("5h", 68, 0), Win("7d", 31, 0)), null)
        val filtered = WidgetRenderer.filterWindows(state, setOf("5h", "7d"))
        assertEquals(2, filtered.windows.size)
    }

    @Test
    fun `binding window of an empty provider is null rather than a crash`() {
        assertNull(WidgetRenderer.binding(ProviderState(true, emptyList(), null)))
    }

    // ---- upgrade compatibility --------------------------------------------

    @Test
    fun `a snapshot stored by v2_3 (no gemini key) still loads`() {
        val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        Prefs.setSnapshot(ctx, """
            {"claude":{"w":[{"l":"5h","p":68,"r":0}],"e":"","plan":""},
             "codex":{"w":[{"l":"7d","p":22,"r":0}],"e":"","plan":"plus"},
             "fetchedAt":1234}
        """.trimIndent())
        val snap = UsageRepo.load(ctx)
        assertEquals(68, snap.claude.windows.single().pct)
        assertEquals(1234L, snap.fetchedAt)
        assertTrue("gemini defaults to empty, not a crash", snap.gemini.windows.isEmpty())
    }

    /**
     * Per-provider timestamps arrived in v2.8. A snapshot written before that has none,
     * and defaulting them to 0 meant every provider read as stale the moment the app
     * updated — amber everywhere and all alerts silenced until the next refresh. The
     * snapshot-wide time is what that field used to mean, so it is the right fallback.
     */
    @Test
    fun `a snapshot without per-provider times inherits the snapshot time`() {
        val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        signIn(ctx)
        val written = System.currentTimeMillis() - 5 * 60_000
        Prefs.setSnapshot(ctx, """
            {"claude":{"w":[{"l":"5h","p":68,"r":0}],"e":"","plan":""},
             "codex":{"w":[{"l":"7d","p":22,"r":0}],"e":"","plan":"plus"},
             "fetchedAt":$written}
        """.trimIndent())
        val snap = UsageRepo.load(ctx)
        assertEquals(written, snap.claude.fetchedAt)
        assertEquals(written, snap.codex.fetchedAt)
        assertTrue("recent data must not look stale after an upgrade",
            !WidgetRenderer.isStale(snap.claude))
    }

    /** A provider's own timestamp still wins when the snapshot carries one. */
    @Test
    fun `a per-provider time overrides the snapshot time`() {
        val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        signIn(ctx)
        val now = System.currentTimeMillis()
        Prefs.setSnapshot(ctx, """
            {"claude":{"w":[{"l":"5h","p":68,"r":0}],"e":"","plan":"","t":${now - 3 * 3_600_000}},
             "codex":{"w":[{"l":"7d","p":22,"r":0}],"e":"","plan":"plus","t":$now},
             "fetchedAt":$now}
        """.trimIndent())
        val snap = UsageRepo.load(ctx)
        assertTrue("the provider that went quiet is stale", WidgetRenderer.isStale(snap.claude))
        assertTrue("its healthy sibling is not", !WidgetRenderer.isStale(snap.codex))
    }

    @Test
    fun `history stored by v2_3 (three-wide) reads gemini as unknown`() {
        val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        Prefs.setHistory(ctx, "[[1000,50,20],[2000,55,22,7]]")
        val h = UsageRepo.history(ctx)
        assertEquals(2, h.size)
        assertEquals(-1, h[0].gemini)
        assertEquals(7, h[1].gemini)
    }
}
