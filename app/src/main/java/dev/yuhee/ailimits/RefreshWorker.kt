package dev.yuhee.ailimits

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class RefreshWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
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

        fun refreshNow(ctx: Context) {
            val req = OneTimeWorkRequestBuilder<RefreshWorker>()
                .setConstraints(NET)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(ctx)
                .enqueueUniqueWork("ailimits-now", ExistingWorkPolicy.REPLACE, req)
        }
    }
}
