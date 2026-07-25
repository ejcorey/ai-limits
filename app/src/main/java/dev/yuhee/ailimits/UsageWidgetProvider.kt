package dev.yuhee.ailimits

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Bundle

abstract class BaseWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        WidgetRenderer.updateAll(context)
        RefreshWorker.schedulePeriodic(context)
        // Redrawing is free; fetching is not. The framework ticks this every 30 minutes
        // whatever interval the user picked, so it only reaches for the network when the
        // data it has is actually older than that interval. A freshly added widget has
        // no data and is therefore always due.
        if (RefreshWorker.isDue(context)) RefreshWorker.refreshNow(context)
    }

    /**
     * Each widget lays itself out against its own size, so a resize must redraw —
     * but only the one being dragged. This fires repeatedly during a drag, and
     * re-rendering every widget on the home screen each step made it stutter.
     */
    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle,
    ) {
        WidgetRenderer.updateOne(context, appWidgetId)
    }

    override fun onEnabled(context: Context) {
        // First widget of this style placed — always worth a fetch.
        RefreshWorker.schedulePeriodic(context)
        RefreshWorker.refreshNow(context)
    }

    override fun onDisabled(context: Context) {
        if (!WidgetRenderer.anyWidgets(context)) RefreshWorker.cancelPeriodic(context)
    }

    /**
     * Drop a removed widget's own configuration. [WidgetConfigStore.reap] would catch this
     * eventually, but only on the next full redraw — and this callback is the only signal
     * that arrives immediately.
     */
    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        appWidgetIds.forEach { WidgetConfigStore.delete(context, it) }
    }
}

class UsageWidgetProvider : BaseWidgetProvider() {

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

// These class names are a permanent contract: a placed widget is bound to its receiver,
// so renaming or removing one deletes every instance of it from every home screen, with
// no way back. Per-instance styles do not change that — do not consolidate them.
class BarsWidgetProvider : BaseWidgetProvider()
class PercentWidgetProvider : BaseWidgetProvider()
class GraphWidgetProvider : BaseWidgetProvider()
class BatteryWidgetProvider : BaseWidgetProvider()
class CountdownWidgetProvider : BaseWidgetProvider()
class TickerWidgetProvider : BaseWidgetProvider()
class PickWidgetProvider : BaseWidgetProvider()
class HorizonWidgetProvider : BaseWidgetProvider()
class RunwayWidgetProvider : BaseWidgetProvider()
