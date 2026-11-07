package com.namankadam.kaggleruns

import android.content.Context
import kotlinx.serialization.json.Json
import java.io.File

val AppJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    explicitNulls = false
}

private fun Context.prefs(name: String) = getSharedPreferences(name, Context.MODE_PRIVATE)

/** Last fetched snapshot, persisted so widgets and the app can draw without the network. */
object SnapshotCache {
    private const val PREFS = "snapshot"
    private const val KEY = "v2" // v1 used a hand-rolled format under "json"

    fun save(context: Context, snapshot: Snapshot) {
        context.prefs(PREFS).edit().putString(KEY, AppJson.encodeToString(snapshot)).apply()
    }

    fun load(context: Context): Snapshot? {
        val raw = context.prefs(PREFS).getString(KEY, null) ?: return null
        return runCatching { AppJson.decodeFromString<Snapshot>(raw) }.getOrNull()
    }

    fun clear(context: Context) = context.prefs(PREFS).edit().clear().apply()
}

/** Run durations by run (ref + start time). A finished run's length never changes, so fetch its log once. */
object DurationCache {
    private const val PREFS = "durations"
    private const val KEY = "map"

    fun key(run: Run) = "${run.ref}|${run.startMillis}"

    fun load(context: Context): Map<String, Long> {
        val raw = context.prefs(PREFS).getString(KEY, null) ?: return emptyMap()
        return runCatching { AppJson.decodeFromString<Map<String, Long>>(raw) }.getOrDefault(emptyMap())
    }

    fun save(context: Context, durations: Map<String, Long>) {
        context.prefs(PREFS).edit().putString(KEY, AppJson.encodeToString(durations)).apply()
    }

    fun clear(context: Context) = context.prefs(PREFS).edit().clear().apply()
}

/** Rank/score history per competition, one small JSON file each. */
object HistoryStore {
    private const val MAX_POINTS = 2000
    private const val SAME_VALUE_GAP_MS = 6 * 3_600_000L
    private const val DAY_MS = 24 * 3_600_000L

    data class RankChange(val delta: Int, val sinceMillis: Long)

    private fun dir(context: Context) = File(context.filesDir, "history")
    private fun file(context: Context, slug: String) = File(dir(context), "${slug.replace(Regex("[^A-Za-z0-9_-]"), "_")}.json")

    fun load(context: Context, slug: String): List<HistoryPoint> {
        val f = file(context, slug)
        if (!f.exists()) return emptyList()
        return runCatching { AppJson.decodeFromString<List<HistoryPoint>>(f.readText()) }.getOrDefault(emptyList())
    }

    /** Appends a point when something changed, or every few hours so flat stretches still plot. */
    fun record(context: Context, competition: Competition, now: Long) {
        val point = HistoryPoint(now, competition.userRank, competition.bestSubmission?.publicScore?.toDoubleOrNull(), competition.teamCount)
        val points = load(context, competition.slug).toMutableList()
        val last = points.lastOrNull()
        if (last != null && last.rank == point.rank && last.best == point.best && now - last.t < SAME_VALUE_GAP_MS) return
        points += point
        while (points.size > MAX_POINTS) points.removeAt(0)
        val f = file(context, competition.slug)
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(AppJson.encodeToString(points))
        tmp.renameTo(f)
    }

    /** Places gained (positive) over the last 24 h, or since the oldest reading if history is younger. */
    fun rankChange(context: Context, competition: Competition, now: Long = System.currentTimeMillis()): RankChange? {
        val current = competition.userRank ?: return null
        val ranked = load(context, competition.slug).filter { it.rank != null }
        val base = ranked.lastOrNull { it.t <= now - DAY_MS } ?: ranked.firstOrNull() ?: return null
        if (now - base.t < 3_600_000L) return null
        return RankChange(base.rank!! - current, base.t)
    }

    fun clear(context: Context) {
        dir(context).deleteRecursively()
    }
}

/** User settings. */
object Prefs {
    private const val PREFS = "settings"
    const val MANUAL = 0
    val REFRESH_CHOICES = listOf(15, 30, 60, 180, MANUAL)

    fun refreshMinutes(context: Context): Int = context.prefs(PREFS).getInt("refresh_minutes", 15)
    fun setRefreshMinutes(context: Context, minutes: Int) =
        context.prefs(PREFS).edit().putInt("refresh_minutes", minutes).apply()

    fun notifyRuns(context: Context) = context.prefs(PREFS).getBoolean("notify_runs", true)
    fun setNotifyRuns(context: Context, on: Boolean) = context.prefs(PREFS).edit().putBoolean("notify_runs", on).apply()

    fun notifySubmissions(context: Context) = context.prefs(PREFS).getBoolean("notify_submissions", true)
    fun setNotifySubmissions(context: Context, on: Boolean) =
        context.prefs(PREFS).edit().putBoolean("notify_submissions", on).apply()

    fun askedForNotifications(context: Context) = context.prefs(PREFS).getBoolean("asked_notifications", false)
    fun setAskedForNotifications(context: Context) =
        context.prefs(PREFS).edit().putBoolean("asked_notifications", true).apply()

    /** Competition a competition widget follows; null means "automatic". */
    fun widgetCompetition(context: Context, widgetId: Int): String? =
        context.prefs(PREFS).getString("widget_comp_$widgetId", null)

    fun setWidgetCompetition(context: Context, widgetId: Int, slug: String?) {
        context.prefs(PREFS).edit().apply {
            if (slug == null) remove("widget_comp_$widgetId") else putString("widget_comp_$widgetId", slug)
        }.apply()
    }
}
