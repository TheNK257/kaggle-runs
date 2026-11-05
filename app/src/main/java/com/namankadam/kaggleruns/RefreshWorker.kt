package com.namankadam.kaggleruns

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.io.IOException
import java.util.concurrent.TimeUnit

class RefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val token = TokenStore.load(ctx)
        if (token == null) {
            WidgetRenderer.updateAll(ctx)
            return Result.success()
        }
        val previous = SnapshotCache.load(ctx)
        val snapshot = try {
            Snapshot(KaggleApi(token).fetchRuns(), System.currentTimeMillis(), null)
        } catch (e: AuthException) {
            Snapshot(previous?.runs.orEmpty(), previous?.fetchedAt ?: 0L, "Token rejected – tap ⚙ to update")
        } catch (e: IOException) {
            Snapshot(previous?.runs.orEmpty(), previous?.fetchedAt ?: 0L, "Can't reach Kaggle")
        } catch (e: Exception) {
            Snapshot(previous?.runs.orEmpty(), previous?.fetchedAt ?: 0L, "Unexpected response from Kaggle")
        }
        SnapshotCache.save(ctx, snapshot)
        WidgetRenderer.updateAll(ctx)
        return Result.success()
    }

    companion object {
        private const val PERIODIC = "kaggle-refresh-periodic"
        private const val NOW = "kaggle-refresh-now"

        private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        /** Every 15 minutes, the shortest interval Android allows for background work. */
        fun schedulePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<RefreshWorker>(15, TimeUnit.MINUTES)
                .setConstraints(online)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun refreshNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<RefreshWorker>().setConstraints(online).build()
            WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.REPLACE, request)
        }

        fun cancelAll(context: Context) {
            WorkManager.getInstance(context).apply {
                cancelUniqueWork(PERIODIC)
                cancelUniqueWork(NOW)
            }
        }
    }
}
