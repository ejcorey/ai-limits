package dev.yuhee.ailimits

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * One rate-limit window: label ("5h", "7d", "Opus", "Pro"...), percent used, reset
 * time (epoch ms, 0 = unknown).
 *
 * [remaining] is an absolute count still available — only ever set when the provider
 * actually reports one (today: Gemini's `remainingAmount`). It is deliberately null
 * for Claude and Codex, which publish percentages only; deriving a count from a
 * percentage would require inventing the denominator.
 */
data class Win(
    val label: String,
    val pct: Int,
    val resetsAt: Long,
    val remaining: Long? = null,
    val unit: String? = null,
)

data class ProviderState(
    val configured: Boolean,
    val windows: List<Win>,
    val error: String?,
    val plan: String? = null,
)

/**
 * Gemini sits last with a default so the many existing two-provider call sites
 * (and the stored-JSON format v2.3 wrote) stay valid.
 */
data class Snapshot(
    val claude: ProviderState,
    val codex: ProviderState,
    val fetchedAt: Long,
    val gemini: ProviderState = ProviderState(false, emptyList(), null),
)

/** One usage-history sample; -1 = that provider wasn't measured at this instant. */
data class HistoryPoint(val t: Long, val claude: Int, val codex: Int, val gemini: Int)

object UsageRepo {

    fun load(ctx: Context): Snapshot {
        val raw = Prefs.snapshot(ctx) ?: return empty(ctx)
        return try {
            val j = JSONObject(raw)
            Snapshot(
                parseProvider(j.optJSONObject("claude"), configuredClaude(ctx)),
                parseProvider(j.optJSONObject("codex"), configuredCodex(ctx)),
                j.optLong("fetchedAt", 0),
                // Absent in snapshots written before v2.4 — parses to an empty state.
                parseProvider(j.optJSONObject("gemini"), configuredGemini(ctx)),
            )
        } catch (_: Exception) {
            empty(ctx)
        }
    }

    private fun empty(ctx: Context) = Snapshot(
        ProviderState(configuredClaude(ctx), emptyList(), null),
        ProviderState(configuredCodex(ctx), emptyList(), null),
        0,
        ProviderState(configuredGemini(ctx), emptyList(), null),
    )

    private fun configuredClaude(ctx: Context) = Prefs.claudeTokens(ctx).first != null
    private fun configuredCodex(ctx: Context) = Prefs.codexTokens(ctx) != null
    private fun configuredGemini(ctx: Context) = Prefs.geminiTokens(ctx).first != null

    private fun parseProvider(j: JSONObject?, configured: Boolean): ProviderState {
        if (j == null) return ProviderState(configured, emptyList(), null)
        val wins = mutableListOf<Win>()
        val arr = j.optJSONArray("w") ?: JSONArray()
        for (i in 0 until arr.length()) {
            // Skip a malformed entry rather than letting it throw: the caller's catch
            // would discard the whole snapshot, losing the other providers too.
            val o = arr.optJSONObject(i) ?: continue
            val label = o.optString("l", "")
            if (label.isEmpty()) continue
            wins.add(
                Win(
                    label,
                    o.optInt("p", 0),
                    o.optLong("r", 0),
                    // -1 is the on-disk marker for "not reported", so a real 0 remaining
                    // (genuinely exhausted) stays distinguishable from absent.
                    o.optLong("n", -1L).takeIf { it >= 0 },
                    o.optString("u", "").ifEmpty { null },
                )
            )
        }
        return ProviderState(
            configured,
            wins,
            j.optString("e", "").ifEmpty { null },
            j.optString("plan", "").ifEmpty { null })
    }

    private fun providerJson(s: ProviderState): JSONObject {
        val arr = JSONArray()
        s.windows.forEach { w ->
            arr.put(
                JSONObject().put("l", w.label).put("p", w.pct).put("r", w.resetsAt)
                    .put("n", w.remaining ?: -1L).put("u", w.unit ?: "")
            )
        }
        return JSONObject().put("w", arr).put("e", s.error ?: "").put("plan", s.plan ?: "")
    }

    /**
     * Fetches all providers (independently), keeping previous data on transient failures.
     *
     * Synchronized because the worker and a manual refresh from the app can run this
     * concurrently: it is a read-modify-write over SharedPreferences, and the Codex
     * refresh token rotates on use, so two overlapping refreshes can invalidate the
     * token the loser is about to send and sign the user out.
     */
    @Synchronized
    fun fetchAll(ctx: Context): Snapshot {
        val prev = load(ctx)
        var claudeOk = false
        var codexOk = false
        var geminiOk = false

        val claude: ProviderState = if (!configuredClaude(ctx)) {
            ProviderState(false, emptyList(), null)
        } else try {
            val wins = ClaudeApi.usage(ctx)
            claudeOk = true
            ProviderState(true, wins, null)
        } catch (e: Exception) {
            ProviderState(true, prev.claude.windows, e.message ?: "fetch failed")
        }

        val codex: ProviderState = if (!configuredCodex(ctx)) {
            ProviderState(false, emptyList(), null)
        } else try {
            val (wins, plan) = CodexApi.usage(ctx)
            codexOk = true
            ProviderState(true, wins, null, plan)
        } catch (e: Exception) {
            ProviderState(true, prev.codex.windows, e.message ?: "fetch failed", prev.codex.plan)
        }

        val gemini: ProviderState = if (!configuredGemini(ctx)) {
            ProviderState(false, emptyList(), null)
        } else try {
            val (wins, tier) = GeminiApi.usage(ctx)
            geminiOk = true
            ProviderState(true, wins, null, tier)
        } catch (e: Exception) {
            ProviderState(true, prev.gemini.windows, e.message ?: "fetch failed", prev.gemini.plan)
        }

        // fetchedAt means "when this data was last actually retrieved". Stamping it on a
        // failed round would show hours-old numbers as current and make the widget's
        // stale warning unreachable, since that warning is keyed off this very field.
        val anyFresh = claudeOk || codexOk || geminiOk
        val fetchedAt = if (anyFresh) System.currentTimeMillis() else prev.fetchedAt
        val snap = Snapshot(claude, codex, fetchedAt, gemini)
        val j = JSONObject()
            .put("claude", providerJson(claude))
            .put("codex", providerJson(codex))
            .put("gemini", providerJson(gemini))
            .put("fetchedAt", fetchedAt)
        Prefs.setSnapshot(ctx, j.toString())
        if (anyFresh) {
            appendHistory(ctx, snap, claudeOk, codexOk, geminiOk, fetchedAt)
        }
        return snap
    }

    /** History of binding-window utilization. Stored as [t, claude, codex, gemini]; -1 = unknown. */
    fun history(ctx: Context): List<HistoryPoint> {
        val raw = Prefs.history(ctx) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            // Drop only the bad samples; one corrupt element used to wipe the whole chart.
            // Three-element arrays are what v2.3 and earlier wrote — gemini reads as -1.
            (0 until arr.length()).mapNotNull { i ->
                val e = arr.optJSONArray(i) ?: return@mapNotNull null
                if (e.length() < 3) return@mapNotNull null
                HistoryPoint(e.optLong(0), e.optInt(1, -1), e.optInt(2, -1), e.optInt(3, -1))
            }
        } catch (_: Exception) { emptyList() }
    }

    private fun appendHistory(
        ctx: Context,
        snap: Snapshot,
        claudeOk: Boolean,
        codexOk: Boolean,
        geminiOk: Boolean,
        t: Long,
    ) {
        // The fullest window is the one the widget shows, so it is the one worth tracking.
        // A provider that failed contributes -1 (unknown) rather than its carried-over
        // previous value — recording that as a fresh observation would invent a flat
        // stretch in the sparkline and feed the burn projection made-up data.
        fun pick(s: ProviderState, ok: Boolean): Int =
            if (!ok) -1 else s.windows.maxByOrNull { it.pct }?.pct ?: -1
        val keep = history(ctx).filter { it.t >= t - 48 * 3600 * 1000L }.takeLast(199)
        val arr = JSONArray()
        (keep + HistoryPoint(t, pick(snap.claude, claudeOk), pick(snap.codex, codexOk), pick(snap.gemini, geminiOk)))
            .forEach { e ->
                arr.put(JSONArray().put(e.t).put(e.claude).put(e.codex).put(e.gemini))
            }
        Prefs.setHistory(ctx, arr.toString())
    }
}
