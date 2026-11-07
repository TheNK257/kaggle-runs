package com.namankadam.kaggleruns

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews

/** Rank, scores and submissions for one competition. */
class CompetitionWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        CompetitionWidget.updateAll(context)
        RefreshWorker.schedulePeriodic(context)
        RefreshWorker.refreshNow(context)
    }

    override fun onDeleted(context: Context, ids: IntArray) {
        ids.forEach { Prefs.setWidgetCompetition(context, it, null) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (!WidgetUpdater.handle(context, intent)) super.onReceive(context, intent)
    }
}

object CompetitionWidget {
    private const val DAY_MS = 24 * 3_600_000L

    fun updateAll(context: Context, refreshing: Boolean = false) {
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(ComponentName(context, CompetitionWidgetProvider::class.java))
        for (id in ids) manager.updateAppWidget(id, build(context, id, refreshing))
    }

    fun update(context: Context, widgetId: Int) {
        AppWidgetManager.getInstance(context).updateAppWidget(widgetId, build(context, widgetId, refreshing = false))
    }

    /** The widget's chosen competition, else the one with the most recent submission. */
    fun pick(snapshot: Snapshot?, chosen: String?): Competition? {
        val comps = snapshot?.competitions.orEmpty()
        return comps.firstOrNull { it.slug == chosen }
            ?: comps.maxByOrNull { it.latest?.dateMillis ?: 0L }
    }

    private fun build(context: Context, widgetId: Int, refreshing: Boolean): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_competition)
        val openApp = WidgetUpdater.openApp(context, MainActivity.TAB_COMPETITIONS)
        views.setOnClickPendingIntent(R.id.title, openApp)
        views.setOnClickPendingIntent(R.id.content, openApp)
        views.setOnClickPendingIntent(R.id.message, openApp)
        views.setOnClickPendingIntent(R.id.refresh, WidgetUpdater.refresh(context, CompetitionWidgetProvider::class.java))
        views.setTextViewText(R.id.title, context.getString(R.string.competition_widget_title))

        if (TokenStore.load(context) == null) {
            views.setTextViewText(R.id.updated, "")
            views.setViewVisibility(R.id.content, View.GONE)
            WidgetUpdater.showMessage(views, "Tap to set up Kaggle Runs", WIDGET_TEXT_SECONDARY)
            return views
        }

        val snapshot = SnapshotCache.load(context)
        val comp = pick(snapshot, Prefs.widgetCompetition(context, widgetId))
        views.setTextViewText(R.id.updated, WidgetUpdater.updatedLabel(context, snapshot, refreshing))

        when {
            snapshot?.error != null -> WidgetUpdater.showMessage(views, snapshot.error, WIDGET_TEXT_ERROR)
            snapshot == null -> WidgetUpdater.showMessage(views, "Loading…", WIDGET_TEXT_SECONDARY)
            comp == null -> WidgetUpdater.showMessage(views, "No active competitions entered", WIDGET_TEXT_SECONDARY)
            else -> views.setViewVisibility(R.id.message, View.GONE)
        }
        if (comp == null || snapshot == null) {
            views.setViewVisibility(R.id.content, View.GONE)
            return views
        }

        views.setViewVisibility(R.id.content, View.VISIBLE)
        views.setTextViewText(R.id.title, comp.title)
        views.setTextViewText(R.id.rank, comp.userRank?.let { "#" + formatCount(it) } ?: "Unranked")
        views.setTextViewText(R.id.teams, "of " + formatCount(comp.teamCount))

        val change = HistoryStore.rankChange(context, comp)
        if (change != null && change.delta != 0) {
            val window = if (System.currentTimeMillis() - change.sinceMillis >= DAY_MS) "24h"
            else "since " + formatClock(context, change.sinceMillis)
            views.setTextViewText(R.id.delta, formatRankDelta(change.delta) + " · " + window)
            views.setTextColor(R.id.delta, if (change.delta > 0) WIDGET_GOOD else WIDGET_BAD)
            views.setViewVisibility(R.id.delta, View.VISIBLE)
        } else {
            views.setViewVisibility(R.id.delta, View.GONE)
        }

        views.setTextViewText(
            R.id.scores,
            "Best ${comp.bestSubmission?.publicScore ?: "—"} · Top ${comp.topScore ?: "—"}",
        )
        views.setTextViewText(
            R.id.latest,
            comp.latest?.let { sub ->
                val name = sub.notebookRef?.let { snapshot.notebookTitle(it) } ?: "Upload"
                "Last: $name · ${sub.scoreText}"
            } ?: "No submissions yet",
        )
        views.setTextViewText(
            R.id.footer,
            listOfNotNull(
                comp.leftToday()?.let { "$it of ${comp.maxDaily} left today" },
                timeLeft(comp.deadlineMillis),
            ).joinToString(" · "),
        )
        return views
    }
}
