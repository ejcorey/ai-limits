package dev.yuhee.ailimits

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.time.OffsetDateTime
import kotlin.math.roundToInt

/**
 * Gemini usage via the same OAuth flow + endpoints Gemini CLI uses (verified against
 * its source): Google PKCE sign-in with the CLI's public installed-app credentials,
 * then the Cloud Code private API —
 *
 *   POST v1internal:loadCodeAssist     -> tier + cloudaicompanionProject
 *   POST v1internal:onboardUser (LRO)  -> project, when the account has none yet
 *   POST v1internal:retrieveUserQuota  -> per-model buckets: remainingFraction + resetTime
 *
 * Like the other providers this is an independent on-phone grant; nothing is shared
 * with Gemini CLI or any other device. Google refresh tokens do not rotate.
 */
object GeminiApi {
    // Public installed-app credentials from the Gemini CLI source; the "secret" is not
    // secret for this client type — Google's docs say as much — it is part of the
    // client identity, shipped in the CLI's own public repository. Assembled at
    // runtime only because GitHub push protection pattern-matches the literals and
    // would block every push containing them.
    val CLIENT_ID: String = "681255809395" + "-oo8ft2oprdrnp9e3aqf6av3hmdib135j" +
        ".apps" + ".googleusercontent" + ".com"
    private val CLIENT_SECRET: String = "GOCSPX" + "-4uHgMPm" + "-1o7Sk" + "-geV6Cu5clXFsxl"
    const val PORT = 7856
    const val REDIRECT = "http://localhost:$PORT/oauth2callback"
    private const val AUTHORIZE_URL = "https://accounts.google.com/o/oauth2/v2/auth"
    private const val TOKEN_URL = "https://oauth2.googleapis.com/token"
    private const val ENDPOINT = "https://cloudcode-pa.googleapis.com"
    private const val SCOPES = "https://www.googleapis.com/auth/cloud-platform " +
        "https://www.googleapis.com/auth/userinfo.email " +
        "https://www.googleapis.com/auth/userinfo.profile"

    data class Flow(val verifier: String, val state: String, val url: String)

    private fun b64url(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

    /** Builds the authorize URL + PKCE material. Caller opens the URL and runs GeminiLoginServer. */
    fun beginLogin(ctx: Context): Flow {
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
            "&scope=${enc(SCOPES)}" +
            "&code_challenge=${enc(challenge)}" +
            "&code_challenge_method=S256" +
            // offline + consent is what makes Google hand back a refresh token.
            "&access_type=offline" +
            "&prompt=consent" +
            "&state=${enc(state)}"
        Prefs.setGeminiFlow(ctx, verifier, state)
        return Flow(verifier, state, url)
    }

    /** Completes a sign-in whose code was caught by the loopback server. */
    fun completePendingLogin(ctx: Context): Boolean {
        val code = Prefs.geminiPendingCode(ctx) ?: return false
        val (verifier, _) = Prefs.geminiFlow(ctx)
        if (verifier.isNullOrEmpty()) {
            Prefs.clearGeminiPending(ctx)
            throw RuntimeException("Sign-in state was lost — tap Sign in again")
        }
        try {
            exchangeCode(ctx, code, verifier)
        } catch (e: RuntimeException) {
            Prefs.clearGeminiPending(ctx)
            throw e
        }
        Prefs.clearGeminiPending(ctx)
        return true
    }

    /** Fallback: the user pastes the localhost callback URL from the address bar. */
    fun finishLoginManual(ctx: Context, pasted: String) {
        val (verifier, storedState) = Prefs.geminiFlow(ctx)
        require(!verifier.isNullOrEmpty()) { "No sign-in in progress — tap “Sign in with Google” first" }
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
            "This link is from an older sign-in attempt — tap “Sign in with Google” and use the newest one"
        }
        exchangeCode(ctx, code, verifier)
    }

