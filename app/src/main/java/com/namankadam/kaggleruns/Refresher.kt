package com.namankadam.kaggleruns

import android.content.Context
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

/** In-memory state the app's screens observe; the cache on disk is the source of truth. */
object AppState {
    val snapshot = MutableStateFlow<Snapshot?>(null)
    val refreshing = MutableStateFlow(false)
    val hasToken = MutableStateFlow(false)

    fun init(context: Context) {
        hasToken.value = TokenStore.load(context) != null
        snapshot.value = SnapshotCache.load(context)
    }
}

/** One refresh shared by the background worker, the widgets' ⟳ button and the app's pull-to-refresh. */
object Refresher {
    private val lock = Mutex()

    suspend fun refresh(context: Context): Snapshot? = lock.withLock {
        val ctx = context.applicationContext
        val token = TokenStore.load(ctx)
        if (token == null) {
            AppState.snapshot.value = null
            WidgetUpdater.updateAll(ctx)
            return null
        }
        AppState.refreshing.value = true
        try {
            val previous = SnapshotCache.load(ctx)
            val next = try {
                fetch(ctx, KaggleApi(token), previous)
            } catch (e: CancellationException) {
                throw e
            } catch (e: AuthException) {
                previous.withError("Token rejected – open the app to update it")
            } catch (e: IOException) {
                previous.withError("Can't reach Kaggle")
            } catch (e: Exception) {
                previous.withError("Unexpected response from Kaggle")
            }
            if (next.error == null) {
                next.competitions.forEach { HistoryStore.record(ctx, it, next.fetchedAt) }
                Notifier.notifyChanges(ctx, previous, next)
            }
            SnapshotCache.save(ctx, next)
            AppState.snapshot.value = next
            WidgetUpdater.updateAll(ctx)
            return next
        } finally {
            AppState.refreshing.value = false
        }
    }

    private suspend fun fetch(ctx: Context, api: KaggleApi, previous: Snapshot?): Snapshot = coroutineScope {
        val runs = async { withDurations(ctx, api, api.fetchRuns()) }
        // Competitions are secondary: if only they fail, keep the last known ones rather than failing the refresh.
        val competitions = async {
            try {
                Result.success(api.fetchCompetitions())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
        val comps = competitions.await().getOrElse {
            if (it is AuthException) throw it
            previous?.competitions.orEmpty()
        }
        Snapshot(runs.await(), comps, System.currentTimeMillis(), null)
    }

    private suspend fun withDurations(ctx: Context, api: KaggleApi, runs: List<Run>): List<Run> = coroutineScope {
        val cache = DurationCache.load(ctx)
        val result = runs.map { run ->
            async {
                if (run.status.active || run.status == RunStatus.UNKNOWN || run.startMillis == 0L) return@async run
                val sec = cache[DurationCache.key(run)] ?: try {
                    api.runDurationSec(run.ref)
                } catch (e: AuthException) {
                    throw e
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null // log not ready yet; try again next refresh
                }
                run.copy(durationSec = sec)
            }
        }.awaitAll()
        DurationCache.save(ctx, result.mapNotNull { r -> r.durationSec?.let { DurationCache.key(r) to it } }.toMap())
        result
    }

    private fun Snapshot?.withError(message: String) =
        Snapshot(this?.runs.orEmpty(), this?.competitions.orEmpty(), this?.fetchedAt ?: 0L, message)
}
