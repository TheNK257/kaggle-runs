package com.namankadam.kaggleruns

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant

class AuthException : IOException("Kaggle rejected the token")

/** Minimal client for the two Kaggle endpoints the widget needs. */
class KaggleApi(private val token: String) {

    /** Status of the most recently run notebooks, active runs first. */
    suspend fun fetchRuns(checkLatest: Int = 8): List<Run> = coroutineScope {
        val kernels = listMine()
            .filter { it.third.isNotBlank() } // never-run notebooks have no status
            .take(checkLatest)
        kernels.map { (ref, title, lastRun) ->
            async(Dispatchers.IO) {
                val status = try {
                    status(ref)
                } catch (e: AuthException) {
                    throw e
                } catch (e: Exception) {
                    JSONObject() // one bad lookup shouldn't hide the other notebooks
                }
                Run(
                    ref = ref,
                    title = title,
                    status = RunStatus.fromApi(status.optString("status")),
                    startMillis = parseTime(lastRun),
                    failure = status.optString("failureMessage"),
                )
            }
        }.awaitAll()
            .sortedWith(compareByDescending<Run> { it.status.active }.thenByDescending { it.startMillis })
    }

    /** (ref, title, lastRunTime) for the user's own notebooks, newest run first. */
    suspend fun listMine(): List<Triple<String, String, String>> {
        val arr = JSONArray(get("kernels/list?group=profile&pageSize=20&sortBy=dateRun"))
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Triple(o.getString("ref"), o.optString("title").ifEmpty { o.getString("ref") }, o.optString("lastRunTime"))
        }
    }

    private suspend fun status(ref: String): JSONObject {
        val user = enc(ref.substringBefore('/'))
        val slug = enc(ref.substringAfter('/'))
        return JSONObject(get("kernels/status?userName=$user&kernelSlug=$slug"))
    }

    private suspend fun get(path: String): String = withContext(Dispatchers.IO) {
        val conn = URL("https://www.kaggle.com/api/v1/$path").openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("Accept", "application/json")
            when (val code = conn.responseCode) {
                in 200..299 -> conn.inputStream.bufferedReader().use { it.readText() }
                401, 403 -> throw AuthException()
                else -> throw IOException("Kaggle API returned HTTP $code")
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun parseTime(s: String): Long =
        if (s.isBlank()) 0L else runCatching { Instant.parse(s).toEpochMilli() }.getOrDefault(0L)
}
