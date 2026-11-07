package com.namankadam.kaggleruns

import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.ZoneOffset

@Serializable
enum class RunStatus(val label: String, val color: Int, val active: Boolean) {
    QUEUED("Queued", 0xFFF5B935.toInt(), true),
    RUNNING("Running", 0xFF20BEFF.toInt(), true),
    CANCELLING("Cancelling", 0xFF9AA0A6.toInt(), true),
    COMPLETE("Complete", 0xFF3DDC84.toInt(), false),
    ERROR("Error", 0xFFFF5A5F.toInt(), false),
    CANCELLED("Cancelled", 0xFF9AA0A6.toInt(), false),
    UNKNOWN("Unknown", 0xFF9AA0A6.toInt(), false);

    companion object {
        /** Maps the Kaggle API's status string (e.g. "running", "cancelAcknowledged"). */
        fun fromApi(raw: String): RunStatus = when (raw.lowercase().substringAfterLast('.')) {
            "queued" -> QUEUED
            "running" -> RUNNING
            "cancelrequested" -> CANCELLING
            "complete" -> COMPLETE
            "error" -> ERROR
            "cancelacknowledged" -> CANCELLED
            else -> UNKNOWN
        }
    }
}

/** One notebook's latest run. [startMillis] is 0 when Kaggle reports no run time. */
@Serializable
data class Run(
    val ref: String,
    val title: String,
    val status: RunStatus,
    val startMillis: Long,
    val failure: String = "",
    /** How long a finished run took, read from its log; null while running or when unknown. */
    val durationSec: Long? = null,
)

@Serializable
enum class SubmissionStatus {
    PENDING, COMPLETE, ERROR, UNKNOWN;

    companion object {
        fun fromApi(raw: String): SubmissionStatus = when (raw.lowercase().substringAfterLast('.')) {
            "pending" -> PENDING
            "complete" -> COMPLETE
            "error" -> ERROR
            else -> UNKNOWN
        }
    }
}

@Serializable
data class Submission(
    val id: Long,
    val dateMillis: Long,
    val description: String = "",
    val status: SubmissionStatus,
    val publicScore: String = "",
    val privateScore: String = "",
    val error: String = "",
    /** "user/slug" of the notebook it was submitted from; null for file uploads. */
    val notebookRef: String? = null,
) {
    val scoreText: String
        get() = when (status) {
            SubmissionStatus.COMPLETE -> publicScore.ifBlank { "—" }
            SubmissionStatus.PENDING -> "Scoring…"
            SubmissionStatus.ERROR -> "Failed"
            SubmissionStatus.UNKNOWN -> "—"
        }
}

@Serializable
data class Competition(
    val slug: String,
    val title: String,
    val deadlineMillis: Long,
    val teamCount: Int,
    /** Public leaderboard rank; null until the first scored submission. */
    val userRank: Int?,
    val maxDaily: Int,
    /** Inferred from the leaderboard order, so "best" means max or min as the metric needs. */
    val higherIsBetter: Boolean,
    val topScore: String?,
    val submissions: List<Submission>,
) {
    val url: String get() = "https://www.kaggle.com/competitions/$slug"

    val bestSubmission: Submission?
        get() {
            val scored = submissions.filter { it.status == SubmissionStatus.COMPLETE && it.publicScore.toDoubleOrNull() != null }
            return if (higherIsBetter) scored.maxByOrNull { it.publicScore.toDouble() }
            else scored.minByOrNull { it.publicScore.toDouble() }
        }

    val latest: Submission? get() = submissions.maxByOrNull { it.dateMillis }

    /** Kaggle's daily submission limit resets at 00:00 UTC. */
    fun leftToday(now: Long = System.currentTimeMillis()): Int? {
        if (maxDaily <= 0) return null
        val dayStart = Instant.ofEpochMilli(now).atZone(ZoneOffset.UTC).toLocalDate()
            .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        return (maxDaily - submissions.count { it.dateMillis >= dayStart }).coerceAtLeast(0)
    }

    /** Decimal places Kaggle uses for this metric, so chart labels match the leaderboard. */
    val scoreDecimals: Int
        get() = (topScore ?: bestSubmission?.publicScore)?.substringAfter('.', "")?.length?.coerceIn(0, 6) ?: 3
}

@Serializable
data class Snapshot(
    val runs: List<Run> = emptyList(),
    val competitions: List<Competition> = emptyList(),
    val fetchedAt: Long = 0L,
    val error: String? = null,
) {
    fun latestSubmissionFor(notebookRef: String): Submission? =
        competitions.asSequence().flatMap { it.submissions }
            .filter { it.notebookRef == notebookRef }
            .maxByOrNull { it.dateMillis }

    fun notebookTitle(ref: String): String = runs.firstOrNull { it.ref == ref }?.title ?: ref.substringAfter('/')
}

/** One recorded reading of a competition, kept locally because Kaggle only reports the current rank. */
@Serializable
data class HistoryPoint(val t: Long, val rank: Int?, val best: Double?, val teams: Int)
