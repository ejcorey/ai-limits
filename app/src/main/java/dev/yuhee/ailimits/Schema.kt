package dev.yuhee.ailimits

import org.json.JSONArray
import org.json.JSONObject

/**
 * Records the shape of a usage response — field *names* only, never values.
 *
 * The point is discovery. Gemini publishes an absolute `remainingAmount`, so the widget
 * can show real token counts for it; Claude and Codex publish percentages in every field
 * this app reads, so it shows none for them. Rather than assume that stays true, each
 * refresh notes the keys it actually saw, and "Copy diagnostics" reports them. If a
 * provider starts returning a count, it shows up here instead of going unnoticed.
 *
 * Names-only is what makes the output safe to paste: no token, id, or number can ride
 * along in a bug report.
 */
object Schema {

    private const val MAX_KEYS = 40

    /** Dotted key paths present in [body], e.g. "rate_limit.primary_window.used_percent". */
    fun keysOf(body: String): String = try {
        val out = LinkedHashSet<String>()
        walk(JSONObject(body), "", out, 0)
        out.take(MAX_KEYS).joinToString(", ")
    } catch (_: Exception) {
        "unparseable"
    }

    private fun walk(node: Any?, prefix: String, out: MutableSet<String>, depth: Int) {
        if (depth > 4 || out.size >= MAX_KEYS) return
        when (node) {
            is JSONObject -> node.keys().forEach { k ->
                val path = if (prefix.isEmpty()) k else "$prefix.$k"
                val child = node.opt(k)
                if (child is JSONObject || child is JSONArray) {
                    walk(child, path, out, depth + 1)
                } else {
                    out.add(path)
                }
            }
            // Arrays are homogeneous in these payloads, so the first element describes
            // the rest; recording every index would just bloat the report.
            is JSONArray -> if (node.length() > 0) walk(node.opt(0), "$prefix[]", out, depth + 1)
        }
    }

    /** Best-effort: a failure to introspect must never break a refresh. */
    fun record(ctx: android.content.Context, provider: String, body: String) {
        runCatching { Prefs.setResponseKeys(ctx, provider, keysOf(body)) }
    }
}
