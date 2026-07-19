package dev.yuhee.ailimits

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent

class UsageWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        WidgetRenderer.updateAll(context)
        RefreshWorker.schedulePeriodic(context)
        RefreshWorker.refreshNow(context)
    }

    override fun onEnabled(context: Context) {
        RefreshWorker.schedulePeriodic(context)
        RefreshWorker.refreshNow(context)
    }

    override fun onDisabled(context: Context) {
        RefreshWorker.cancelPeriodic(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_MANUAL_REFRESH) {
            WidgetRenderer.updateAll(context, refreshing = true)
            RefreshWorker.refreshNow(context)
        }
    }

    companion object {
        const val ACTION_MANUAL_REFRESH = "dev.yuhee.ailimits.ACTION_MANUAL_REFRESH"
    }
}
