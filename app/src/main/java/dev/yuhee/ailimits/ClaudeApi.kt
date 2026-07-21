package dev.yuhee.ailimits

import android.content.Context
import android.util.Base64
import org.json.JSONObject
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.time.OffsetDateTime

/**
 * Claude usage via the same OAuth flow + usage endpoint Claude Code uses.
 * The app signs in with its own PKCE flow, so it never touches other devices' sessions.
 */
object ClaudeApi {
    private const val CLIENT_ID = "9d1c250a-e61b-44d9-88ed-5944d1962f5e"
    private const val REDIRECT = "https://console.anthropic.com/oauth/code/callback"
    // Moved off console.anthropic.com ~2026-07: the old host now answers 429/404.
    private const val TOKEN_URL = "https://api.anthropic.com/v1/oauth/token"
    private const val USAGE_URL = "https://api.anthropic.com/api/oauth/usage"
    private const val SCOPE = "org:create_api_key user:profile user:inference"
    private const val UA = "claude-code/2.1.2"

    private fun b64url(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

    /** Returns the URL to open in the browser; stores verifier+state in prefs. */
    fun beginLogin(ctx: Context): String {
        val rnd = SecureRandom()
        val verifierBytes = ByteArray(32).also { rnd.nextBytes(it) }
        val verifier = b64url(verifierBytes)
        val stateBytes = ByteArray(16).also { rnd.nextBytes(it) }
        val state = stateBytes.joinToString("") { "%02x".format(it) }
        Prefs.setClaudeFlow(ctx, verifier, state)

        val challenge = b64url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
        fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
        return "https://claude.ai/oauth/authorize?code=true" +
            "&client_id=${enc(CLIENT_ID)}" +
            "&response_type=code" +
            "&redirect_uri=${enc(REDIRECT)}" +
            "&scope=${enc(SCOPE)}" +
            "&code_challenge=${enc(challenge)}" +
            "&code_challenge_method=S256" +
            "&state=${enc(state)}"
    }

    /** Exchanges the pasted "code#state" for tokens and stores them. Throws with a readable message on failure. */
    fun finishLogin(ctx: Context, pasted: String) {
        val (verifier, storedState) = Prefs.claudeFlow(ctx)
        require(!verifier.isNullOrEmpty()) { "No sign-in in progress — tap Sign in first" }
        val trimmed = pasted.trim()
        val code = trimmed.substringBefore('#').substringBefore('&').trim()
        val state = if (trimmed.contains('#')) trimmed.substringAfter('#').trim() else (storedState ?: "")
        require(code.isNotEmpty()) { "Empty code" }

        val body = JSONObject()
            .put("grant_type", "authorization_code")
            .put("client_id", CLIENT_ID)
            .put("code", code)
            .put("state", state)
            .put("code_verifier", verifier)
            .put("redirect_uri", REDIRECT)
        val r = Net.postJson(TOKEN_URL, body.toString(), mapOf("User-Agent" to UA))
        if (r.code !in 200..299) throw RuntimeException("Token exchange failed (HTTP ${r.code}): ${r.body.take(200)}")
        val j = JSONObject(r.body)
        val expMs = System.currentTimeMillis() + j.optLong("expires_in", 3600) * 1000
        Prefs.setClaudeTokens(ctx, j.getString("access_token"), j.optString("refresh_token", null), expMs)
    }

    /** Refreshes if expired/near expiry. Returns a valid access token or throws. */
    fun freshAccessToken(ctx: Context): String {
        val (access, refresh, exp) = Prefs.claudeTokens(ctx)
        if (access == null) throw RuntimeException("Not signed in")
        if (System.currentTimeMillis() < exp - 10 * 60 * 1000) return access
        if (refresh == null) throw RuntimeException("Session expired — sign in again")
        return refresh(ctx, refresh)
    }

    fun refresh(ctx: Context, refreshToken: String): String {
        val body = JSONObject()
            .put("grant_type", "refresh_token")
            .put("client_id", CLIENT_ID)
            .put("refresh_token", refreshToken)
        val r = Net.postJson(TOKEN_URL, body.toString(), mapOf("User-Agent" to UA))
        if (r.code !in 200..299) throw RuntimeException("Claude token refresh failed (HTTP ${r.code}) — sign in again")
        val j = JSONObject(r.body)
        val access = j.getString("access_token")
        val newRefresh = j.optString("refresh_token", "").ifEmpty { refreshToken }
        val expMs = System.currentTimeMillis() + j.optLong("expires_in", 3600) * 1000
        Prefs.setClaudeTokens(ctx, access, newRefresh, expMs)
        return access
    }

    /** Fetches usage windows. Retries once through a refresh on 401. */
    fun usage(ctx: Context): List<Win> {
        var token = freshAccessToken(ctx)
        var r = usageRequest(token)
        if (r.code == 401) {
            val (_, refresh, _) = Prefs.claudeTokens(ctx)
            if (refresh != null) {
                token = refresh(ctx, refresh)
                r = usageRequest(token)
            }
        }
        if (r.code !in 200..299) throw RuntimeException("Claude usage HTTP ${r.code}")
        return parseUsage(r.body)
    }

    private fun usageRequest(token: String) = Net.get(
        USAGE_URL,
        mapOf(
            "Authorization" to "Bearer $token",
            "anthropic-beta" to "oauth-2025-04-20",
            "User-Agent" to UA,
        )
    )

    internal fun parseUsage(body: String): List<Win> {
        val j = JSONObject(body)
        val out = mutableListOf<Win>()
        fun add(key: String, label: String) {
            val o = j.optJSONObject(key) ?: return
            val pct = o.optDouble("utilization", Double.NaN)
            if (pct.isNaN()) return
            out.add(Win(label, Math.round(pct).toInt().coerceIn(0, 100), parseIso(o.optString("resets_at", ""))))
        }
        add("five_hour", "5h")
        add("seven_day", "7d")
        add("seven_day_opus", "Opus")
        add("seven_day_sonnet", "Sonnet")
        // A 200 whose shape we no longer recognise must fail, not succeed with nothing:
        // the caller only preserves the previous snapshot when this throws, so returning
        // an empty list would quietly overwrite good data and report no error.
        if (out.isEmpty()) throw RuntimeException("Unrecognized usage response")
        return out
    }

    private fun parseIso(s: String): Long {
        if (s.isEmpty() || s == "null") return 0
        return try {
            OffsetDateTime.parse(s).toInstant().toEpochMilli()
        } catch (_: Exception) {
            try {
                Instant.parse(s).toEpochMilli()
            } catch (_: Exception) {
                // tolerate malformed variants like "...+00:00Z"
                try { OffsetDateTime.parse(s.removeSuffix("Z")).toInstant().toEpochMilli() } catch (_: Exception) { 0 }
            }
        }
    }
}
