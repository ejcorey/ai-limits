package dev.yuhee.ailimits

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class RefreshWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            // Here as well as in the app, because a user who updates and never opens the
            // app would otherwise keep a live Google refresh token on disk forever.
            Prefs.purgeRemovedGemini(applicationContext)
            val snap = UsageRepo.fetchAll(applicationContext)
            Notifier.check(applicationContext, snap)
        } finally {
            WidgetRenderer.updateAll(applicationContext)
        }
        Result.success()
    }

    companion object {
        private val NET = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        fun schedulePeriodic(ctx: Context) {
            val mins = Prefs.refreshMinutes(ctx).coerceAtLeast(15).toLong()
            val req = PeriodicWorkRequestBuilder<RefreshWorker>(mins, TimeUnit.MINUTES)
                .setConstraints(NET)
                .build()
            WorkManager.getInstance(ctx)
                .enqueueUniquePeriodicWork("ailimits-periodic", ExistingPeriodicWorkPolicy.UPDATE, req)
        }

        fun cancelPeriodic(ctx: Context) {
            WorkManager.getInstance(ctx).cancelUniqueWork("ailimits-periodic")
        }

        /**
         * Whether the stored data is older than the interval the user chose.
         *
         * The AppWidget framework calls onUpdate on its own schedule (30 minutes, its
         * floor) and that used to fetch unconditionally — so picking "every 2 hours"
         * still hit every provider every 30 minutes, and doubled up with the periodic
         * worker. The manual tap path deliberately does not consult this.
         */
        fun isDue(ctx: Context): Boolean {
            val fetchedAt = UsageRepo.load(ctx).fetchedAt
            if (fetchedAt <= 0L) return true
            val interval = Prefs.refreshMinutes(ctx).coerceAtLeast(1) * 60_000L
            val age = System.currentTimeMillis() - fetchedAt
            // A clock moved backwards leaves a negative age; treat that as due rather
            // than as "fetched in the future" and never refreshing again.
            if (age < 0L) return true
            // A minute of slack, so a tick landing just short does not skip a whole cycle.
            return age >= interval - 60_000L
        }

        fun refreshNow(ctx: Context) {
            // Deliberately not expedited: below API 31 WorkManager runs expedited work as
            // a foreground service and requires getForegroundInfo(), which CoroutineWorker
            // does not implement — every tap-to-refresh would fail on API 26-30. A usage
            // readout is not worth a foreground-service notification.
            val req = OneTimeWorkRequestBuilder<RefreshWorker>()
                .setConstraints(NET)
                .build()
            WorkManager.getInstance(ctx)
                .enqueueUniqueWork("ailimits-now", ExistingWorkPolicy.REPLACE, req)
        }
    }
}