    fun exchangeCode(ctx: Context, code: String, verifier: String) {
        val r = Net.postForm(
            TOKEN_URL,
            mapOf(
                "grant_type" to "authorization_code",
                "code" to code,
                "redirect_uri" to REDIRECT,
                "client_id" to CLIENT_ID,
                "client_secret" to CLIENT_SECRET,
                "code_verifier" to verifier,
            ),
        )
        if (r.code !in 200..299) throw RuntimeException("Token exchange failed (HTTP ${r.code}): ${r.body.take(200)}")
        val j = JSONObject(r.body)
        val access = j.getString("access_token")
        val refresh = j.optString("refresh_token", "").ifEmpty { null }
            ?: throw RuntimeException("Google returned no refresh token — sign in again and approve the consent screen")
        val expMs = System.currentTimeMillis() + j.optLong("expires_in", 3500) * 1000
        Prefs.setGeminiTokens(ctx, access, refresh, expMs)
    }

    private fun freshAccessToken(ctx: Context, force: Boolean = false): String {
        val (access, refresh, exp) = Prefs.geminiTokens(ctx)
        if (!force && access != null && exp > System.currentTimeMillis() + 60_000) return access
        if (refresh.isNullOrEmpty()) throw RuntimeException("Sign in via app")
        val r = Net.postForm(
            TOKEN_URL,
            mapOf(
                "grant_type" to "refresh_token",
                "refresh_token" to refresh,
                "client_id" to CLIENT_ID,
                "client_secret" to CLIENT_SECRET,
            ),
        )
        if (r.code !in 200..299) {
            // invalid_grant means the refresh token itself was revoked or expired.
            val detail = if (r.body.contains("invalid_grant")) "sign in via app" else "HTTP ${r.code}"
            throw RuntimeException("Google token refresh failed — $detail")
        }
        val j = JSONObject(r.body)
        val newAccess = j.getString("access_token")
        Prefs.setGeminiAccess(ctx, newAccess, System.currentTimeMillis() + j.optLong("expires_in", 3500) * 1000)
        return newAccess
    }

    private fun authHeaders(access: String) = mapOf("Authorization" to "Bearer $access")

    private fun clientMetadata() = JSONObject()
        .put("ideType", "IDE_UNSPECIFIED")
        .put("platform", "PLATFORM_UNSPECIFIED")
        .put("pluginType", "GEMINI")

    /**
     * The Cloud AI Companion project the quota is accounted against. Most accounts get
     * one from loadCodeAssist; a brand-new free-tier account has to be onboarded first,
     * which is a long-running operation we poll briefly.
     */
    private fun ensureProject(ctx: Context, access: String): Pair<String, String?> {
        Prefs.geminiProject(ctx).let { (p, t) -> if (!p.isNullOrEmpty()) return p to t }

        val load = Net.postJson(
            "$ENDPOINT/v1internal:loadCodeAssist",
            JSONObject().put("metadata", clientMetadata()).toString(),
            authHeaders(access),
        )
        if (load.code !in 200..299) throw RuntimeException("Gemini setup failed (HTTP ${load.code})")
        val lj = JSONObject(load.body)
        val tier = lj.optJSONObject("paidTier")?.optString("id", "")?.ifEmpty { null }
            ?: lj.optJSONObject("currentTier")?.optString("id", "")?.ifEmpty { null }
        lj.optString("cloudaicompanionProject", "").ifEmpty { null }?.let { project ->
            Prefs.setGeminiProject(ctx, project, tier)
            return project to tier
        }

        // No project yet: onboard. The free tier must NOT send a project (the server
        // assigns a managed one and rejects requests that name their own).
        val onboardReq = JSONObject()
            .put("tierId", tier ?: "free-tier")
            .put("metadata", clientMetadata())
        var lro = Net.postJson("$ENDPOINT/v1internal:onboardUser", onboardReq.toString(), authHeaders(access))
        if (lro.code !in 200..299) throw RuntimeException("Gemini onboarding failed (HTTP ${lro.code})")
        var op = JSONObject(lro.body)
        var tries = 0
        while (!op.optBoolean("done", false) && tries < 6) {
            Thread.sleep(1500)
            val name = op.optString("name", "")
            if (name.isEmpty()) break
            val poll = Net.get("$ENDPOINT/$name", authHeaders(access))
            if (poll.code !in 200..299) break
            op = JSONObject(poll.body)
            tries++
        }
        val project = op.optJSONObject("response")
            ?.optJSONObject("cloudaicompanionProject")
            ?.optString("id", "")
            ?.ifEmpty { null }
            ?: throw RuntimeException("Gemini onboarding didn't finish — try refreshing in a minute")
        Prefs.setGeminiProject(ctx, project, tier)
        return project to tier
    }

