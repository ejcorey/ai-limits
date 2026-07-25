package dev.yuhee.ailimits

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * Warns when a usage window crosses the threshold the user picked.
 *
 * Each window is announced at most once per reset period: the de-dup key folds in
 * `resetsAt`, so when the window rolls over the key changes and the next crossing
 * is allowed to fire again. Without that, a window sitting at 95% would notify on
 * every single refresh.
 */
object Notifier {

    private const val CHANNEL = "limits"
    private const val GROUP = "dev.yuhee.ailimits.LIMITS"

    fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (mgr.getNotificationChannel(CHANNEL) != null) return
        mgr.createNotificationChannel(
            NotificationChannel(CHANNEL, "Usage limits", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Fires when a Claude or Codex window passes your threshold"
            }
        )
    }

    fun canPost(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Called after every successful fetch. Safe to call when disabled or unpermitted.
     *
     * Synchronized because a manual refresh and the periodic worker can reach this at
     * once: it is a read-modify-write of the fired-alert set, and a lost update either
     * re-announces a window or silences the next real crossing.
     */
    @Synchronized
    fun check(ctx: Context, snap: Snapshot) {
        if (!Settings.notifyEnabled(ctx) || !canPost(ctx)) return
        val threshold = Settings.notifyThreshold(ctx)
        ensureChannel(ctx)

        val already = Settings.firedAlerts(ctx)
        val live = mutableSetOf<String>()
        val fresh = mutableListOf<Triple<String, Win, Int>>()
        val reporting = mutableSetOf<String>()

        // A window is "the same window" when its reset lands near the one we recorded.
        // Exact-instant matching was fragile: Codex derives its reset from the clock, so
        // the value drifts a second per poll and any bucketing boundary it crossed made
        // the key look new — which pruned the old one and re-announced a window that had
        // already been announced. Real rollovers move the reset by hours, far past this.
        fun sameWindow(stored: String, name: String, w: Win): Boolean {
            val cut = stored.lastIndexOf('|')
            if (cut <= 0) return false
            if (stored.substring(0, cut) != "$name|${w.label}") return false
            val storedReset = stored.substring(cut + 1).toLongOrNull() ?: return false
            // Tolerance is a fraction of the window, not a flat 30 minutes: a flat value
            // was wider than the short windows Codex can report, so three consecutive
            // genuinely-new 10-minute periods all looked like the same one and went
            // unannounced. Falls back to 30 minutes when the span is unknown.
            val span = w.lengthMs
            val tolerance = if (span != null && span > 0L) {
                (span / 4).coerceIn(60_000L, 30 * 60_000L)
            } else {
                30 * 60_000L
            }
            return kotlin.math.abs(storedReset - w.resetsAt) <= tolerance
        }

        fun scan(name: String, state: ProviderState) {
            if (!state.configured || state.windows.isEmpty()) return
            // Never interrupt someone over numbers that are no longer current. A failed
            // refresh keeps the last good windows, and alerting off those could announce
            // a threshold from hours ago — or one the window has since reset past.
            // A clock moved backwards leaves a negative age, which would sail past a
            // "> threshold" test and let stale data alert — the opposite of the intent.
            val age = System.currentTimeMillis() - state.fetchedAt
            if (state.fetchedAt <= 0 || age < 0L || age > 60 * 60_000L) return
            reporting += name
            state.windows.forEach { w ->
                // With no reset instant there is nothing to distinguish one period from
                // the next, so the key carries a coarse clock bucket and expires with it
                // — otherwise one alert per install was all the user would ever get.
                val keyed = if (w.resetsAt > 0) w else
                    w.copy(resetsAt = -(System.currentTimeMillis() / (6 * 3600_000L)))
                val announced = already.filter { sameWindow(it, name, keyed) }
                // Keep the key we already stored, so its reset value does not creep with
                // the clock and eventually drift out of tolerance.
                live += announced.ifEmpty { listOf("$name|${keyed.label}|${keyed.resetsAt}") }
                if (w.pct >= threshold && announced.isEmpty()) {
                    fresh += Triple(name, keyed, w.pct)
                }
            }
        }
        scan("Claude", snap.claude)
        scan("Codex", snap.codex)
        scan("Gemini", snap.gemini)

        // Drop keys for windows that have since reset, so the set cannot grow forever —
        // but only for a provider that actually reported this round. Pruning on a failed
        // fetch (which reports no windows) would forget what has been announced and
        // re-alert the moment it recovers.
        // Keys survive while their window is still being reported. A provider that is
        // signed out or silent keeps its keys rather than having them forgotten and
        // re-announced on recovery; a provider that IS reporting drops the keys whose
        // windows have genuinely rolled over.
        val configuredNames = buildSet {
            if (snap.claude.configured) add("Claude")
            if (snap.codex.configured) add("Codex")
            if (snap.gemini.configured) add("Gemini")
        }
        val kept = already.filterTo(mutableSetOf()) { key ->
            val owner = key.substringBefore('|')
            // Signing out of a provider clears its keys for good.
            if (owner !in configuredNames) return@filterTo false
            key in live || owner !in reporting
        }

        fresh.forEach { (name, w, pct) ->
            // The bucketed key is negative for a reset-less window; the notification
            // itself must still show the real (absent) reset, so it is restored here.
            post(ctx, name, if (w.resetsAt < 0) w.copy(resetsAt = 0) else w, pct)
            kept += "$name|${w.label}|${w.resetsAt}"
        }
        Settings.setFiredAlerts(ctx, kept)
    }

    private fun post(ctx: Context, provider: String, w: Win, pct: Int) {
        val title = "$provider ${WidgetRenderer.windowName(w.label)} at $pct%"
        val body = if (w.resetsAt > 0) "Resets in ${WidgetRenderer.left(w.resetsAt)}" else "Limit nearly spent"
        val open = PendingIntent.getActivity(
            ctx, 100, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle(title)
            .setContentText(body)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setGroup(GROUP)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        runCatching {
            NotificationManagerCompat.from(ctx).notify("$provider-${w.label}".hashCode(), n)
        }
    }
}
