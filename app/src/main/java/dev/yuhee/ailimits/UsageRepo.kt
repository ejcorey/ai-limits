package dev.yuhee.ailimits

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** One rate-limit window: label ("5h", "7d", "Opus"...), percent used, reset time (epoch ms, 0 = unknown). */
data class Win(val label: String, val pct: Int, val resetsAt: Long)

data class ProviderState(
    val configured: Boolean,
    val windows: List<Win>,
    val error: String?,
    val plan: String? = null,
)

data class Snapshot(
    val claude: ProviderState,
    val codex: ProviderState,
    val fetchedAt: Long,
)

object UsageRepo {

    fun load(ctx: Context): Snapshot {
        val raw = Prefs.snapshot(ctx) ?: return Snapshot(empty(ctx, "claude"), empty(ctx, "codex"), 0)
        return try {
            val j = JSONObject(raw)
            Snapshot(
                parseProvider(j.optJSONObject("claude"), configuredClaude(ctx)),
                parseProvider(j.optJSONObject("codex"), configuredCodex(ctx)),
                j.optLong("fetchedAt", 0)
            )
        } catch (_: Exception) {
            Snapshot(empty(ctx, "claude"), empty(ctx, "codex"), 0)
        }
    }

    private fun configuredClaude(ctx: Context) = Prefs.claudeTokens(ctx).first != null
    private fun configuredCodex(ctx: Context) = Prefs.codexTokens(ctx) != null

    private fun empty(ctx: Context, which: String): ProviderState {
        val conf = if (which == "claude") configuredClaude(ctx) else configuredCodex(ctx)
        return ProviderState(conf, emptyList(), null)
    }

    private fun parseProvider(j: JSONObject?, configured: Boolean): ProviderState {
        if (j == null) return ProviderState(configured, emptyList(), null)
        val wins = mutableListOf<Win>()
        val arr = j.optJSONArray("w") ?: JSONArray()
        for (i in 0 until arr.length()) {
            // Skip a malformed entry rather than letting it throw: the caller's catch
            // would discard the whole snapshot, losing the other provider too.
            val o = arr.optJSONObject(i) ?: continue
            val label = o.optString("l", "")
            if (label.isEmpty()) continue
            wins.add(Win(label, o.optInt("p", 0), o.optLong("r", 0)))
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
            arr.put(JSONObject().put("l", w.label).put("p", w.pct).put("r", w.resetsAt))
        }
        return JSONObject().put("w", arr).put("e", s.error ?: "").put("plan", s.plan ?: "")
    }

    /**
     * Fetches both providers (independently), keeping previous data on transient failures.
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

        // fetchedAt means "when this data was last actually retrieved". Stamping it on a
        // failed round would show hours-old numbers as current and make the widget's
        // stale warning unreachable, since that warning is keyed off this very field.
        val anyFresh = claudeOk || codexOk
        val fetchedAt = if (anyFresh) System.currentTimeMillis() else prev.fetchedAt
        val snap = Snapshot(claude, codex, fetchedAt)
        val j = JSONObject()
            .put("claude", providerJson(claude))
            .put("codex", providerJson(codex))
            .put("fetchedAt", fetchedAt)
        Prefs.setSnapshot(ctx, j.toString())
        if (anyFresh) appendHistory(ctx, claude, claudeOk, codex, codexOk, fetchedAt)
        return snap
    }

    /** History of binding-window utilization: (timeMs, claudePct, codexPct), -1 = unknown. */
    fun history(ctx: Context): List<Triple<Long, Int, Int>> {
        val raw = Prefs.history(ctx) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            // Drop only the bad samples; one corrupt element used to wipe the whole chart.
            (0 until arr.length()).mapNotNull { i ->
                val e = arr.optJSONArray(i) ?: return@mapNotNull null
                if (e.length() < 3) return@mapNotNull null
                Triple(e.optLong(0), e.optInt(1, -1), e.optInt(2, -1))
            }
        } catch (_: Exception) { emptyList() }
    }

    private fun appendHistory(
        ctx: Context,
        claude: ProviderState, claudeOk: Boolean,
        codex: ProviderState, codexOk: Boolean,
        t: Long,
    ) {
        // The fullest window is the one the widget shows, so it is the one worth tracking.
        // A provider that failed contributes -1 (unknown) rather than its carried-over
        // previous value — recording that as a fresh observation would invent a flat
        // stretch in the sparkline and feed the burn projection made-up data.
        fun pick(s: ProviderState, ok: Boolean): Int =
            if (!ok) -1 else s.windows.maxByOrNull { it.pct }?.pct ?: -1
        val keep = history(ctx).filter { it.first >= t - 48 * 3600 * 1000L }.takeLast(199)
        val arr = JSONArray()
        (keep + Triple(t, pick(claude, claudeOk), pick(codex, codexOk))).forEach { e ->
            arr.put(JSONArray().put(e.first).put(e.second).put(e.third))
        }
        Prefs.setHistory(ctx, arr.toString())
    }
}
