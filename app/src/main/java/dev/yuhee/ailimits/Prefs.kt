package dev.yuhee.ailimits

import android.content.Context
import android.content.SharedPreferences

object Prefs {
    private fun p(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences("ailimits", Context.MODE_PRIVATE)

    // --- Claude OAuth ---
    fun claudeTokens(ctx: Context): Triple<String?, String?, Long> {
        val p = p(ctx)
        return Triple(p.getString("cl_access", null), p.getString("cl_refresh", null), p.getLong("cl_exp", 0))
    }

    fun setClaudeTokens(ctx: Context, access: String, refresh: String?, expMs: Long) {
        p(ctx).edit().putString("cl_access", access).putString("cl_refresh", refresh).putLong("cl_exp", expMs).apply()
    }

    fun clearClaude(ctx: Context) {
        p(ctx).edit().remove("cl_access").remove("cl_refresh").remove("cl_exp")
            .remove("cl_verifier").remove("cl_state").remove("keys_claude").apply()
    }

    // PKCE in-flight state (survives process death while browser is open)
    fun setClaudeFlow(ctx: Context, verifier: String, state: String) {
        p(ctx).edit().putString("cl_verifier", verifier).putString("cl_state", state).apply()
    }

    fun claudeFlow(ctx: Context): Pair<String?, String?> =
        Pair(p(ctx).getString("cl_verifier", null), p(ctx).getString("cl_state", null))

    // --- Codex OAuth ---
    fun codexTokens(ctx: Context): CodexTokens? {
        val p = p(ctx)
        val access = p.getString("cx_access", null) ?: return null
        return CodexTokens(
            access,
            p.getString("cx_refresh", null),
            p.getString("cx_id", null),
            p.getString("cx_account", null),
            p.getLong("cx_exp", 0)
        )
    }

    fun setCodexTokens(ctx: Context, t: CodexTokens) {
        p(ctx).edit()
            .putString("cx_access", t.access)
            .putString("cx_refresh", t.refresh)
            .putString("cx_id", t.idToken)
            .putString("cx_account", t.accountId)
            .putLong("cx_exp", t.expMs)
            .apply()
    }

    fun clearCodex(ctx: Context) {
        p(ctx).edit().remove("cx_access").remove("cx_refresh").remove("cx_id")
            .remove("cx_account").remove("cx_exp")
            .remove("cx_verifier").remove("cx_state").remove("cx_pending")
            .remove("keys_codex").apply()
    }

    // PKCE in-flight state (survives process death while browser is open)
    fun setCodexFlow(ctx: Context, verifier: String, state: String) {
        p(ctx).edit().putString("cx_verifier", verifier).putString("cx_state", state).apply()
    }

    fun codexFlow(ctx: Context): Pair<String?, String?> =
        Pair(p(ctx).getString("cx_verifier", null), p(ctx).getString("cx_state", null))

    // Authorization code caught by the loopback server, waiting for a foreground exchange
    // (Samsung blocks background network, so the exchange must happen once the app resumes).
    fun setCodexPendingCode(ctx: Context, code: String) =
        p(ctx).edit().putString("cx_pending", code).apply()

    fun codexPendingCode(ctx: Context): String? = p(ctx).getString("cx_pending", null)

    fun clearCodexPending(ctx: Context) = p(ctx).edit().remove("cx_pending").apply()

    // --- Gemini OAuth (Google) ---
    fun geminiTokens(ctx: Context): Triple<String?, String?, Long> {
        val p = p(ctx)
        return Triple(p.getString("gm_access", null), p.getString("gm_refresh", null), p.getLong("gm_exp", 0))
    }

    fun setGeminiTokens(ctx: Context, access: String, refresh: String?, expMs: Long) {
        p(ctx).edit().putString("gm_access", access).putString("gm_refresh", refresh).putLong("gm_exp", expMs).apply()
    }

    /** Google refresh tokens don't rotate, but the access token does; update it alone. */
    fun setGeminiAccess(ctx: Context, access: String, expMs: Long) {
        p(ctx).edit().putString("gm_access", access).putLong("gm_exp", expMs).apply()
    }

    fun clearGemini(ctx: Context) {
        p(ctx).edit().remove("gm_access").remove("gm_refresh").remove("gm_exp")
            .remove("gm_verifier").remove("gm_state").remove("gm_pending")
            .remove("gm_project").remove("gm_tier").remove("keys_gemini").apply()
    }

    // PKCE in-flight state (survives process death while browser is open)
    fun setGeminiFlow(ctx: Context, verifier: String, state: String) {
        p(ctx).edit().putString("gm_verifier", verifier).putString("gm_state", state).apply()
    }

    fun geminiFlow(ctx: Context): Pair<String?, String?> =
        Pair(p(ctx).getString("gm_verifier", null), p(ctx).getString("gm_state", null))

    fun setGeminiPendingCode(ctx: Context, code: String) =
        p(ctx).edit().putString("gm_pending", code).apply()

    fun geminiPendingCode(ctx: Context): String? = p(ctx).getString("gm_pending", null)

    fun clearGeminiPending(ctx: Context) = p(ctx).edit().remove("gm_pending").apply()

    // The Cloud AI Companion project + tier from loadCodeAssist — stable per account,
    // cached so usage polls don't repeat the onboarding round-trip.
    fun geminiProject(ctx: Context): Pair<String?, String?> =
        Pair(p(ctx).getString("gm_project", null), p(ctx).getString("gm_tier", null))

    fun setGeminiProject(ctx: Context, project: String, tier: String?) =
        p(ctx).edit().putString("gm_project", project).putString("gm_tier", tier).apply()

    /**
     * Field names seen in the last usage response of each provider — names only, never
     * values, so it is safe to paste into a bug report. This is how we find out whether
     * Claude or Codex ever start publishing token counts: today they expose percentages
     * only, and the alternative to looking would be guessing.
     */
    fun responseKeys(ctx: Context, provider: String): String? =
        p(ctx).getString("keys_$provider", null)

    fun setResponseKeys(ctx: Context, provider: String, keys: String) =
        // Marked when cut, so a truncated tail is never mistaken for a real field name.
        p(ctx).edit()
            .putString("keys_$provider", if (keys.length <= 600) keys else keys.take(597) + "…")
            .apply()

    // --- Last usage snapshot (JSON) ---
    fun snapshot(ctx: Context): String? = p(ctx).getString("snapshot", null)
    fun setSnapshot(ctx: Context, json: String) = p(ctx).edit().putString("snapshot", json).apply()

    // --- Settings ---
    fun refreshMinutes(ctx: Context): Int = p(ctx).getInt("refresh_min", 30)
    fun setRefreshMinutes(ctx: Context, m: Int) = p(ctx).edit().putInt("refresh_min", m).apply()

    // --- Usage history for the graph widget (JSON array of [t, claudePct, codexPct]) ---
    fun history(ctx: Context): String? = p(ctx).getString("history", null)
    fun setHistory(ctx: Context, json: String) = p(ctx).edit().putString("history", json).apply()
}

data class CodexTokens(
    val access: String,
    val refresh: String?,
    val idToken: String?,
    val accountId: String?,
    val expMs: Long,
)
