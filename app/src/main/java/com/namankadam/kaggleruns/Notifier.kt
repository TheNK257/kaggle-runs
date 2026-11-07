package com.namankadam.kaggleruns

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/** Posts a notification when a run finishes or a submission gets scored, by diffing two snapshots. */
object Notifier {
    private const val CHANNEL_RUNS = "runs"
    private const val CHANNEL_SUBMISSIONS = "submissions"

    fun canPost(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun notifyChanges(context: Context, previous: Snapshot?, next: Snapshot) {
        // Nothing to compare against on the first sync: don't announce old results.
        if (previous == null || previous.fetchedAt == 0L || !canPost(context)) return
        ensureChannels(context)
        if (Prefs.notifyRuns(context)) runChanges(context, previous, next)
        if (Prefs.notifySubmissions(context)) submissionChanges(context, previous, next)
    }

    private fun runChanges(context: Context, previous: Snapshot, next: Snapshot) {
        val before = previous.runs.associateBy { it.ref }
        for (run in next.runs) {
            if (run.status.active || run.status == RunStatus.UNKNOWN) continue
            val old = before[run.ref]
            val justFinished = when {
                old == null -> run.startMillis > previous.fetchedAt
                old.startMillis != run.startMillis -> run.startMillis > old.startMillis // started and finished between checks
                else -> old.status.active
            }
            if (!justFinished) continue
            val took = run.durationSec?.let { "Took ${formatDuration(it)}" }
            val (title, text) = when (run.status) {
                RunStatus.COMPLETE -> "✅ ${run.title} finished" to (took ?: "Run complete")
                RunStatus.ERROR -> "❌ ${run.title} failed" to (run.failure.ifBlank { null } ?: took ?: "The run stopped with an error")
                else -> "⏹ ${run.title} was cancelled" to (took ?: "Run cancelled")
            }
            val open = Intent(context, OpenNotebookActivity::class.java)
                .setData(Uri.parse("https://www.kaggle.com/code/${run.ref}"))
            post(context, CHANNEL_RUNS, "run:${run.ref}:${run.startMillis}".hashCode(), title, text, open)
        }
    }

    private fun submissionChanges(context: Context, previous: Snapshot, next: Snapshot) {
        val beforeComps = previous.competitions.associateBy { it.slug }
        for (comp in next.competitions) {
            val oldComp = beforeComps[comp.slug] ?: continue // newly seen competition: no baseline yet
            val before = oldComp.submissions.associateBy { it.id }
            for (sub in comp.submissions) {
                if (sub.status == SubmissionStatus.PENDING || sub.status == SubmissionStatus.UNKNOWN) continue
                val old = before[sub.id]
                val justScored = if (old == null) sub.dateMillis > previous.fetchedAt - 3_600_000L
                else old.status == SubmissionStatus.PENDING
                if (!justScored) continue
                val name = sub.notebookRef?.let { next.notebookTitle(it) } ?: "Your submission"
                val (title, text) = if (sub.status == SubmissionStatus.ERROR) {
                    "⚠️ $name submission failed" to sub.error.ifBlank { comp.title }
                } else {
                    val rank = comp.userRank?.let { now ->
                        val was = oldComp.userRank
                        if (was != null && was != now) "rank #${formatCount(was)} → #${formatCount(now)}" else "rank #${formatCount(now)}"
                    }
                    "📤 $name scored ${sub.publicScore}" to listOfNotNull(comp.title, rank).joinToString(" · ")
                }
                val open = Intent(context, MainActivity::class.java)
                    .putExtra(MainActivity.EXTRA_TAB, MainActivity.TAB_COMPETITIONS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                post(context, CHANNEL_SUBMISSIONS, "sub:${sub.id}".hashCode(), title, text, open)
            }
        }
    }

    @SuppressLint("MissingPermission") // checked in canPost()
    private fun post(context: Context, channel: String, id: Int, title: String, text: String, open: Intent) {
        val tap = PendingIntent.getActivity(context, id, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(0xFF20BEFF.toInt())
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (e: SecurityException) {
            // permission revoked between the check and now
        }
    }

    private fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_RUNS, "Notebook runs", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "When a notebook run finishes, fails or is cancelled" },
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_SUBMISSIONS, "Submissions", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "When a competition submission is scored" },
        )
    }
}
