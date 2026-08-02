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
    /**
     * How long the window spans, when the provider tells us. Carried rather than
     * inferred from [label]: Codex rounds its label from `limit_window_seconds`, so a
     * 36-hour window arrives labelled "2d" and anything under 30 minutes becomes "0h".
     * Deriving a length from that produced wrong answers and, for "0h", a divide by zero.
     * Null means genuinely unknown, and everything that needs a length stays silent.
     */
    val lengthMs: Long? = null,
)

data class ProviderState(
    val configured: Boolean,
    val windows: List<Win>,
    val error: String?,
    val plan: String? = null,
    /**
     * When THIS provider's numbers were last actually retrieved. Per-provider because a
     * single snapshot-wide timestamp let one dead provider hide behind its healthy
     * siblings: its stale percentages were drawn under a fresh "updated 14:32".
     * 0 means never.
     */
    val fetchedAt: Long = 0,
)

data class Snapshot(
    val claude: ProviderState,
    val codex: ProviderState,
    val fetchedAt: Long,
)

/** One usage-history sample; -1 = that provider wasn't measured at this instant. */
data class HistoryPoint(val t: Long, val claude: Int, val codex: Int)

object UsageRepo {

    fun load(ctx: Context): Snapshot {
        val raw = Prefs.snapshot(ctx) ?: return empty(ctx)
        return try {
            val j = JSONObject(raw)
            val snapshotTime = j.optLong("fetchedAt", 0)
            // A snapshot written while Gemini existed still carries a "gemini" object;
            // it is simply not read. Nothing has to be migrated for that.
            Snapshot(
                parseProvider(j.optJSONObject("claude"), configuredClaude(ctx), snapshotTime),
                parseProvider(j.optJSONObject("codex"), configuredCodex(ctx), snapshotTime),
                snapshotTime,
            )
        } catch (_: Exception) {
            empty(ctx)
        }
    }

    private fun empty(ctx: Context) = Snapshot(
        ProviderState(configuredClaude(ctx), emptyList(), null),
        ProviderState(configuredCodex(ctx), emptyList(), null),
        0,
    )

    private fun configuredClaude(ctx: Context) = Prefs.claudeTokens(ctx).first != null
    private fun configuredCodex(ctx: Context) = Prefs.codexTokens(ctx) != null

    /**
     * @param snapshotTime when the snapshot as a whole was written. Used as the fallback
     * for a provider that has no timestamp of its own, which is every provider in a
     * snapshot written before per-provider times existed. Defaulting those to 0 marked
     * the whole app stale the moment it was updated, and silenced alerts until the next
     * refresh — the snapshot-wide time is exactly what that field used to mean.
     */
    private fun parseProvider(j: JSONObject?, configured: Boolean, snapshotTime: Long = 0): ProviderState {
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
                    o.optLong("len", 0L).takeIf { it > 0 },
                )
            )
        }
        return ProviderState(
            configured,
            wins,
            j.optString("e", "").ifEmpty { null },
            j.optString("plan", "").ifEmpty { null },
            j.optLong("t", snapshotTime),
        )
    }

    private fun providerJson(s: ProviderState): JSONObject {
        val arr = JSONArray()
        s.windows.forEach { w ->
            arr.put(
                JSONObject().put("l", w.label).put("p", w.pct).put("r", w.resetsAt)
                    .put("n", w.remaining ?: -1L).put("u", w.unit ?: "")
                    .put("len", w.lengthMs ?: 0L)
            )
        }
        return JSONObject().put("w", arr).put("e", s.error ?: "").put("plan", s.plan ?: "")
            .put("t", s.fetchedAt)
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
        val now = System.currentTimeMillis()
        var claudeOk = false
        var codexOk = false

        val claude: ProviderState = if (!configuredClaude(ctx)) {
            ProviderState(false, emptyList(), null)
        } else try {
            val wins = ClaudeApi.usage(ctx)
            claudeOk = true
            ProviderState(true, wins, null, null, now)
        } catch (e: Exception) {
            // Keep the last good numbers, but keep their original timestamp with them so
            // the widget can show that this provider specifically has gone quiet.
            ProviderState(true, prev.claude.windows, e.message ?: "fetch failed", null, prev.claude.fetchedAt)
        }

        val codex: ProviderState = if (!configuredCodex(ctx)) {
            ProviderState(false, emptyList(), null)
        } else try {
            val (wins, plan) = CodexApi.usage(ctx)
            codexOk = true
            ProviderState(true, wins, null, plan, now)
        } catch (e: Exception) {
            ProviderState(true, prev.codex.windows, e.message ?: "fetch failed", prev.codex.plan, prev.codex.fetchedAt)
        }

        // fetchedAt means "when this data was last actually retrieved". Stamping it on a
        // failed round would show hours-old numbers as current and make the widget's
        // stale warning unreachable, since that warning is keyed off this very field.
        val anyFresh = claudeOk || codexOk
        val fetchedAt = if (anyFresh) now else prev.fetchedAt
        val snap = Snapshot(claude, codex, fetchedAt)
        val j = JSONObject()
            .put("claude", providerJson(claude))
            .put("codex", providerJson(codex))
            .put("fetchedAt", fetchedAt)
        Prefs.setSnapshot(ctx, j.toString())
        if (anyFresh) {
            appendHistory(ctx, snap, claudeOk, codexOk, fetchedAt)
        }
        return snap
    }

    /**
     * Drops one provider's stored numbers. Signing out used to leave them in the
     * snapshot; every style then kept painting the last-known percentage, and the
     * staleness check could not flag it because that short-circuits on !configured.
     */
    @Synchronized
    fun forget(ctx: Context, provider: String) {
        val raw = Prefs.snapshot(ctx) ?: return
        runCatching {
            val j = JSONObject(raw)
            j.put(provider, JSONObject().put("w", JSONArray()).put("e", "").put("plan", "").put("t", 0))
            Prefs.setSnapshot(ctx, j.toString())
        }
    }

    /**
     * History of binding-window utilization. Stored as [t, claude, codex]; -1 = unknown.
     * Samples written while Gemini existed have a fourth element, which is ignored — the
     * leading elements have never changed meaning, so old history stays readable.
     */
    fun history(ctx: Context): List<HistoryPoint> {
        val raw = Prefs.history(ctx) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            // Drop only the bad samples; one corrupt element used to wipe the whole chart.
            (0 until arr.length()).mapNotNull { i ->
                val e = arr.optJSONArray(i) ?: return@mapNotNull null
                if (e.length() < 3) return@mapNotNull null
                HistoryPoint(e.optLong(0), e.optInt(1, -1), e.optInt(2, -1))
            }
        } catch (_: Exception) { emptyList() }
    }

    private fun appendHistory(
        ctx: Context,
        snap: Snapshot,
        claudeOk: Boolean,
        codexOk: Boolean,
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
        (keep + HistoryPoint(t, pick(snap.claude, claudeOk), pick(snap.codex, codexOk)))
            .forEach { e ->
                arr.put(JSONArray().put(e.t).put(e.claude).put(e.codex))
            }
        Prefs.setHistory(ctx, arr.toString())
    }
}
