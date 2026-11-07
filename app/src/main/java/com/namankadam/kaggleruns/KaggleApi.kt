package com.namankadam.kaggleruns

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToLong

class AuthException : IOException("Kaggle rejected the token")

data class KernelInfo(val ref: String, val title: String, val lastRunTime: String)

@Serializable private data class KernelDto(val ref: String = "", val title: String = "", val lastRunTime: String = "")
@Serializable private data class StatusDto(val status: String = "", val failureMessage: String = "")
@Serializable private data class OutputDto(val log: String = "")
@Serializable private data class LogLineDto(val time: Double = 0.0)
@Serializable private data class LeaderboardDto(val submissions: List<LeaderboardRowDto> = emptyList())
@Serializable private data class LeaderboardRowDto(val score: String = "")

@Serializable
private data class CompetitionDto(
    val ref: String = "",
    val title: String = "",
    val deadline: String = "",
    val teamCount: Int = 0,
    val userHasEntered: Boolean = false,
    val userRank: Int? = null,
    val maxDailySubmissions: Int = 0,
)

@Serializable
private data class SubmissionDto(
    val ref: Long = 0,
    val date: String = "",
    val description: String = "",
    val status: String = "",
    val publicScore: String = "",
    val privateScore: String = "",
    val errorDescription: String = "",
    val url: String = "",
)

/** Minimal client for the Kaggle REST endpoints the app needs. */
class KaggleApi(private val token: String) {

    /** Status of the most recently run notebooks, active runs first. */
    suspend fun fetchRuns(checkLatest: Int = 8): List<Run> = coroutineScope {
        listMine()
            .filter { it.lastRunTime.isNotBlank() } // never-run notebooks have no status
            .take(checkLatest)
            .map { kernel ->
                async(Dispatchers.IO) {
                    val status = try {
                        status(kernel.ref)
                    } catch (e: AuthException) {
                        throw e
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        StatusDto() // one bad lookup shouldn't hide the other notebooks
                    }
                    Run(
                        ref = kernel.ref,
                        title = kernel.title,
                        status = RunStatus.fromApi(status.status),
                        startMillis = parseTime(kernel.lastRunTime),
                        failure = status.failureMessage,
                    )
                }
            }.awaitAll()
            .sortedWith(compareByDescending<Run> { it.status.active }.thenByDescending { it.startMillis })
    }

    /** The user's own notebooks, newest run first. */
    suspend fun listMine(): List<KernelInfo> =
        decode<List<KernelDto>>(get("kernels/list?group=profile&pageSize=20&sortBy=dateRun")).map {
            KernelInfo(it.ref, it.title.ifEmpty { it.ref }, it.lastRunTime)
        }

    /** Run length from the log: every line carries seconds since the run started. */
    suspend fun runDurationSec(ref: String): Long? {
        val output = decode<OutputDto>(get("kernels/output?userName=${user(ref)}&kernelSlug=${slug(ref)}"))
        if (output.log.isBlank()) return null
        val lines = AppJson.decodeFromString<List<LogLineDto>>(output.log)
        return lines.maxOfOrNull { it.time }?.roundToLong()
    }

    /** Active competitions the user has entered, with submissions and leaderboard context. */
    suspend fun fetchCompetitions(): List<Competition> = coroutineScope {
        val now = System.currentTimeMillis()
        decode<List<CompetitionDto>>(get("competitions/list?group=entered"))
            .filter { it.userHasEntered }
            .map { it to parseTime(it.deadline) }
            .filter { (_, deadline) -> deadline == 0L || deadline > now }
            .map { (dto, deadline) ->
                async(Dispatchers.IO) {
                    val slug = dto.ref.trimEnd('/').substringAfterLast('/')
                    val submissions = async { submissions(slug) }
                    val top = try {
                        decode<LeaderboardDto>(get("competitions/${enc(slug)}/leaderboard/view")).submissions.map { it.score }
                    } catch (e: AuthException) {
                        throw e
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        emptyList()
                    }
                    val scores = top.mapNotNull { it.toDoubleOrNull() }
                    Competition(
                        slug = slug,
                        title = dto.title.ifEmpty { slug },
                        deadlineMillis = deadline,
                        teamCount = dto.teamCount,
                        userRank = dto.userRank?.takeIf { it > 0 },
                        maxDaily = dto.maxDailySubmissions,
                        higherIsBetter = scores.size < 2 || scores.first() >= scores.last(),
                        topScore = top.firstOrNull(),
                        submissions = submissions.await(),
                    )
                }
            }.awaitAll()
    }

    /** All pages of the user's submissions (capped), newest first. */
    private suspend fun submissions(slug: String): List<Submission> {
        val byId = LinkedHashMap<Long, Submission>()
        for (page in 1..5) {
            val batch = decode<List<SubmissionDto>>(get("competitions/submissions/list/${enc(slug)}?page=$page"))
            val fresh = batch.filter { it.ref !in byId }
            if (fresh.isEmpty()) break // empty page, or the API ignored the page number
            fresh.forEach { byId[it.ref] = it.toSubmission() }
        }
        return byId.values.sortedByDescending { it.dateMillis }
    }

    private fun SubmissionDto.toSubmission() = Submission(
        id = ref,
        dateMillis = parseTime(date),
        description = description,
        status = SubmissionStatus.fromApi(status),
        publicScore = publicScore,
        privateScore = privateScore,
        error = errorDescription,
        // e.g. "/code/user/notebook-slug?scriptVersionId=123"
        notebookRef = url.takeIf { it.startsWith("/code/") }?.removePrefix("/code/")?.substringBefore('?')?.trimEnd('/'),
    )

    private suspend fun status(ref: String): StatusDto =
        decode(get("kernels/status?userName=${user(ref)}&kernelSlug=${slug(ref)}"))

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

    private inline fun <reified T> decode(json: String): T = AppJson.decodeFromString(json)

    private fun user(ref: String) = enc(ref.substringBefore('/'))
    private fun slug(ref: String) = enc(ref.substringAfter('/'))
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun parseTime(s: String): Long =
        if (s.isBlank()) 0L else runCatching { Instant.parse(s).toEpochMilli() }.getOrDefault(0L)
}
