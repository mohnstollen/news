package io.github.mohnstollen.news.playback

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import io.github.mohnstollen.news.data.MediaTypes
import io.github.mohnstollen.news.data.Repository
import io.github.mohnstollen.news.data.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File

/** A download that is waiting or running. */
data class DownloadProgress(
    val running: Boolean,
    val bytes: Long = 0,
    /** 0 while unknown. */
    val total: Long = 0,
) {
    /** Between 0 and 1, or null while the size is unknown. */
    val fraction: Float? get() = if (total > 0) (bytes.toFloat() / total).coerceIn(0f, 1f) else null
}

/**
 * Episode files in the app's own storage, one per article, named after its id. Downloading is done
 * by [DownloadWorker]; this class starts, cancels and deletes downloads and keeps the folder tidy.
 */
class Downloads(context: Context, private val repo: Repository, private val settings: SettingsStore) {
    private val context = context.applicationContext
    private val workManager get() = WorkManager.getInstance(context)

    /** App-specific storage, removed with the app. External storage if there is some, else internal. */
    val dir: File
        get() = (context.getExternalFilesDir("episodes") ?: File(context.filesDir, "episodes")).apply { mkdirs() }

    fun fileFor(articleId: Long, url: String, type: String?): File {
        val ext = url.substringBefore('?').substringBefore('#').substringAfterLast('/')
            .substringAfterLast('.', "").lowercase().takeIf { it.length in 2..4 && MediaTypes.fromUrl("x.$it") != null }
            ?: when {
                type == null -> "mp3"
                type.startsWith("video/") -> "mp4"
                type.contains("mp4") || type.contains("m4a") -> "m4a"
                type.contains("ogg") || type.contains("opus") -> "ogg"
                else -> "mp3"
            }
        return File(dir, "$articleId.$ext")
    }

    /**
     * Downloads episodes the user (or a podcast's automatic downloads) asked for. If set, they go
     * into the queue too: who downloads an episode wants to hear it.
     */
    suspend fun download(articleIds: Collection<Long>) {
        enqueue(articleIds)
        if (settings.current.downloadEnqueues) articleIds.forEach { repo.enqueueIfAbsent(it) }
    }

    /** An episode was put into the queue: fetch it, if set (the network setting still applies). */
    suspend fun onQueued(articleId: Long) {
        if (!settings.current.queueDownloads) return
        val article = repo.articleDao.byId(articleId) ?: return
        if (article.enclosureUrl == null || article.downloadPath != null) return
        enqueue(listOf(articleId))
    }

    /** Queues downloads; already queued or running ones are left alone. */
    fun enqueue(articleIds: Collection<Long>) {
        val network = if (settings.current.downloadWifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
        for (id in articleIds) {
            val request = OneTimeWorkRequestBuilder<DownloadWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(network).setRequiresStorageNotLow(true).build())
                .setInputData(workDataOf(DownloadWorker.KEY_ARTICLE to id))
                .addTag(TAG)
                .addTag(idTag(id))
                .build()
            workManager.enqueueUniqueWork(workName(id), ExistingWorkPolicy.KEEP, request)
        }
    }

    /** Auto-download: of the new articles, those with a media file. */
    suspend fun enqueueNewEpisodes(articleIds: List<Long>) {
        val episodes = articleIds.filter { repo.articleDao.byId(it)?.enclosureUrl != null }
        if (episodes.isNotEmpty()) download(episodes)
    }

    fun cancel(articleId: Long) {
        workManager.cancelUniqueWork(workName(articleId))
    }

    /** Cancels running downloads and removes finished ones. Playback falls back to streaming. */
    suspend fun delete(articleIds: Collection<Long>) = withContext(Dispatchers.IO) {
        for (id in articleIds) {
            cancel(id)
            val article = repo.articleDao.byId(id)
            article?.downloadPath?.let { File(it).delete() }
            dir.listFiles { f -> f.name.startsWith("$id.") }?.forEach { it.delete() }
            if (article?.downloadPath != null) repo.articleDao.setDownloadPath(id, null)
        }
    }

    suspend fun deleteFiles(paths: List<String>) = withContext(Dispatchers.IO) {
        paths.forEach { File(it).delete() }
    }

    suspend fun deleteAll() = delete(repo.articleDao.downloads().map { it.id } + activeIds())

    /** After an episode was played to the end, if the settings say so. */
    suspend fun onPlayed(articleId: Long) {
        if (settings.current.deletePlayedDownloads) delete(listOf(articleId))
    }

    /** Waiting and running downloads by article id. */
    val progress: Flow<Map<Long, DownloadProgress>> = workManager.getWorkInfosByTagFlow(TAG).map { infos ->
        infos.filter { !it.state.isFinished }.mapNotNull { info ->
            val id = idFrom(info) ?: return@mapNotNull null
            id to DownloadProgress(
                running = info.state == WorkInfo.State.RUNNING,
                bytes = info.progress.getLong(DownloadWorker.KEY_BYTES, 0),
                total = info.progress.getLong(DownloadWorker.KEY_TOTAL, 0),
            )
        }.toMap()
    }

    private suspend fun activeIds(): List<Long> = withContext(Dispatchers.IO) {
        workManager.getWorkInfosByTag(TAG).get().filter { !it.state.isFinished }.mapNotNull(::idFrom)
    }

    private fun idFrom(info: WorkInfo): Long? =
        info.tags.firstOrNull { it.startsWith(ID_TAG_PREFIX) }?.removePrefix(ID_TAG_PREFIX)?.toLongOrNull()

    /** Number of downloaded episodes and their size in bytes. */
    suspend fun usage(): Pair<Int, Long> = withContext(Dispatchers.IO) {
        val rows = repo.articleDao.downloads()
        rows.size to rows.sumOf { File(it.downloadPath).length() }
    }

    /**
     * Removes files no article points to (e.g. its feed was deleted or it was cleaned up) and forgets
     * downloads whose file is gone. Partial files of running or waiting downloads stay.
     */
    suspend fun cleanUp() = withContext(Dispatchers.IO) {
        val rows = repo.articleDao.downloads()
        rows.filter { !File(it.downloadPath).isFile }.forEach { repo.articleDao.setDownloadPath(it.id, null) }
        val kept = rows.map { File(it.downloadPath).name }.toSet()
        val active = activeIds().toSet()
        dir.listFiles()?.forEach { file ->
            val id = file.name.substringBefore('.').toLongOrNull()
            if (file.name !in kept && id !in active) file.delete()
        }
    }

    companion object {
        const val TAG = "episode-download"
        private const val ID_TAG_PREFIX = "episode:"
        private fun idTag(id: Long) = "$ID_TAG_PREFIX$id"
        private fun workName(id: Long) = "download-$id"
    }
}
