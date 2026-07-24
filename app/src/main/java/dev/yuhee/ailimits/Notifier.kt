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

    /** Called after every successful fetch. Safe to call when disabled or unpermitted. */
    fun check(ctx: Context, snap: Snapshot) {
        if (!Settings.notifyEnabled(ctx) || !canPost(ctx)) return
        val threshold = Settings.notifyThreshold(ctx)
        ensureChannel(ctx)

        val already = Settings.firedAlerts(ctx)
        val live = mutableSetOf<String>()
        val fresh = mutableListOf<Triple<String, Win, Int>>()
        val reporting = mutableSetOf<String>()

        fun scan(name: String, state: ProviderState) {
            if (!state.configured || state.windows.isEmpty()) return
            reporting += name
            state.windows.forEach { w ->
                val key = "$name|${w.label}|${w.resetsAt}"
                live += key
                if (w.pct >= threshold && key !in already) {
                    fresh += Triple(name, w, w.pct)
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
        val kept = already.filterTo(mutableSetOf()) { key ->
            key in live || key.substringBefore('|') !in reporting
        }

        fresh.forEach { (name, w, pct) ->
            post(ctx, name, w, pct)
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
