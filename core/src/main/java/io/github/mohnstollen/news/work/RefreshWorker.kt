package io.github.mohnstollen.news.work

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
import androidx.work.workDataOf
import io.github.mohnstollen.news.widget.NewsWidget
import io.github.mohnstollen.news.NewsApp
import io.github.mohnstollen.news.data.MIN_INTERVAL_MINUTES
import io.github.mohnstollen.news.data.Prefs
import io.github.mohnstollen.news.playback.CoverProvider
import android.graphics.BitmapFactory
import java.util.concurrent.TimeUnit

/**
 * Background job: sync with Nextcloud if that is set up, refresh all feeds, redraw the widget and
 * announce new articles. Runs at the update interval, at the widget interval, and on demand.
 */
class RefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as NewsApp).container
        val prefs = container.settings.current
        if (prefs.syncConfigured && prefs.ncAutoSync) {
            // First, so that feeds added on another device are fetched right away.
            runCatching { container.sync.sync() }
        }
        val result = container.repository.refreshAll()
        NewsWidget.updateAll(applicationContext)
        runCatching { container.downloads.cleanUp() }
        val manual = inputData.getBoolean(KEY_MANUAL, false)
        if (!manual && prefs.notifyNewArticles && result.notifyIds.isNotEmpty()) {
            val podcasts = container.features.podcasts
            // In Podfang only episodes: an entry without a file is no episode.
            val articles = container.repository.articleDao.listItems(result.notifyIds.take(500))
                .filter { !podcasts || it.hasAudio }
            val cover = articles.singleOrNull()?.takeIf { podcasts }?.let { episode ->
                runCatching { CoverProvider.file(applicationContext, CoverProvider.Cover.Episode(episode.id)) }.getOrNull()
                    ?.let { BitmapFactory.decodeFile(it.path) }
            }
            Notifier.showNewArticles(applicationContext, articles, cover)
        }
        return if (result.failedFeeds > 0 && result.newArticles == 0 && runAttemptCount < 2) Result.retry() else Result.success()
    }

    companion object {
        const val WORK_NAME = "refresh"
        const val WIDGET_WORK_NAME = "widget-refresh"
        private const val NOW_WORK_NAME = "refresh-now"
        private const val KEY_MANUAL = "manual"

        /**
         * Brings the scheduled jobs in line with the settings and with whether a widget is placed.
         * Safe to call repeatedly.
         */
        fun schedule(context: Context, prefs: Prefs) {
            periodic(context, WORK_NAME, prefs.intervalMinutes.takeIf { prefs.autoUpdate }, prefs.wifiOnly)
            // The widget keeps itself current at its own interval, also with automatic updates off.
            periodic(
                context, WIDGET_WORK_NAME, prefs.widgetIntervalMinutes.takeIf { NewsWidget.isPlaced(context) }, prefs.wifiOnly,
            )
        }

        private fun periodic(context: Context, name: String, intervalMinutes: Int?, wifiOnly: Boolean) {
            val workManager = WorkManager.getInstance(context)
            if (intervalMinutes == null) {
                workManager.cancelUniqueWork(name)
                return
            }
            val interval = intervalMinutes.coerceAtLeast(MIN_INTERVAL_MINUTES).toLong()
            val request = PeriodicWorkRequestBuilder<RefreshWorker>(interval, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                        .setRequiresBatteryNotLow(true)
                        .build(),
                )
                .build()
            workManager.enqueueUniquePeriodicWork(name, ExistingPeriodicWorkPolicy.UPDATE, request)
        }

        /** One refresh as soon as there is a connection, e.g. from the widget's button. No notification. */
        fun refreshNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<RefreshWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInputData(workDataOf(KEY_MANUAL to true))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(NOW_WORK_NAME, ExistingWorkPolicy.KEEP, request)
        }
    }
}
