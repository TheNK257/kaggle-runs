package com.namankadam.kaggleruns

import android.app.PendingIntent
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews

const val ACTION_REFRESH = "com.namankadam.kaggleruns.REFRESH"

const val WIDGET_TEXT_SECONDARY = 0xFFB0B3B8.toInt()
const val WIDGET_TEXT_ERROR = 0xFFFF8A80.toInt()
const val WIDGET_GOOD = 0xFF0CA30C.toInt()
const val WIDGET_BAD = 0xFFD03B3B.toInt()

object WidgetUpdater {
    fun updateAll(context: Context, refreshing: Boolean = false) {
        RunsWidget.updateAll(context, refreshing)
        CompetitionWidget.updateAll(context, refreshing)
    }

    /** Shared by both providers' ⟳ buttons and app-update handling. */
    fun handle(context: Context, intent: Intent): Boolean = when (intent.action) {
        ACTION_REFRESH -> {
            updateAll(context, refreshing = true)
            RefreshWorker.refreshNow(context)
            true
        }
        // An app update wipes widget views and some launchers don't send APPWIDGET_UPDATE.
        Intent.ACTION_MY_PACKAGE_REPLACED -> {
            updateAll(context)
            RefreshWorker.schedulePeriodic(context)
            RefreshWorker.refreshNow(context)
            true
        }
        else -> false
    }

    fun openApp(context: Context, tab: String): PendingIntent = PendingIntent.getActivity(
        context, tab.hashCode(),
        Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_TAB, tab)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun refresh(context: Context, provider: Class<out AppWidgetProvider>): PendingIntent = PendingIntent.getBroadcast(
        context, provider.name.hashCode(),
        Intent(context, provider).setAction(ACTION_REFRESH),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun updatedLabel(context: Context, snapshot: Snapshot?, refreshing: Boolean): String = when {
        refreshing -> "Refreshing…"
        snapshot == null || snapshot.fetchedAt == 0L -> ""
        else -> "Updated " + formatClock(context, snapshot.fetchedAt)
    }

    fun showMessage(views: RemoteViews, text: String, color: Int) {
        views.setTextViewText(R.id.message, text)
        views.setTextColor(R.id.message, color)
        views.setViewVisibility(R.id.message, View.VISIBLE)
    }
}
