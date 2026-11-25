package io.github.mohnstollen.news.playback

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.github.mohnstollen.news.NewsApp
import io.github.mohnstollen.news.R
import io.github.mohnstollen.news.data.Article
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Downloads one episode into [Downloads.dir]. Writes to a ".part" file first and continues it
 * with an HTTP range request after an interruption. Shows a progress notification while running.
 */
class DownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val container get() = (applicationContext as NewsApp).container

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val id = inputData.getLong(KEY_ARTICLE, -1)
        val repo = container.repository
        val downloads = container.downloads
        val article = repo.articleDao.byId(id) ?: return@withContext Result.success()
        if (article.enclosureUrl == null) return@withContext Result.success()
        if (article.archivedAt > 0) return@withContext Result.success()
        if (article.downloadPath?.let { File(it).isFile } == true) return@withContext Result.success()

        // One download after the other, the next episode of the queue first.
        downloads.turns.run(id, onWait = { setProgress(workDataOf(KEY_WAITING_TURN to true)) }) {
            setProgress(workDataOf(KEY_WAITING_TURN to false))
            transfer(id, article)
        }
    }

    private suspend fun transfer(id: Long, article: Article): Result {
        val repo = container.repository
        val downloads = container.downloads
        val url = article.enclosureUrl ?: return Result.success()
        val target = downloads.fileFor(id, url, article.enclosureType)
        val part = File(target.path + ".part")
        val notificationId = NOTIFICATION_BASE + (id % 100_000).toInt()
        // Long downloads must run as a foreground service. Not allowed when started from the
        // background on newer Android versions; then it runs as a normal job.
        try {
            setForeground(foregroundInfo(notificationId, article.title, 0, 0))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Continue without the notification.
        }

        return try {
            val offset = if (part.isFile) part.length() else 0L
            val request = Request.Builder().url(url).apply { if (offset > 0) header("Range", "bytes=$offset-") }.build()
            container.mediaHttp.newCall(request).execute().use { response ->
                if (response.code == 416) {
                    // The partial file does not fit the file on the server any more: start over.
                    part.delete()
                    return Result.retry()
                }
                if (!response.isSuccessful) {
                    val permanent = response.code in 400..499
                    return if (permanent || runAttemptCount >= MAX_ATTEMPTS) {
                        Result.failure(workDataOf(KEY_ERROR to "HTTP ${response.code}"))
                    } else Result.retry()
                }
                val body = response.body ?: throw IOException("Leere Antwort")
                val append = response.code == 206 && offset > 0
                var done = if (append) offset else 0L
                val total = (body.contentLength().takeIf { it > 0 }?.plus(done))
                    ?: article.enclosureLength ?: 0L
                var lastReport = 0L
                FileOutputStream(part, append).use { out ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            if (isStopped) return Result.retry()
                            val n = input.read(buffer)
                            if (n < 0) break
                            out.write(buffer, 0, n)
                            done += n
                            val now = System.currentTimeMillis()
                            if (now - lastReport > PROGRESS_INTERVAL_MS) {
                                lastReport = now
                                setProgress(workDataOf(KEY_BYTES to done, KEY_TOTAL to total))
                                runCatching { setForeground(foregroundInfo(notificationId, article.title, done, total)) }
                            }
                        }
                    }
                }
            }
            if (target.exists()) target.delete()
            if (!part.renameTo(target)) throw IOException("Datei konnte nicht gespeichert werden")
            // The article may have been deleted or archived meanwhile.
            val current = repo.articleDao.byId(id)
            if (current == null || current.archivedAt > 0) {
                target.delete()
            } else {
                repo.articleDao.setDownloadPath(id, target.path)
            }
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            if (runAttemptCount >= MAX_ATTEMPTS) {
                part.delete()
                Result.failure(workDataOf(KEY_ERROR to (e.message ?: e.javaClass.simpleName)))
            } else Result.retry()
        }
    }

    /** For an urgent (expedited) download on older Android versions, which run it as a foreground service. */
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val id = inputData.getLong(KEY_ARTICLE, -1)
        val title = container.repository.articleDao.byId(id)?.title.orEmpty()
        return foregroundInfo(NOTIFICATION_BASE + (id % 100_000).toInt(), title, 0, 0)
    }

    private fun foregroundInfo(notificationId: Int, title: String, bytes: Long, total: Long): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW),
        )
        val percent = if (total > 0) (bytes * 100 / total).toInt().coerceIn(0, 100) else 0
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Folge wird geladen")
            .setContentText(title)
            .setOngoing(true)
            .setSilent(true)
            .setProgress(100, percent, total <= 0)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(notificationId, notification)
        }
    }

    companion object {
        const val KEY_ARTICLE = "article"
        const val KEY_BYTES = "bytes"
        const val KEY_TOTAL = "total"
        const val KEY_ERROR = "error"
        /** Progress: waiting for another download to finish. */
        const val KEY_WAITING_TURN = "waitingTurn"
        private const val CHANNEL = "downloads"
        private const val NOTIFICATION_BASE = 10_000
        private const val MAX_ATTEMPTS = 4
        private const val PROGRESS_INTERVAL_MS = 500L
    }
}
