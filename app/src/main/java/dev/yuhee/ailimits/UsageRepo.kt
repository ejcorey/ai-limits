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
            val o = arr.getJSONObject(i)
            wins.add(Win(o.getString("l"), o.getInt("p"), o.optLong("r", 0)))
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

    /** Fetches both providers (independently), keeping previous data on transient failures. */
    fun fetchAll(ctx: Context): Snapshot {
        val prev = load(ctx)

        val claude: ProviderState = if (!configuredClaude(ctx)) {
            ProviderState(false, emptyList(), null)
        } else try {
            ProviderState(true, ClaudeApi.usage(ctx), null)
        } catch (e: Exception) {
            ProviderState(true, prev.claude.windows, e.message ?: "fetch failed")
        }

        val codex: ProviderState = if (!configuredCodex(ctx)) {
            ProviderState(false, emptyList(), null)
        } else try {
            val (wins, plan) = CodexApi.usage(ctx)
            ProviderState(true, wins, null, plan)
        } catch (e: Exception) {
            ProviderState(true, prev.codex.windows, e.message ?: "fetch failed", prev.codex.plan)
        }

        val snap = Snapshot(claude, codex, System.currentTimeMillis())
        val j = JSONObject()
            .put("claude", providerJson(claude))
            .put("codex", providerJson(codex))
            .put("fetchedAt", snap.fetchedAt)
        Prefs.setSnapshot(ctx, j.toString())
        return snap
    }
}
