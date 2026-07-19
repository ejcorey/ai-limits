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
            .remove("cl_verifier").remove("cl_state").apply()
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
            .remove("cx_verifier").remove("cx_state").remove("cx_pending").apply()
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
