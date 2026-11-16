package io.github.mohnstollen.news.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.mohnstollen.news.NewsApp
import io.github.mohnstollen.news.data.MIN_INTERVAL_MINUTES
import io.github.mohnstollen.news.data.Prefs
import java.util.concurrent.TimeUnit

/** Periodic background job: refresh all feeds, then sync with Nextcloud if that is set up. */
class RefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as NewsApp).container
        val prefs = container.settings.current
        if (prefs.syncConfigured && prefs.ncAutoSync) {
            // First, so that feeds added on another device are fetched right away.
            runCatching { container.sync.sync() }
        }
        val result = container.repository.refreshAll()
        if (prefs.notifyNewArticles && result.notifyIds.isNotEmpty()) {
            val articles = container.repository.articleDao.listItems(result.notifyIds.take(500))
            Notifier.showNewArticles(applicationContext, articles)
        }
        return if (result.failedFeeds > 0 && result.newArticles == 0 && runAttemptCount < 2) Result.retry() else Result.success()
    }

    companion object {
        private const val WORK_NAME = "refresh"

        /** Brings the scheduled job in line with the settings. Safe to call repeatedly. */
        fun schedule(context: Context, prefs: Prefs) {
            val workManager = WorkManager.getInstance(context)
            if (!prefs.autoUpdate) {
                workManager.cancelUniqueWork(WORK_NAME)
                return
            }
            val interval = prefs.intervalMinutes.coerceAtLeast(MIN_INTERVAL_MINUTES).toLong()
            val request = PeriodicWorkRequestBuilder<RefreshWorker>(interval, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(if (prefs.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                        .setRequiresBatteryNotLow(true)
                        .build(),
                )
                .build()
            workManager.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }
    }
}
