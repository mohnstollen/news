package io.github.mohnstollen.news.desktop

import io.github.mohnstollen.news.data.MIN_INTERVAL_MINUTES
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Background updates while the app runs (in the window or in the tray): sync with Nextcloud if
 * that is set up, refresh all feeds and announce new articles. The desktop counterpart of the
 * Android RefreshWorker.
 */
class Updater(private val container: AppContainer, private val scope: CoroutineScope) {
    private var job: Job? = null

    /** Called with the texts of a "new articles" notification when notify-send is missing. */
    var fallbackNotifier: ((String, String) -> Unit)? = null

    fun start() {
        scope.launch {
            container.settings.flow.map { it.autoUpdate to it.intervalMinutes }.distinctUntilChanged()
                .collect { (auto, interval) -> reschedule(auto, interval) }
        }
    }

    private fun reschedule(auto: Boolean, intervalMinutes: Int) {
        job?.cancel()
        if (!auto) return
        val interval = intervalMinutes.coerceAtLeast(MIN_INTERVAL_MINUTES) * 60_000L
        job = scope.launch {
            // The first run waits until the interval has passed since the last load, so that
            // restarting the app does not hit every server again.
            val last = container.repository.feedDao.lastFetched() ?: 0L
            val wait = (last + interval - System.currentTimeMillis()).coerceIn(10_000L, interval)
            delay(wait)
            while (true) {
                runOnce(manual = false)
                delay(interval)
            }
        }
    }

    suspend fun runOnce(manual: Boolean) {
        val prefs = container.settings.current
        if (prefs.syncConfigured && prefs.ncAutoSync) runCatching { container.sync.sync() }
        val result = container.repository.refreshAll()
        if (!manual && prefs.notifyNewArticles && result.notifyIds.isNotEmpty()) {
            val articles = container.repository.articleDao.listItems(result.notifyIds.take(500))
            if (articles.isNotEmpty()) {
                val (title, body) = Platform.newArticlesNotification(articles)
                if (!Platform.notify(title, body)) fallbackNotifier?.invoke(title, body)
            }
        }
    }
}
