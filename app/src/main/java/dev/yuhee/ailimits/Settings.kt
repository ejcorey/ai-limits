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

    fun setShowClaude(ctx: Context, on: Boolean) = setShown(ctx, "show_claude", on)

    fun setShowCodex(ctx: Context, on: Boolean) = setShown(ctx, "show_codex", on)

    /**
     * Turning the last visible provider off is refused — it just comes back on. Written
     * once for all three: the per-provider versions had drifted, and the Claude one
     * checked only Codex, so hiding Claude force-enabled Codex even when Gemini was
     * already showing. That pushed a "tap to sign in" panel onto laid-out widgets.
     */
    private fun setShown(ctx: Context, key: String, on: Boolean) {
        p(ctx).edit().putBoolean(key, on).apply()
        if (!on && !showClaude(ctx) && !showCodex(ctx) && !showGemini(ctx)) {
            p(ctx).edit().putBoolean(key, true).apply()
        }
    }

    // Gemini is opt-in (default off) so updating the app cannot dump a "tap to sign
    // in" panel onto widgets people already laid out; signing in turns it on.
    fun showGemini(ctx: Context): Boolean = p(ctx).getBoolean("show_gemini", false)

    fun setShowGemini(ctx: Context, on: Boolean) = setShown(ctx, "show_gemini", on)

    /** How many providers are on. */
    fun shownCount(ctx: Context): Int =
        (if (showClaude(ctx)) 1 else 0) + (if (showCodex(ctx)) 1 else 0) + (if (showGemini(ctx)) 1 else 0)

    /** True when exactly one provider is on — the widget then gets a roomier layout. */
    fun solo(ctx: Context): Boolean = shownCount(ctx) == 1

    // --- which windows appear, per provider --------------------------------
    // Stored as the labels the user has HIDDEN, so a brand-new window from the
    // API shows up by default instead of being invisible until found in settings.
    // Keys: "cl", "cx", "gm".

    fun hiddenWindows(ctx: Context, key: String): Set<String> =
        // getStringSet's return must never be mutated or handed back to putStringSet,
        // so both directions copy.
        HashSet(p(ctx).getStringSet("hide_$key", emptySet()) ?: emptySet())

    fun setHiddenWindows(ctx: Context, key: String, hidden: Set<String>) =
        p(ctx).edit().putStringSet("hide_$key", HashSet(hidden)).apply()

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

    /**
     * Prefer an absolute count over a percentage where the provider reports one.
     * Only Gemini does today, so this changes nothing for a Claude/Codex-only setup.
     */
    fun showTokens(ctx: Context): Boolean = p(ctx).getBoolean("show_tokens", true)
    fun setShowTokens(ctx: Context, on: Boolean) =
        p(ctx).edit().putBoolean("show_tokens", on).apply()

    /** Compare spend against how much of the window has elapsed. */
    fun showPace(ctx: Context): Boolean = p(ctx).getBoolean("show_pace", true)
    fun setShowPace(ctx: Context, on: Boolean) =
        p(ctx).edit().putBoolean("show_pace", on).apply()

    // --- notifications -----------------------------------------------------

    fun notifyEnabled(ctx: Context): Boolean = p(ctx).getBoolean("notify", false)
    fun setNotifyEnabled(ctx: Context, on: Boolean) = p(ctx).edit().putBoolean("notify", on).apply()

    /** Percent at which a window is worth interrupting the user for. */
    fun notifyThreshold(ctx: Context): Int = p(ctx).getInt("notify_at", 90).coerceIn(50, 100)
    fun setNotifyThreshold(ctx: Context, v: Int) =
        p(ctx).edit().putInt("notify_at", v.coerceIn(50, 100)).apply()

    /** Windows already announced, keyed to their reset time so each period fires once. */
    fun firedAlerts(ctx: Context): Set<String> =
        // Copied for the same reason as hiddenWindows: getStringSet returns the live
        // prefs instance, and the absent-key default is an immutable EmptySet.
        HashSet(p(ctx).getStringSet("notify_fired", emptySet()) ?: emptySet())

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
