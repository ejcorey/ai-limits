package dev.yuhee.ailimits

import org.json.JSONArray
import org.json.JSONObject

/**
 * Records the shape of a usage response — field *names* only, never values.
 *
 * The point is discovery. A provider that starts publishing an absolute count would let
 * the widget
 * can show real token counts for it; Claude and Codex publish percentages in every field
 * this app reads, so it shows none for them. Rather than assume that stays true, each
 * refresh notes the keys it actually saw, and "Copy diagnostics" reports them. If a
 * provider starts returning a count, it shows up here instead of going unnoticed.
 *
 * A JSON *name* is still server-controlled data, so it is not trusted either: a payload
 * keyed by account id, project resource name or email address would otherwise put that
 * identifier into text offered for pasting into a bug report. Any segment that does not
 * look like a field name is replaced with `<redacted>` rather than reproduced.
 */
object Schema {

    private const val MAX_KEYS = 40
    private const val MAX_SEGMENT = 40

    /** Ordinary identifier shapes. Anything else is treated as data, not a field name. */
    private val NAME = Regex("^[A-Za-z_][A-Za-z0-9_]{0,${MAX_SEGMENT - 1}}$")

    /**
     * Collects paths under a hard cap, and remembers that it hit the cap. Bounding the
     * set as it grows keeps a pathological response from building a huge collection on
     * the network thread; the flag is what stops a cut list from being mistaken for the
     * whole shape, which a size comparison could not do once the set was already capped.
     */
    private class Acc {
        val keys = LinkedHashSet<String>()
        var truncated = false
        val full: Boolean get() = keys.size >= MAX_KEYS

        fun add(path: String) {
            if (keys.contains(path)) return
            if (full) truncated = true else keys.add(path)
        }
    }

    /** Dotted key paths present in [body], e.g. "rate_limit.primary_window.used_percent". */
    fun keysOf(body: String): String = try {
        val acc = Acc()
        walk(JSONObject(body), "", acc, 0)
        (acc.keys.toList() + if (acc.truncated) listOf("… more, truncated") else emptyList())
            .joinToString(", ")
    } catch (_: Exception) {
        "unparseable"
    }

    private fun safe(segment: String): String =
        if (NAME.matches(segment)) segment else "<redacted>"

    private fun walk(node: Any?, prefix: String, out: Acc, depth: Int) {
        // A silent depth cut is the same defect the truncation flag exists to prevent.
        if (depth > 5) { out.truncated = true; return }
        if (out.full) { out.truncated = true; return }
        when (node) {
            is JSONObject -> {
                if (!node.keys().hasNext() && prefix.isNotEmpty()) {
                    // An empty object still tells us the field exists.
                    out.add(prefix)
                    return
                }
                node.keys().forEach { k ->
                    if (out.full) { out.truncated = true; return }
                    val path = if (prefix.isEmpty()) safe(k) else "$prefix.${safe(k)}"
                    when (val child = node.opt(k)) {
                        is JSONObject, is JSONArray -> walk(child, path, out, depth + 1)
                        else -> out.add(path)
                    }
                }
            }
            is JSONArray -> {
                if (node.length() == 0) {
                    if (prefix.isNotEmpty()) out.add("$prefix[]")
                    return
                }
                // Every element, not just the first: these payloads are only mostly
                // homogeneous. A provider may treat a count as optional per bucket, so
                // sampling element 0 alone could miss the very field this exists to find.
                for (i in 0 until node.length()) {
                    if (out.full) { out.truncated = true; return }
                    when (val child = node.opt(i)) {
                        is JSONObject, is JSONArray -> walk(child, "$prefix[]", out, depth + 1)
                        // An array of scalars has no names of its own; record the container.
                        else -> out.add("$prefix[]")
                    }
                }
            }
        }
    }

    /** Best-effort: a failure to introspect must never break a refresh. */
    fun record(ctx: android.content.Context, provider: String, body: String) {
        runCatching { Prefs.setResponseKeys(ctx, provider, keysOf(body)) }
    }
}
