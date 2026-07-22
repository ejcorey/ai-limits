package dev.yuhee.ailimits

import android.content.Context
import android.content.res.Configuration

enum class ThemeMode(val label: String) {
    AUTO("Follow system"),
    DARK("Always dark"),
    LIGHT("Always light");

    companion object {
        fun of(name: String?) = entries.firstOrNull { it.name == name } ?: AUTO
    }
}

/**
 * Everything the user can change about how the widgets look and behave.
 * Backed by the same SharedPreferences as [Prefs]; kept separate so the token
 * plumbing and the display options do not get tangled together.
 */
object Settings {

    private fun p(ctx: Context) =
        ctx.applicationContext.getSharedPreferences("ailimits", Context.MODE_PRIVATE)

    // --- which providers appear -------------------------------------------
    // Guarded so the pair can never both be off: a widget showing nothing is
    // not a state worth supporting.

    fun showClaude(ctx: Context): Boolean = p(ctx).getBoolean("show_claude", true)
    fun showCodex(ctx: Context): Boolean = p(ctx).getBoolean("show_codex", true)

    fun setShowClaude(ctx: Context, on: Boolean) {
        p(ctx).edit().putBoolean("show_claude", on).apply()
        if (!on && !showCodex(ctx)) p(ctx).edit().putBoolean("show_codex", true).apply()
    }

    fun setShowCodex(ctx: Context, on: Boolean) {
        p(ctx).edit().putBoolean("show_codex", on).apply()
        if (!on && !showClaude(ctx) && !showGemini(ctx)) p(ctx).edit().putBoolean("show_claude", true).apply()
    }

    // Gemini is opt-in (default off): Google publishes no usage-limit API, so its panel
    // is an honest presence card rather than a live percentage. An optional plan label
    // is all there is to show.
    fun showGemini(ctx: Context): Boolean = p(ctx).getBoolean("show_gemini", false)

    fun setShowGemini(ctx: Context, on: Boolean) {
        p(ctx).edit().putBoolean("show_gemini", on).apply()
        if (!on && !showClaude(ctx) && !showCodex(ctx)) p(ctx).edit().putBoolean("show_claude", true).apply()
    }

    /** Optional plan label the user can attach to the Gemini card (e.g. "AI Pro"). */
    fun geminiPlan(ctx: Context): String? =
        p(ctx).getString("gemini_plan", null)?.trim()?.ifEmpty { null }

    fun setGeminiPlan(ctx: Context, plan: String?) =
        p(ctx).edit().putString("gemini_plan", plan?.trim().orEmpty()).apply()

    /** How many providers are on. */
    fun shownCount(ctx: Context): Int =
        (if (showClaude(ctx)) 1 else 0) + (if (showCodex(ctx)) 1 else 0) + (if (showGemini(ctx)) 1 else 0)

    /** True when exactly one provider is on — the widget then gets a roomier layout. */
    fun solo(ctx: Context): Boolean = shownCount(ctx) == 1

    // --- appearance --------------------------------------------------------

    fun themeMode(ctx: Context): ThemeMode = ThemeMode.of(p(ctx).getString("theme_mode", null))
    fun setThemeMode(ctx: Context, m: ThemeMode) =
        p(ctx).edit().putString("theme_mode", m.name).apply()

    /** Card opacity in percent, 40..100. */
    fun opacity(ctx: Context): Int = p(ctx).getInt("opacity", 100).coerceIn(40, 100)
    fun setOpacity(ctx: Context, v: Int) = p(ctx).edit().putInt("opacity", v.coerceIn(40, 100)).apply()

    fun showProjection(ctx: Context): Boolean = p(ctx).getBoolean("show_projection", true)
    fun setShowProjection(ctx: Context, on: Boolean) =
        p(ctx).edit().putBoolean("show_projection", on).apply()

    fun showSparkline(ctx: Context): Boolean = p(ctx).getBoolean("show_sparkline", true)
    fun setShowSparkline(ctx: Context, on: Boolean) =
        p(ctx).edit().putBoolean("show_sparkline", on).apply()

    // --- notifications -----------------------------------------------------

    fun notifyEnabled(ctx: Context): Boolean = p(ctx).getBoolean("notify", false)
    fun setNotifyEnabled(ctx: Context, on: Boolean) = p(ctx).edit().putBoolean("notify", on).apply()

    /** Percent at which a window is worth interrupting the user for. */
    fun notifyThreshold(ctx: Context): Int = p(ctx).getInt("notify_at", 90).coerceIn(50, 100)
    fun setNotifyThreshold(ctx: Context, v: Int) =
        p(ctx).edit().putInt("notify_at", v.coerceIn(50, 100)).apply()

    /** Windows already announced, keyed to their reset time so each period fires once. */
    fun firedAlerts(ctx: Context): Set<String> =
        p(ctx).getStringSet("notify_fired", emptySet()) ?: emptySet()

    fun setFiredAlerts(ctx: Context, keys: Set<String>) =
        p(ctx).edit().putStringSet("notify_fired", keys).apply()

    // --- widget preview shown in the app -----------------------------------

    fun previewStyle(ctx: Context): WidgetRenderer.Style =
        runCatching { WidgetRenderer.Style.valueOf(p(ctx).getString("preview_style", "")!!) }
            .getOrDefault(WidgetRenderer.Style.DETAIL)

    fun setPreviewStyle(ctx: Context, s: WidgetRenderer.Style) =
        p(ctx).edit().putString("preview_style", s.name).apply()

    /**
     * A context whose resources resolve against the chosen theme, so an override
     * of dark/light reaches every `getColor` the renderer makes.
     */
    fun themedContext(ctx: Context): Context = when (themeMode(ctx)) {
        ThemeMode.AUTO -> ctx
        ThemeMode.DARK -> withNightMode(ctx, Configuration.UI_MODE_NIGHT_YES)
        ThemeMode.LIGHT -> withNightMode(ctx, Configuration.UI_MODE_NIGHT_NO)
    }

    private fun withNightMode(ctx: Context, mode: Int): Context {
        val cfg = Configuration(ctx.resources.configuration)
        cfg.uiMode = (cfg.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or mode
        return ctx.createConfigurationContext(cfg)
    }
}
