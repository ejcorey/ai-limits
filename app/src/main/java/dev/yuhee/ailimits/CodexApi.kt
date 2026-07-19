package dev.yuhee.ailimits

import android.content.Context
import android.util.Base64
import org.json.JSONObject
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Codex usage via the same OAuth flow + endpoint the Codex CLI uses.
 * The app runs its own PKCE login (browser + loopback callback on port 1455),
 * so its tokens are an independent grant — nothing is shared with the ChatGPT
 * app, the PC, or any other session.
 */
object CodexApi {
    const val CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"
    const val REDIRECT = "http://localhost:1455/auth/callback"
    private const val AUTHORIZE_URL = "https://auth.openai.com/oauth/authorize"
    private const val TOKEN_URL = "https://auth.openai.com/oauth/token"
    private const val USAGE_URL = "https://chatgpt.com/backend-api/wham/usage"
    private const val UA = "codex_cli_rs/0.50.0 (Android) ailimits"

    data class Flow(val verifier: String, val state: String, val url: String)

    private fun b64url(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

    /** Builds the authorize URL + PKCE material. Caller opens the URL and runs CodexLoginServer. */
    fun beginLogin(ctx: Context): Flow = beginLogin().also { Prefs.setCodexFlow(ctx, it.verifier, it.state) }

    private fun beginLogin(): Flow {
        val rnd = SecureRandom()
        val verifier = b64url(ByteArray(64).also { rnd.nextBytes(it) })
        val state = b64url(ByteArray(32).also { rnd.nextBytes(it) })
        val challenge = b64url(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        )
        fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
        val url = "$AUTHORIZE_URL?response_type=code" +
            "&client_id=${enc(CLIENT_ID)}" +
            "&redirect_uri=${enc(REDIRECT)}" +
            "&scope=${enc("openid profile email offline_access")}" +
            "&code_challenge=${enc(challenge)}" +
            "&code_challenge_method=S256" +
            "&id_token_add_organizations=true" +
            "&codex_cli_simplified_flow=true" +
            "&state=${enc(state)}" +
            "&originator=codex_cli_rs"
        return Flow(verifier, state, url)
    }

    /**
     * Fallback for when the loopback catch fails (e.g. Android killed the app while the
     * browser was open): the user pastes the localhost callback URL from the address bar.
     */
    fun finishLoginManual(ctx: Context, pasted: String) {
        val (verifier, storedState) = Prefs.codexFlow(ctx)
        require(!verifier.isNullOrEmpty()) { "No sign-in in progress — tap “Sign in with ChatGPT” first" }
        val text = pasted.trim()
        val query = text.substringAfter('?', text)
        val params = query.split('&').mapNotNull { kv ->
            val i = kv.indexOf('=')
            if (i <= 0) null else kv.substring(0, i) to java.net.URLDecoder.decode(kv.substring(i + 1), "UTF-8")
        }.toMap()
        val code = params["code"] ?: text.takeIf { !it.contains('/') && !it.contains('=') }
        require(!code.isNullOrEmpty()) { "No code found — paste the full localhost URL from the browser's address bar" }
        val state = params["state"]
        require(state == null || storedState == null || state == storedState) {
            "This link is from an older sign-in attempt — tap “Sign in with ChatGPT” and use the newest one"
        }
        exchangeCode(ctx, code, verifier)
    }

    /** Exchanges the authorization code for tokens and stores them. Throws with a readable message. */
    fun exchangeCode(ctx: Context, code: String, verifier: String) {
        val r = Net.postForm(
            TOKEN_URL,
            mapOf(
                "grant_type" to "authorization_code",
                "code" to code,
                "redirect_uri" to REDIRECT,
                "client_id" to CLIENT_ID,
                "code_verifier" to verifier,
            ),
            mapOf("User-Agent" to UA)
        )
        if (r.code !in 200..299) throw RuntimeException("Token exchange failed (HTTP ${r.code}): ${r.body.take(200)}")
        val j = JSONObject(r.body)
        val access = j.getString("access_token")
        val refresh = j.optString("refresh_token", "").ifEmpty { null }
        val idToken = j.optString("id_token", "").ifEmpty { null }
        val accountId = idToken
            ?.let { jwtClaim(it, "https://api.openai.com/auth") }
            ?.optString("chatgpt_account_id", "")
            ?.ifEmpty { null }
            ?: throw RuntimeException("Signed in, but no ChatGPT account id came back — try again")
        val expMs = jwtExpMs(access) ?: (System.currentTimeMillis() + j.optLong("expires_in", 864000) * 1000)
        Prefs.setCodexTokens(ctx, CodexTokens(access, refresh, idToken, accountId, expMs))
    }

    private fun jwtPayload(jwt: String): JSONObject? = try {
        val part = jwt.split(".")[1]
        val bytes = Base64.decode(part, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        JSONObject(String(bytes, Charsets.UTF_8))
    } catch (_: Exception) { null }

    private fun jwtClaim(jwt: String, key: String): JSONObject? = jwtPayload(jwt)?.optJSONObject(key)

    private fun jwtExpMs(jwt: String): Long? =
        jwtPayload(jwt)?.optLong("exp", 0)?.takeIf { it > 0 }?.times(1000)

    /** Returns valid tokens, refreshing if within 24h of expiry. */
    fun freshTokens(ctx: Context): CodexTokens {
        val t = Prefs.codexTokens(ctx) ?: throw RuntimeException("Not signed in")
        if (System.currentTimeMillis() < t.expMs - 24 * 3600 * 1000L) return t
        if (t.refresh == null) throw RuntimeException("Session expired — sign in again")
        return refresh(ctx, t)
    }

    // The refresh token ROTATES on every use: the new one is persisted immediately.
    fun refresh(ctx: Context, t: CodexTokens): CodexTokens {
        val body = JSONObject()
            .put("client_id", CLIENT_ID)
            .put("grant_type", "refresh_token")
            .put("refresh_token", t.refresh)
            .put("scope", "openid profile email")
        val r = Net.postJson(TOKEN_URL, body.toString(), mapOf("User-Agent" to UA))
        if (r.code !in 200..299) throw RuntimeException("Codex token refresh failed (HTTP ${r.code}) — sign in again")
        val j = JSONObject(r.body)
        val access = j.getString("access_token")
        val newT = CodexTokens(
            access = access,
            refresh = j.optString("refresh_token", "").ifEmpty { t.refresh },
            idToken = j.optString("id_token", "").ifEmpty { t.idToken },
            accountId = t.accountId,
            expMs = jwtExpMs(access) ?: (System.currentTimeMillis() + j.optLong("expires_in", 864000) * 1000),
        )
        Prefs.setCodexTokens(ctx, newT)
        return newT
    }

    /** Fetches usage windows. Retries once through a refresh on 401. */
    fun usage(ctx: Context): Pair<List<Win>, String?> {
        var t = freshTokens(ctx)
        var r = usageRequest(t)
        if (r.code == 401 && t.refresh != null) {
            t = refresh(ctx, t)
            r = usageRequest(t)
        }
        if (r.code !in 200..299) throw RuntimeException("Codex usage HTTP ${r.code}")
        return parseUsage(r.body)
    }

    private fun usageRequest(t: CodexTokens) = Net.get(
        USAGE_URL,
        mapOf(
            "Authorization" to "Bearer ${t.access}",
            "ChatGPT-Account-Id" to (t.accountId ?: ""),
            "originator" to "codex_cli_rs",
            "User-Agent" to UA,
        )
    )

    internal fun parseUsage(body: String): Pair<List<Win>, String?> {
        val j = JSONObject(body)
        val plan = j.optString("plan_type", "").ifEmpty { null }
        val rl = j.optJSONObject("rate_limit") ?: return Pair(emptyList(), plan)
        val out = mutableListOf<Win>()
        fun add(key: String) {
            val w = rl.optJSONObject(key) ?: return
            val pct = w.optDouble("used_percent", Double.NaN)
            if (pct.isNaN()) return
            val secs = w.optLong("limit_window_seconds", 0)
            val label = when {
                secs in 1 until 86400 -> "${Math.round(secs / 3600.0)}h"
                secs >= 86400 -> "${Math.round(secs / 86400.0)}d"
                else -> "now"
            }
            var resetMs = w.optLong("reset_at", 0) * 1000
            if (resetMs == 0L) {
                val after = w.optLong("reset_after_seconds", 0)
                if (after > 0) resetMs = System.currentTimeMillis() + after * 1000
            }
            out.add(Win(label, Math.round(pct).toInt().coerceIn(0, 100), resetMs))
        }
        add("primary_window")
        add("secondary_window")
        return Pair(out, plan)
    }
}
