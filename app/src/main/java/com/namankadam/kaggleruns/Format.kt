package com.namankadam.kaggleruns

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.format.DateUtils
import java.text.NumberFormat
import kotlin.math.ceil

/** Absolute times never go stale between refreshes, unlike "5 min ago". */
fun formatClock(context: Context, millis: Long): String {
    val flags = if (DateUtils.isToday(millis)) DateUtils.FORMAT_SHOW_TIME
    else DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_SHOW_TIME
    return DateUtils.formatDateTime(context, millis, flags)
}

fun formatDay(context: Context, millis: Long): String = DateUtils.formatDateTime(
    context, millis, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_NO_YEAR,
)

/** 45s · 55m 49s · 2h 03m */
fun formatDuration(seconds: Long): String = when {
    seconds < 60 -> "${seconds}s"
    seconds < 3600 -> "%dm %02ds".format(seconds / 60, seconds % 60)
    else -> "%dh %02dm".format(seconds / 3600, seconds % 3600 / 60)
}

/** Stopwatch style, matching the widget's Chronometer: 4:05 or 2:46:28. */
fun formatElapsed(millis: Long): String {
    val s = (millis / 1000).coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60)
    else "%d:%02d".format(s / 60, s % 60)
}

fun formatCount(n: Int): String = NumberFormat.getIntegerInstance().format(n)

fun timeLeft(deadlineMillis: Long, now: Long = System.currentTimeMillis()): String? {
    if (deadlineMillis <= 0) return null
    val left = deadlineMillis - now
    if (left <= 0) return "Ended"
    val hours = left / 3_600_000
    if (hours < 48) return "${hours.coerceAtLeast(1)}h left"
    return "${ceil(left / 86_400_000.0).toInt()} days left"
}

/** "▲ 31" / "▼ 12"; the arrow keeps direction readable without colour. */
fun formatRankDelta(delta: Int): String = if (delta > 0) "▲ ${formatCount(delta)}" else "▼ ${formatCount(-delta)}"

fun Context.openUrl(url: String) {
    try {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: ActivityNotFoundException) {
        // no browser installed
    }
}
