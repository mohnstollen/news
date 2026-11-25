package io.github.mohnstollen.news.playback

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OutOfQuotaPolicy
import android.net.ConnectivityManager
import android.os.StatFs
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
    /** Why it is not running; null while it runs. */
    val waiting: Waiting? = null,
) {
    enum class Waiting {
        /** Another download runs; this one is next or later. */
        TURN,
        /** "Downloads nur im WLAN" and there is none. */
        WIFI,
        /** No network yet, or a retry after an error. */
        NETWORK,
    }

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

    /** One download at a time, in the order of the queue; episodes not in it after those that are. */
    val turns = DownloadTurns { ids ->
        val queue = repo.queueDao.ids()
        ids.associateWith { id -> queue.indexOf(id).takeIf { it >= 0 } ?: Int.MAX_VALUE }
    }

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
    suspend fun download(articleIds: Collection<Long>, byUser: Boolean = true) {
        enqueue(articleIds, urgent = byUser)
        if (settings.current.downloadEnqueues) articleIds.forEach { repo.enqueueIfAbsent(it) }
    }

    /** An episode was put into the queue: fetch it, if set (the network setting still applies). */
    suspend fun onQueued(articleId: Long) {
        if (!settings.current.queueDownloads) return
        val article = repo.articleDao.byId(articleId) ?: return
        if (article.enclosureUrl == null || article.downloadPath != null) return
        enqueue(listOf(articleId), urgent = true)
    }

    /**
     * Queues downloads; already queued or running ones are left alone. [anyNetwork]: also over
     * mobile data, whatever the setting says. [urgent]: asked for by the user, so Android should
     * start it right away instead of when it suits (expedited work, where its quota allows).
     */
    fun enqueue(
        articleIds: Collection<Long>,
        anyNetwork: Boolean = false,
        policy: ExistingWorkPolicy = ExistingWorkPolicy.KEEP,
        urgent: Boolean = false,
    ) {
        val wifiOnly = settings.current.downloadWifiOnly && !anyNetwork
        val network = if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
        for (id in articleIds) {
            val request = OneTimeWorkRequestBuilder<DownloadWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(network).setRequiresStorageNotLow(true).build())
                .setInputData(workDataOf(DownloadWorker.KEY_ARTICLE to id))
                .addTag(TAG)
                .addTag(idTag(id))
                .apply {
                    if (wifiOnly) addTag(WIFI_TAG)
                    if (anyNetwork) addTag(ANY_NETWORK_TAG)
                    if (urgent) setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                }
                .build()
            workManager.enqueueUniqueWork(workName(id), policy, request)
        }
    }

    /** A download waiting for Wi-Fi goes ahead over mobile data, just this one. */
    suspend fun downloadNow(articleId: Long) = withContext(Dispatchers.IO) {
        val waiting = workManager.getWorkInfosForUniqueWork(workName(articleId)).get()
            .any { it.state == WorkInfo.State.ENQUEUED }
        if (waiting) enqueue(listOf(articleId), anyNetwork = true, policy = ExistingWorkPolicy.REPLACE, urgent = true)
    }

    /**
     * "Downloads nur im WLAN" was switched: downloads still waiting follow the new setting at
     * once. Running ones go on; those let over mobile data by hand stay so.
     */
    suspend fun followNetworkSetting() = withContext(Dispatchers.IO) {
        val ids = workManager.getWorkInfosByTag(TAG).get()
            .filter { it.state == WorkInfo.State.ENQUEUED && ANY_NETWORK_TAG !in it.tags }
            .mapNotNull(::idFrom)
        enqueue(ids, policy = ExistingWorkPolicy.REPLACE)
    }

    /** Auto-download: of the new articles, those with a media file. */
    suspend fun enqueueNewEpisodes(articleIds: List<Long>) {
        val episodes = articleIds.filter { repo.articleDao.byId(it)?.enclosureUrl != null }
        if (episodes.isNotEmpty()) download(episodes, byUser = false)
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
            val running = info.state == WorkInfo.State.RUNNING
            val waiting = when {
                running && info.progress.getBoolean(DownloadWorker.KEY_WAITING_TURN, false) -> DownloadProgress.Waiting.TURN
                running -> null
                WIFI_TAG in info.tags -> DownloadProgress.Waiting.WIFI
                else -> DownloadProgress.Waiting.NETWORK
            }
            id to DownloadProgress(
                running = waiting == null,
                bytes = info.progress.getLong(DownloadWorker.KEY_BYTES, 0),
                total = info.progress.getLong(DownloadWorker.KEY_TOTAL, 0),
                waiting = waiting,
            )
        }.toMap()
    }

    private suspend fun activeIds(): List<Long> = withContext(Dispatchers.IO) {
        workManager.getWorkInfosByTag(TAG).get().filter { !it.state.isFinished }.mapNotNull(::idFrom)
    }

    private fun idFrom(info: WorkInfo): Long? =
        info.tags.firstOrNull { it.startsWith(ID_TAG_PREFIX) }?.removePrefix(ID_TAG_PREFIX)?.toLongOrNull()

    /**
     * What keeps waiting downloads from starting although a network is there, as Android sees it:
     * background data on mobile networks blocked for the app (Data Saver, or the app's own data
     * setting), or storage nearly full. Null if nothing is known to stand in the way.
     */
    fun blocker(): String? {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        if (connectivity != null && connectivity.isActiveNetworkMetered &&
            connectivity.restrictBackgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED
        ) {
            return "Android sperrt mobile Daten im Hintergrund für diese App (Datensparmodus oder Datennutzung " +
                "der App), deshalb starten Downloads über Mobilfunk nicht. Erlauben unter App-Info → Mobile Daten."
        }
        val free = runCatching { StatFs(dir.path).availableBytes }.getOrNull()
        if (free != null && free < LOW_STORAGE_BYTES) {
            return "Der Speicher ist fast voll; Android startet Downloads erst, wenn wieder Platz ist."
        }
        return null
    }

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
        /** Below this, Android counts storage as low and holds downloads back (roughly). */
        private const val LOW_STORAGE_BYTES = 500L * 1024 * 1024
        /** Waits for a network without volume limit. */
        private const val WIFI_TAG = "episode-download-wifi"
        /** Let over mobile data by hand. */
        private const val ANY_NETWORK_TAG = "episode-download-any-network"
        private const val ID_TAG_PREFIX = "episode:"
        private fun idTag(id: Long) = "$ID_TAG_PREFIX$id"
        private fun workName(id: Long) = "download-$id"
    }
}
