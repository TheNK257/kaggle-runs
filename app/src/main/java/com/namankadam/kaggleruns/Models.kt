package com.namankadam.kaggleruns

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

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
data class Run(
    val ref: String,
    val title: String,
    val status: RunStatus,
    val startMillis: Long,
    val failure: String,
)

data class Snapshot(val runs: List<Run>, val fetchedAt: Long, val error: String?)

/** Last fetched snapshot, persisted so the widget can redraw without the network. */
object SnapshotCache {
    private const val PREFS = "snapshot"
    private const val KEY = "json"

    fun save(context: Context, snapshot: Snapshot) {
        val runs = JSONArray()
        snapshot.runs.forEach {
            runs.put(
                JSONObject()
                    .put("ref", it.ref)
                    .put("title", it.title)
                    .put("status", it.status.name)
                    .put("start", it.startMillis)
                    .put("failure", it.failure)
            )
        }
        val json = JSONObject()
            .put("runs", runs)
            .put("fetchedAt", snapshot.fetchedAt)
            .put("error", snapshot.error ?: JSONObject.NULL)
        prefs(context).edit().putString(KEY, json.toString()).apply()
    }

    fun load(context: Context): Snapshot? {
        val raw = prefs(context).getString(KEY, null) ?: return null
        return try {
            val json = JSONObject(raw)
            val arr = json.getJSONArray("runs")
            val runs = (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Run(
                    ref = o.getString("ref"),
                    title = o.getString("title"),
                    status = runCatching { RunStatus.valueOf(o.getString("status")) }
                        .getOrDefault(RunStatus.UNKNOWN),
                    startMillis = o.getLong("start"),
                    failure = o.optString("failure"),
                )
            }
            Snapshot(runs, json.getLong("fetchedAt"), json.optString("error").takeIf { !json.isNull("error") })
        } catch (e: Exception) {
            null
        }
    }

    fun clear(context: Context) = prefs(context).edit().clear().apply()

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
