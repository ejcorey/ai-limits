package dev.yuhee.ailimits

import android.content.Context
import android.util.Base64
import org.json.JSONObject
import java.net.URLDecoder
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

    /**
     * The paste flow's redirect: a web page that prints the code for the user to copy.
     * Kept only as the fallback — see [loopbackRedirect].
     */
    internal const val MANUAL_REDIRECT = "https://console.anthropic.com/oauth/code/callback"

    /**
     * The redirect that removes the paste step: the browser is sent to a socket this app
     * is listening on, so the code arrives on its own.
     *
     * The host is the literal string `localhost` even though [ClaudeLoginServer] binds
     * `127.0.0.1` — redirect-URI matching is a string comparison and this is the exact
     * form the client is registered with. The port is whatever the OS handed out, which
     * is only safe because this client accepts any loopback port.
     */
    internal fun loopbackRedirect(port: Int) = "http://localhost:$port/callback"
    // Moved off console.anthropic.com ~2026-07: the old host now answers 429/404.
    private const val TOKEN_URL = "https://api.anthropic.com/v1/oauth/token"
    private const val USAGE_URL = "https://api.anthropic.com/api/oauth/usage"
    private const val SCOPE = "org:create_api_key user:profile user:inference"
    private const val UA = "claude-code/2.1.2"

    private fun b64url(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

    /**
     * Returns the URL to open in the browser; stores verifier, state and the redirect.
     *
     * @param port the port [ClaudeLoginServer] is already listening on, or null to use
     *   the console page and have the user paste the code back.
     * @param fixedState the state the loopback server is already set to verify. Supplied
     *   by the caller in that case because the server has to know it before the browser
     *   is ever opened; generated here for the paste flow, which has no server.
     */
    fun beginLogin(ctx: Context, port: Int?, fixedState: String? = null): String {
        val rnd = SecureRandom()
        val verifierBytes = ByteArray(32).also { rnd.nextBytes(it) }
        val verifier = b64url(verifierBytes)
        val stateBytes = ByteArray(16).also { rnd.nextBytes(it) }
        val state = fixedState ?: stateBytes.joinToString("") { "%02x".format(it) }
        val redirect = if (port != null) loopbackRedirect(port) else MANUAL_REDIRECT
        Prefs.setClaudeFlow(ctx, verifier, state, redirect)

        val challenge = b64url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
        fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
        return "https://claude.ai/oauth/authorize?code=true" +
            "&client_id=${enc(CLIENT_ID)}" +
            "&response_type=code" +
            "&redirect_uri=${enc(redirect)}" +
            "&scope=${enc(SCOPE)}" +
            "&code_challenge=${enc(challenge)}" +
            "&code_challenge_method=S256" +
            "&state=${enc(state)}"
    }

    /**
     * Completes a sign-in whose code was caught by [ClaudeLoginServer].
     * Returns false if nothing is pending. The code is single-use, so a failed exchange
     * drops it rather than retrying a spent code on every resume.
     */
    fun completePendingLogin(ctx: Context): Boolean {
        val code = Prefs.claudePendingCode(ctx) ?: return false
        val (verifier, state, redirect) = Prefs.claudeFlow(ctx)
        if (verifier.isNullOrEmpty()) {
            Prefs.clearClaudePending(ctx)
            throw RuntimeException("Sign-in state was lost — tap Sign in again")
        }
        try {
            exchange(ctx, code, state ?: "", verifier, redirect ?: MANUAL_REDIRECT)
        } finally {
            Prefs.clearClaudePending(ctx)
        }
        return true
    }

    /**
     * Fallback for when the loopback catch doesn't happen. Accepts either the console
     * page's "code#state", or the full `http://localhost:PORT/callback?code=…` URL from
     * the address bar when the browser reached the socket but the app had been killed.
     */
    fun finishLogin(ctx: Context, pasted: String) {
        val (verifier, storedState, storedRedirect) = Prefs.claudeFlow(ctx)
        require(!verifier.isNullOrEmpty()) { "No sign-in in progress — tap Sign in first" }
        val (code, state) = parseCallback(pasted, storedState)
        require(storedState.isNullOrEmpty() || state.isEmpty() || state == storedState) {
            "That code is from an older sign-in attempt — tap Sign in and use the newest one"
        }
        exchange(ctx, code, state, verifier, storedRedirect ?: MANUAL_REDIRECT)
    }

    /** Split out of [finishLogin] so both accepted shapes can be tested without a network. */
    internal fun parseCallback(pasted: String, storedState: String?): Pair<String, String> {
        val trimmed = pasted.trim()
        val code: String
        val state: String
        if (trimmed.contains("://") || trimmed.contains("code=")) {
            val query = trimmed.substringAfter('?', trimmed)
            val params = query.split('&').mapNotNull { kv ->
                val i = kv.indexOf('=')
                if (i <= 0) null else kv.substring(0, i) to URLDecoder.decode(kv.substring(i + 1), "UTF-8")
            }.toMap()
            code = params["code"].orEmpty().trim()
            state = params["state"]?.trim() ?: storedState.orEmpty()
        } else {
            // The console page prints "code#state".
            code = trimmed.substringBefore('#').substringBefore('&').trim()
            state = if (trimmed.contains('#')) trimmed.substringAfter('#').trim() else storedState.orEmpty()
        }
        require(code.isNotEmpty()) { "No code found in what you pasted" }
        return code to state
    }

    /**
     * The redirect_uri here must be byte-identical to the one the authorize URL carried,
     * which is why it is read back from prefs rather than taken from a constant.
     */
    private fun exchange(ctx: Context, code: String, state: String, verifier: String, redirect: String) {
        val body = JSONObject()
            .put("grant_type", "authorization_code")
            .put("client_id", CLIENT_ID)
            .put("code", code)
            .put("state", state)
            .put("code_verifier", verifier)
            .put("redirect_uri", redirect)
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
        Schema.record(ctx, "claude", r.body)
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
        // Claude's windows are fixed by the endpoint's own field names, so the span is
        // known exactly rather than parsed out of a label.
        fun add(key: String, label: String, lengthMs: Long) {
            val o = j.optJSONObject(key) ?: return
            val pct = o.optDouble("utilization", Double.NaN)
            if (pct.isNaN()) return
            out.add(
                Win(
                    label,
                    Math.round(pct).toInt().coerceIn(0, 100),
                    parseIso(o.optString("resets_at", "")),
                    lengthMs = lengthMs,
                )
            )
        }
        val fiveHours = 5 * 3600_000L
        val sevenDays = 7 * 86_400_000L
        add("five_hour", "5h", fiveHours)
        add("seven_day", "7d", sevenDays)
        add("seven_day_opus", "Opus", sevenDays)
        add("seven_day_sonnet", "Sonnet", sevenDays)
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