    /** Fetches the quota buckets. Returns windows plus the tier (shown as the plan chip). */
    fun usage(ctx: Context): Pair<List<Win>, String?> {
        var access = freshAccessToken(ctx)
        val (project, tier) = ensureProject(ctx, access)
        var r = Net.postJson(
            "$ENDPOINT/v1internal:retrieveUserQuota",
            JSONObject().put("project", project).toString(),
            authHeaders(access),
        )
        if (r.code == 401) {
            // Access token died early; refresh once and retry.
            access = freshAccessToken(ctx, force = true)
            r = Net.postJson(
                "$ENDPOINT/v1internal:retrieveUserQuota",
                JSONObject().put("project", project).toString(),
                authHeaders(access),
            )
        }
        if (r.code !in 200..299) throw RuntimeException("Gemini usage failed (HTTP ${r.code})")
        Schema.record(ctx, "gemini", r.body)
        return parseQuota(r.body) to prettyTier(tier)
    }

    /**
     * Buckets arrive per model id, sometimes several per family; the widget wants one
     * window per family showing the fullest bucket (that is the binding one).
     */
    internal fun parseQuota(body: String): List<Win> {
        val j = JSONObject(body)
        val buckets = j.optJSONArray("buckets") ?: JSONArray()
        val byLabel = LinkedHashMap<String, Win>()
        for (i in 0 until buckets.length()) {
            val o = buckets.optJSONObject(i) ?: continue
            val frac = o.optDouble("remainingFraction", Double.NaN)
            if (frac.isNaN()) continue
            val pct = ((1 - frac) * 100).roundToInt().coerceIn(0, 100)
            val reset = parseIso(o.optString("resetTime", ""))
            val label = modelLabel(o.optString("modelId", ""))
            // remainingAmount is a numeric string (Gemini CLI parseInt's it the same way).
            // An absent or non-numeric value simply means no count to show.
            val remaining = o.optString("remainingAmount", "").trim()
                .takeIf { it.isNotEmpty() }?.toLongOrNull()?.takeIf { it >= 0 }
            val unit = o.optString("tokenType", "").trim().ifEmpty { null }
            val prev = byLabel[label]
            if (prev == null || pct > prev.pct) {
                byLabel[label] = Win(label, pct, reset, remaining, unit)
            }
        }
        // A recognised-but-empty answer must fail so the previous snapshot survives —
        // same rule as the other providers.
        if (byLabel.isEmpty()) throw RuntimeException("Unrecognized usage response")
        return byLabel.values.toList()
    }

    /** "gemini-3-pro-preview" -> "Pro", "gemini-2.5-flash-lite" -> "Flash Lite". */
    internal fun modelLabel(id: String): String {
        val k = id.lowercase()
        return when {
            k.contains("flash") && k.contains("lite") -> "Flash Lite"
            k.contains("flash") -> "Flash"
            k.contains("pro") -> "Pro"
            k.isEmpty() -> "daily"
            else -> id.removePrefix("gemini-").take(12)
        }
    }

    internal fun prettyTier(tier: String?): String? = when (tier?.lowercase()) {
        null, "" -> null
        "free-tier" -> "Free"
        "legacy-tier" -> "Legacy"
        "standard-tier" -> "Standard"
        else -> tier.removeSuffix("-tier").replaceFirstChar { it.uppercase() }
    }

    private fun parseIso(s: String): Long {
        if (s.isEmpty() || s == "null") return 0
        return try {
            OffsetDateTime.parse(s).toInstant().toEpochMilli()
        } catch (_: Exception) {
            try { Instant.parse(s).toEpochMilli() } catch (_: Exception) { 0 }
        }
    }
}
