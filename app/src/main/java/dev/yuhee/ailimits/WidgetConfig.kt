package dev.yuhee.ailimits

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * One placed widget's own display choices.
 *
 * **Every field is nullable and null means "inherit the app-wide setting".** That is the
 * whole safety property of this class: widgets placed before per-instance config existed
 * have no record at all, and must keep rendering exactly as they did. A version of this
 * with non-null defaults would silently revert every existing widget on update — a
 * provider switched off, opacity back to 100, hidden windows reappearing — which is the
 * one way this feature can do real damage.
 */
data class WidgetConfig(
    val style: WidgetRenderer.Style? = null,
    val showClaude: Boolean? = null,
    val showCodex: Boolean? = null,
    val opacity: Int? = null,
    val projection: Boolean? = null,
    val sparkline: Boolean? = null,
    val tokens: Boolean? = null,
    val pace: Boolean? = null,
    val hiddenClaude: Set<String>? = null,
    val hiddenCodex: Set<String>? = null,
) {
    /** True when nothing is overridden, so the record is worth deleting rather than storing. */
    val inheritsEverything: Boolean
        get() = style == null && showClaude == null && showCodex == null &&
            opacity == null && projection == null && sparkline == null && tokens == null &&
            pace == null && hiddenClaude == null && hiddenCodex == null

    fun toJson(): String {
        val j = JSONObject()
        style?.let { j.put("style", it.name) }
        showClaude?.let { j.put("cl", it) }
        showCodex?.let { j.put("cx", it) }
        opacity?.let { j.put("op", it) }
        projection?.let { j.put("proj", it) }
        sparkline?.let { j.put("spark", it) }
        tokens?.let { j.put("tok", it) }
        pace?.let { j.put("pace", it) }
        hiddenClaude?.let { j.put("hcl", JSONArray(it.toList())) }
        hiddenCodex?.let { j.put("hcx", JSONArray(it.toList())) }
        return j.toString()
    }

    companion object {
        fun fromJson(raw: String): WidgetConfig = try {
            val j = JSONObject(raw)
            // optString/optBoolean can't express "absent", and absent is the meaningful
            // state here, so every read goes through an explicit has() check.
            fun bool(k: String): Boolean? = if (j.has(k)) j.optBoolean(k) else null
            fun strings(k: String): Set<String>? {
                val a = j.optJSONArray(k) ?: return null
                return (0 until a.length()).mapNotNull { a.optString(it, null) }.toSet()
            }
            WidgetConfig(
                style = j.optString("style", "").takeIf { it.isNotEmpty() }
                    ?.let { name -> WidgetRenderer.Style.entries.firstOrNull { it.name == name } },
                showClaude = bool("cl"),
                showCodex = bool("cx"),
                opacity = if (j.has("op")) j.optInt("op").coerceIn(40, 100) else null,
                projection = bool("proj"),
                sparkline = bool("spark"),
                tokens = bool("tok"),
                pace = bool("pace"),
                hiddenClaude = strings("hcl"),
                hiddenCodex = strings("hcx"),
            )
        } catch (_: Exception) {
            // A record we cannot read must not take the widget down with it.
            WidgetConfig()
        }
    }
}

/** Per-widget records, one JSON blob per appWidgetId, in the app's existing prefs file. */
object WidgetConfigStore {

    private const val PREFIX = "w_"

    private fun p(ctx: Context) =
        ctx.applicationContext.getSharedPreferences("ailimits", Context.MODE_PRIVATE)

    /** Null when this widget has no record — i.e. it inherits everything. */
    fun load(ctx: Context, id: Int): WidgetConfig? =
        p(ctx).getString(PREFIX + id, null)?.let { WidgetConfig.fromJson(it) }

    fun save(ctx: Context, id: Int, cfg: WidgetConfig) {
        if (cfg.inheritsEverything) delete(ctx, id)
        else p(ctx).edit().putString(PREFIX + id, cfg.toJson()).apply()
    }

    fun delete(ctx: Context, id: Int) = p(ctx).edit().remove(PREFIX + id).apply()

    /** Every widget id we hold a record for. */
    fun storedIds(ctx: Context): Set<Int> =
        p(ctx).all.keys.mapNotNull { k ->
            if (k.startsWith(PREFIX)) k.removePrefix(PREFIX).toIntOrNull() else null
        }.toSet()

    /**
     * Drops records for widgets that no longer exist.
     *
     * `onDeleted` alone is not enough: it isn't delivered when a launcher's data is
     * cleared, and hosts recycle appWidgetIds — so a record left behind would eventually
     * apply someone's old configuration to a brand-new widget.
     */
    fun reap(ctx: Context, liveIds: Set<Int>) {
        val stale = storedIds(ctx) - liveIds
        if (stale.isEmpty()) return
        val e = p(ctx).edit()
        stale.forEach { e.remove(PREFIX + it) }
        e.apply()
    }
}
