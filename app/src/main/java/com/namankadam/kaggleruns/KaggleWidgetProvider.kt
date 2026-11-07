package com.namankadam.kaggleruns

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews
import androidx.core.widget.RemoteViewsCompat
import androidx.core.widget.RemoteViewsCompat.RemoteCollectionItems

/** The notebook runs widget. (Class name kept from v1.0 so placed widgets survive the update.) */
class KaggleWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        // Called when the widget is added, after reboot and after app updates.
        RunsWidget.updateAll(context)
        RefreshWorker.schedulePeriodic(context)
        RefreshWorker.refreshNow(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (!WidgetUpdater.handle(context, intent)) super.onReceive(context, intent)
    }
}

object RunsWidget {
    private const val MAX_ROWS = 12

    fun updateAll(context: Context, refreshing: Boolean = false) {
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(ComponentName(context, KaggleWidgetProvider::class.java))
        for (id in ids) manager.updateAppWidget(id, build(context, id, refreshing))
    }

    private fun build(context: Context, widgetId: Int, refreshing: Boolean): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_kaggle)
        val openApp = WidgetUpdater.openApp(context, MainActivity.TAB_RUNS)
        // List items can't carry their own PendingIntents; each row fills in its notebook URL.
        val openNotebook = PendingIntent.getActivity(
            context, 2, Intent(context, OpenNotebookActivity::class.java),
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        views.setOnClickPendingIntent(R.id.title, openApp)
        views.setOnClickPendingIntent(R.id.refresh, WidgetUpdater.refresh(context, KaggleWidgetProvider::class.java))
        views.setPendingIntentTemplate(R.id.rows, openNotebook)

        val hasToken = TokenStore.load(context) != null
        val snapshot = SnapshotCache.load(context)
        val runs = if (hasToken) snapshot?.runs.orEmpty().take(MAX_ROWS) else emptyList()
        val items = RemoteCollectionItems.Builder().setHasStableIds(true).setViewTypeCount(1)
        runs.forEach { items.addItem(it.ref.hashCode().toLong(), row(context, it, snapshot!!)) }
        RemoteViewsCompat.setRemoteAdapter(context, views, widgetId, R.id.rows, items.build())

        if (!hasToken) {
            views.setTextViewText(R.id.updated, "")
            WidgetUpdater.showMessage(views, "Tap to set up Kaggle Runs", WIDGET_TEXT_SECONDARY)
            views.setOnClickPendingIntent(R.id.message, openApp)
            return views
        }

        views.setTextViewText(R.id.updated, WidgetUpdater.updatedLabel(context, snapshot, refreshing))
        when {
            snapshot?.error != null -> WidgetUpdater.showMessage(views, snapshot.error, WIDGET_TEXT_ERROR)
            snapshot == null -> WidgetUpdater.showMessage(views, "Loading…", WIDGET_TEXT_SECONDARY)
            snapshot.runs.isEmpty() -> WidgetUpdater.showMessage(views, "No notebook runs yet", WIDGET_TEXT_SECONDARY)
            else -> views.setViewVisibility(R.id.message, View.GONE)
        }
        views.setOnClickPendingIntent(R.id.message, openApp)
        return views
    }

    private fun row(context: Context, run: Run, snapshot: Snapshot): RemoteViews {
        val row = RemoteViews(context.packageName, R.layout.widget_row)
        row.setTextViewText(R.id.name, run.title)
        row.setTextColor(R.id.dot, run.status.color)
        row.setTextViewText(R.id.status, run.status.label)
        row.setTextColor(R.id.status, run.status.color)

        if (run.status.active && run.startMillis > 0) {
            // Chronometer ticks on its own, so the running time stays live between refreshes.
            val base = SystemClock.elapsedRealtime() - (System.currentTimeMillis() - run.startMillis)
            row.setChronometer(R.id.timer, base, null, true)
            row.setViewVisibility(R.id.timer, View.VISIBLE)
            row.setTextViewText(R.id.detail, "· since " + formatClock(context, run.startMillis))
        } else {
            row.setViewVisibility(R.id.timer, View.GONE)
            val parts = listOfNotNull(
                run.startMillis.takeIf { it > 0 }?.let { formatClock(context, it) },
                run.durationSec?.let { "took " + formatDuration(it) },
            )
            row.setTextViewText(R.id.detail, parts.joinToString(" · "))
        }

        val submission = snapshot.latestSubmissionFor(run.ref)
        if (submission != null) {
            row.setTextViewText(R.id.badge, "📤 " + submission.scoreText)
            row.setViewVisibility(R.id.badge, View.VISIBLE)
        } else {
            row.setViewVisibility(R.id.badge, View.GONE)
        }

        row.setOnClickFillInIntent(
            R.id.row,
            Intent().setData(Uri.parse("https://www.kaggle.com/code/${run.ref}")),
        )
        return row
    }
}
