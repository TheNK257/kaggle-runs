package com.namankadam.kaggleruns

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.text.format.DateUtils
import android.view.View
import android.widget.RemoteViews
import androidx.core.widget.RemoteViewsCompat
import androidx.core.widget.RemoteViewsCompat.RemoteCollectionItems

class KaggleWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        // Called when the widget is added, after reboot and after app updates.
        WidgetRenderer.updateAll(context)
        RefreshWorker.schedulePeriodic(context)
        RefreshWorker.refreshNow(context)
    }

    override fun onDisabled(context: Context) {
        RefreshWorker.cancelAll(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_REFRESH -> {
                WidgetRenderer.updateAll(context, refreshing = true)
                RefreshWorker.refreshNow(context)
            }
            // An app update wipes the widget's views and some launchers don't send APPWIDGET_UPDATE.
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                WidgetRenderer.updateAll(context)
                RefreshWorker.schedulePeriodic(context)
                RefreshWorker.refreshNow(context)
            }
            else -> super.onReceive(context, intent)
        }
    }

    companion object {
        const val ACTION_REFRESH = "com.namankadam.kaggleruns.REFRESH"
    }
}

object WidgetRenderer {
    private const val MAX_ROWS = 12
    private const val TEXT_SECONDARY = 0xFFB0B3B8.toInt()
    private const val TEXT_ERROR = 0xFFFF8A80.toInt()

    fun updateAll(context: Context, refreshing: Boolean = false) {
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(ComponentName(context, KaggleWidgetProvider::class.java))
        for (id in ids) manager.updateAppWidget(id, build(context, id, refreshing))
    }

    private fun build(context: Context, widgetId: Int, refreshing: Boolean): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_kaggle)
        val settings = PendingIntent.getActivity(
            context, 0, Intent(context, SettingsActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val refresh = PendingIntent.getBroadcast(
            context, 1,
            Intent(context, KaggleWidgetProvider::class.java).setAction(KaggleWidgetProvider.ACTION_REFRESH),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        // List items can't carry their own PendingIntents; each row fills in its notebook URL.
        val openNotebook = PendingIntent.getActivity(
            context, 2, Intent(context, OpenNotebookActivity::class.java),
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        views.setOnClickPendingIntent(R.id.title, settings)
        views.setOnClickPendingIntent(R.id.refresh, refresh)
        views.setPendingIntentTemplate(R.id.rows, openNotebook)

        val snapshot = SnapshotCache.load(context)
        val runs = if (TokenStore.load(context) == null) emptyList() else snapshot?.runs.orEmpty().take(MAX_ROWS)
        val items = RemoteCollectionItems.Builder().setHasStableIds(true).setViewTypeCount(1)
        runs.forEach { items.addItem(it.ref.hashCode().toLong(), row(context, it)) }
        RemoteViewsCompat.setRemoteAdapter(context, views, widgetId, R.id.rows, items.build())

        if (TokenStore.load(context) == null) {
            views.setTextViewText(R.id.updated, "")
            showMessage(views, "Tap to add your Kaggle API token", TEXT_SECONDARY)
            views.setOnClickPendingIntent(R.id.message, settings)
            return views
        }

        views.setTextViewText(
            R.id.updated,
            when {
                refreshing -> "Refreshing…"
                snapshot == null || snapshot.fetchedAt == 0L -> ""
                else -> "Updated " + clock(context, snapshot.fetchedAt)
            },
        )

        when {
            snapshot?.error != null -> showMessage(views, snapshot.error, TEXT_ERROR)
            snapshot == null -> showMessage(views, "Loading…", TEXT_SECONDARY)
            snapshot.runs.isEmpty() -> showMessage(views, "No notebook runs yet", TEXT_SECONDARY)
            else -> views.setViewVisibility(R.id.message, View.GONE)
        }
        if (snapshot?.error != null) views.setOnClickPendingIntent(R.id.message, settings)
        return views
    }

    private fun row(context: Context, run: Run): RemoteViews {
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
            row.setViewVisibility(R.id.started, View.GONE)
        } else {
            row.setTextViewText(R.id.started, if (run.startMillis > 0) clock(context, run.startMillis) else "")
            row.setViewVisibility(R.id.timer, View.GONE)
            row.setViewVisibility(R.id.started, View.VISIBLE)
        }

        row.setOnClickFillInIntent(
            R.id.row,
            Intent().setData(Uri.parse("https://www.kaggle.com/code/${run.ref}")),
        )
        return row
    }

    private fun showMessage(views: RemoteViews, text: String, color: Int) {
        views.setTextViewText(R.id.message, text)
        views.setTextColor(R.id.message, color)
        views.setViewVisibility(R.id.message, View.VISIBLE)
    }

    /** Absolute times never go stale between refreshes, unlike "5 min ago". */
    private fun clock(context: Context, millis: Long): String {
        val flags = if (DateUtils.isToday(millis)) DateUtils.FORMAT_SHOW_TIME
        else DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_SHOW_TIME
        return DateUtils.formatDateTime(context, millis, flags)
    }
}
