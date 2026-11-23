package io.github.mohnstollen.news.data

import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.InputStream

data class RefreshResult(
    val newArticles: Int,
    val failedFeeds: Int,
    val skipped: Boolean = false,
    /** New articles of feeds that have notifications switched on. */
    val notifyIds: List<Long> = emptyList(),
)

/** At most this many new episodes per feed and update are downloaded automatically. */
private const val MAX_AUTO_DOWNLOADS = 5

class Repository(
    private val db: AppDatabase,
    private val fetcher: FeedFetcher,
    private val extractor: FullTextExtractor,
    private val settings: SettingsStore,
) {
    val feedDao = db.feedDao()
    val articleDao = db.articleDao()
    val queueDao = db.queueDao()
    val listenDao = db.listenDao()

    /** Outlives screens, so a refresh keeps running when the user navigates away. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing

    private val refreshMutex = Mutex()

    // ---- refresh ----------------------------------------------------------------------------

    /**
     * Gets the new articles of feeds with automatic downloads after each update, whoever started
     * it. Set by the app; it picks out the episodes.
     */
    var onNewEpisodes: (suspend (List<Long>) -> Unit)? = null

    /** Gets the downloaded files of a feed before it is deleted; set by the app. */
    var onFeedDeleting: (suspend (List<String>) -> Unit)? = null

    suspend fun refreshAll(): RefreshResult = refresh(null)

    /** Loads a feed without subscribing to it, e.g. to look at a podcast first. */
    suspend fun previewFeed(url: String): ParsedFeed = withContext(Dispatchers.IO) {
        try {
            fetcher.discover(url).feed
        } catch (e: FeedException) {
            throw e
        } catch (e: java.io.IOException) {
            throw FeedException("Feed nicht erreichbar: ${e.message ?: e.javaClass.simpleName}", e)
        }
    }

    /** Refreshes one feed, or all if [feedId] is null. Does nothing if a refresh is already running. */
    suspend fun refresh(feedId: Long?): RefreshResult = withContext(Dispatchers.IO) {
        if (!refreshMutex.tryLock()) return@withContext RefreshResult(0, 0, skipped = true)
        try {
            _refreshing.value = true
            val feeds = if (feedId == null) feedDao.all() else listOfNotNull(feedDao.byId(feedId))
            val parallel = Semaphore(4)
            val results = coroutineScope {
                feeds.map { feed -> async { parallel.withPermit { feed to refreshFeed(feed) } } }.awaitAll()
            }
            val autoDownload = results.filter { it.first.autoDownload }
                .flatMap { it.second.orEmpty().take(MAX_AUTO_DOWNLOADS) }
            if (autoDownload.isNotEmpty()) onNewEpisodes?.invoke(autoDownload)
            RefreshResult(
                newArticles = results.sumOf { it.second?.size ?: 0 },
                failedFeeds = results.count { it.second == null },
                notifyIds = results.filter { it.first.notify }.flatMap { it.second.orEmpty() },
            )
        } finally {
            _refreshing.value = false
            refreshMutex.unlock()
        }
    }

    /** Returns the ids of the new articles, or null if the feed could not be loaded. */
    private suspend fun refreshFeed(feed: Feed): List<Long>? {
        val now = System.currentTimeMillis()
        return try {
            when (val result = fetcher.fetch(feed.url, feed.etag, feed.lastModified)) {
                FeedFetcher.Result.NotModified -> {
                    feedDao.fetchSucceeded(feed.id, now, feed.etag, feed.lastModified, null)
                    emptyList()
                }
                is FeedFetcher.Result.Fetched -> {
                    val newIds = storeItems(feed, result.feed, now)
                    feedDao.fetchSucceeded(
                        feed.id, now, result.etag, result.lastModified, result.feed.siteUrl, result.feed.imageUrl,
                    )
                    if (feed.fetchFullText) {
                        newIds.take(MAX_FULL_TEXT_PER_REFRESH).forEach { runCatchingCancellable { loadFullText(it) } }
                    }
                    newIds
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            feedDao.fetchFailed(feed.id, now, e.message ?: e.javaClass.simpleName)
            null
        }
    }

    /** Inserts unknown entries and returns their ids. */
    private suspend fun storeItems(feed: Feed, parsed: ParsedFeed, now: Long): List<Long> {
        val items = parsed.items.distinctBy { it.guid }
        val articles = items.mapIndexed { index, item ->
            Article(
                feedId = feed.id,
                guid = item.guid,
                title = item.title,
                link = item.link,
                author = item.author,
                summary = item.contentHtml?.let { FeedParser.plainText(it).take(SUMMARY_LENGTH) }.orEmpty(),
                content = item.contentHtml?.let { FullTextExtractor.sanitize(it, item.link ?: feed.url) },
                // Undated entries keep the order of the feed. Dates in the future would pin an
                // entry to the top of the list, so they are capped.
                pubDate = (item.pubDate ?: (now - index)).coerceAtMost(now),
                fetchedAt = now,
                enclosureUrl = item.enclosure?.url,
                enclosureType = item.enclosure?.type,
                enclosureLength = item.enclosure?.length,
                durationSeconds = item.durationSeconds,
                imageUrl = item.imageUrl,
                chapters = item.chapters.takeIf { it.isNotEmpty() }?.let(Chapters::encode),
                chaptersUrl = item.chaptersUrl,
            )
        }
        val retention = settings.current.retentionDays
        return db.withTransaction {
            val ids = articleDao.insertAll(articles)

            // Entries that came in through sync have no text yet.
            val missing = articleDao.guidsWithoutContent(feed.id).toSet()
            if (missing.isNotEmpty()) {
                articles.filter { it.guid in missing && it.content != null }.forEach {
                    articleDao.backfillContent(feed.id, it.guid, it.content, it.summary)
                }
            }

            // Episodes stored before chapters were read get them now.
            val withChapters = articles.filter { it.chapters != null || it.chaptersUrl != null }
            if (withChapters.isNotEmpty()) {
                val noChapters = articleDao.guidsWithoutChapters(feed.id).toSet()
                withChapters.filter { it.guid in noChapters }.forEach {
                    articleDao.backfillChapters(feed.id, it.guid, it.chapters, it.chaptersUrl)
                }
            }

            // Entries stored before podcast support, or through sync, get their media file.
            val withEnclosure = articles.filter { it.enclosureUrl != null }
            if (withEnclosure.isNotEmpty()) {
                val noEnclosure = articleDao.guidsWithoutEnclosure(feed.id).toSet()
                withEnclosure.filter { it.guid in noEnclosure }.forEach {
                    articleDao.backfillEnclosure(
                        feed.id, it.guid, it.enclosureUrl!!, it.enclosureType, it.enclosureLength,
                        it.durationSeconds, it.imageUrl,
                    )
                }
            }

            val guids = articles.map { it.guid }
            if (retention > 0 && guids.size <= MAX_SQL_VARIABLES) {
                articleDao.deleteOld(feed.id, now - retention * DAY_MS, guids)
            }
            ids.filter { it != -1L }
        }
    }

    /** Downloads and stores the article behind the entry's link. Returns false if no text was found. */
    suspend fun loadFullText(articleId: Long): Boolean = withContext(Dispatchers.IO) {
        val article = articleDao.byId(articleId) ?: return@withContext false
        val link = article.link ?: return@withContext false
        val html = extractor.extract(link) ?: return@withContext false
        articleDao.setFullText(articleId, html)
        true
    }

    // ---- subscriptions ----------------------------------------------------------------------

    /** Blocking network check; throws [FeedException]. */
    suspend fun discover(input: String): FeedFetcher.Discovered = withContext(Dispatchers.IO) {
        fetcher.discover(input)
    }

    /** Verifies the address, subscribes and stores the entries that came with the check. */
    /**
     * Subscribes. With [autoDownload], new episodes are downloaded after each update, and the
     * newest one right away, so there is something to play offline.
     */
    suspend fun addFeed(input: String, category: String?, autoDownload: Boolean = false): Feed = withContext(Dispatchers.IO) {
        if (input.isBlank()) throw FeedException("Bitte eine Adresse eingeben")
        feedDao.byUrl(normalizeUrl(input))?.let { throw FeedException("„${it.title}“ ist bereits abonniert") }
        val found = fetcher.discover(input)
        feedDao.byUrl(found.url)?.let { throw FeedException("„${it.title}“ ist bereits abonniert") }
        val now = System.currentTimeMillis()
        val feed = Feed(
            url = found.url,
            title = found.feed.title.ifBlank { found.url },
            siteUrl = found.feed.siteUrl,
            category = category?.trim()?.ifBlank { null },
            updatedAt = now,
            autoDownload = autoDownload,
        )
        val id = feedDao.insert(feed)
        feedDao.removeTombstone(found.url)
        val stored = feed.copy(id = id)
        storeItems(stored, found.feed, now)
        feedDao.fetchSucceeded(id, now, null, null, found.feed.siteUrl, found.feed.imageUrl)
        if (autoDownload) {
            val newest = found.feed.items.filter { it.enclosure != null }.maxByOrNull { it.pubDate ?: 0 }
            newest?.let { articleDao.byGuid(id, it.guid) }?.let { onNewEpisodes?.invoke(listOf(it.id)) }
        }
        stored
    }

    /** Throws [FeedException] if a changed address is not a feed or is already subscribed. */
    suspend fun updateFeed(
        id: Long,
        title: String,
        url: String,
        category: String?,
        fetchFullText: Boolean,
        notify: Boolean? = null,
        autoDownload: Boolean? = null,
    ) =
        withContext(Dispatchers.IO) {
            val feed = feedDao.byId(id) ?: return@withContext
            if (title.isBlank()) throw FeedException("Bitte einen Titel eingeben")
            var newUrl = feed.url
            if (normalizeUrl(url) != feed.url) {
                newUrl = fetcher.discover(url).url
                feedDao.byUrl(newUrl)?.takeIf { it.id != id }
                    ?.let { throw FeedException("„${it.title}“ ist bereits abonniert") }
            }
            val now = System.currentTimeMillis()
            db.withTransaction {
                if (newUrl != feed.url) {
                    feedDao.putTombstone(DeletedFeed(feed.url, now))
                    feedDao.removeTombstone(newUrl)
                    feedDao.clearCacheHeaders(id)
                }
                feedDao.updateSubscription(id, title.trim(), newUrl, category?.trim()?.ifBlank { null }, fetchFullText, now)
                if (notify != null) feedDao.setNotify(id, notify)
                if (autoDownload != null) feedDao.setAutoDownload(id, autoDownload)
            }
        }

    suspend fun deleteFeed(id: Long) {
        onFeedDeleting?.invoke(articleDao.downloadPaths(id))
        deleteFeedRows(id)
    }

    private suspend fun deleteFeedRows(id: Long) = db.withTransaction {
        val feed = feedDao.byId(id) ?: return@withTransaction
        feedDao.delete(id)
        feedDao.putTombstone(DeletedFeed(feed.url, System.currentTimeMillis()))
    }

    // ---- OPML -------------------------------------------------------------------------------

    /** Returns (new subscriptions, entries in the file). */
    suspend fun importOpml(input: InputStream): Pair<Int, Int> = withContext(Dispatchers.IO) {
        val entries = Opml.parse(input)
        val now = System.currentTimeMillis()
        var added = 0
        db.withTransaction {
            for (entry in entries) {
                if (feedDao.byUrl(entry.url) != null) continue
                feedDao.insert(
                    Feed(
                        url = entry.url,
                        title = entry.title,
                        siteUrl = entry.siteUrl,
                        category = entry.category,
                        updatedAt = now,
                    ),
                )
                feedDao.removeTombstone(entry.url)
                added++
            }
        }
        added to entries.size
    }

    suspend fun exportOpml(): String =
        Opml.write(feedDao.all().map { OpmlEntry(it.url, it.title, it.siteUrl, it.category) })

    // ---- article state ----------------------------------------------------------------------

    suspend fun setRead(ids: List<Long>, read: Boolean) {
        val readAt = if (read) System.currentTimeMillis() else 0L
        ids.chunked(MAX_SQL_VARIABLES).forEach { articleDao.setRead(it, read, readAt) }
    }

    /** Remembers where playback of an episode stopped. */
    suspend fun savePlayPosition(id: Long, positionMs: Long) = articleDao.setPlayPosition(id, positionMs)

    /** Played to the end counts as read; marking as not played leaves the read state alone. */
    suspend fun setPlayed(id: Long, played: Boolean) {
        val now = System.currentTimeMillis()
        db.withTransaction {
            articleDao.setPlayed(id, if (played) now else 0)
            if (played) {
                articleDao.markReadIfUnread(listOf(id), now)
                // Heard: done with it, it leaves the queue.
                queueDao.remove(id)
            }
        }
    }

    /** Archived articles leave the lists and count as read; the search and the archive still show them. */
    suspend fun setArchived(ids: List<Long>, archived: Boolean) {
        val now = System.currentTimeMillis()
        db.withTransaction {
            ids.chunked(MAX_SQL_VARIABLES).forEach {
                articleDao.setArchived(it, if (archived) now else 0)
                if (archived) {
                    articleDao.markReadIfUnread(it, now)
                    it.forEach { id -> queueDao.remove(id) }
                }
            }
        }
    }

    // ---- Queue ------------------------------------------------------------------------------

    /** Puts an episode into the queue: next in line, or at the end. Moves it if already queued. */
    /** [handOn]: tell [onQueued] about a newly queued episode (which downloads it, if set). */
    suspend fun enqueue(articleId: Long, next: Boolean = false, handOn: Boolean = true) {
        val added = db.withTransaction {
            val wasQueued = articleId in queueDao.ids()
            val position = if (next) (queueDao.minPosition() ?: 1) - 1 else (queueDao.maxPosition() ?: -1) + 1
            queueDao.put(QueueEntry(articleId, position))
            !wasQueued
        }
        if (added && handOn) onQueued?.invoke(articleId)
    }

    /** Puts the episode into the queue unless it is there already; returns whether it was added. */
    suspend fun enqueueIfAbsent(articleId: Long, next: Boolean = false, handOn: Boolean = true): Boolean {
        if (articleId in queueDao.ids()) return false
        enqueue(articleId, next, handOn)
        return true
    }

    /** Gets each episode newly put into the queue; set by the app, e.g. to download it. */
    var onQueued: (suspend (Long) -> Unit)? = null

    suspend fun dequeue(articleId: Long) = queueDao.remove(articleId)

    /** Restores a queue order, e.g. to undo taking an episode out. Episodes not in [ids] are dropped. */
    suspend fun setQueueOrder(ids: List<Long>) = db.withTransaction {
        queueDao.clear()
        ids.forEachIndexed { i, id -> if (articleDao.byId(id) != null) queueDao.put(QueueEntry(id, i.toLong())) }
    }

    /** Swaps the episode with its neighbour above ([up]) or below. */
    suspend fun moveInQueue(articleId: Long, up: Boolean) = db.withTransaction {
        val ids = queueDao.ids().toMutableList()
        val index = ids.indexOf(articleId)
        val target = if (up) index - 1 else index + 1
        if (index < 0 || target !in ids.indices) return@withTransaction
        java.util.Collections.swap(ids, index, target)
        // Renumbered, so the positions are simply 0, 1, 2, ...
        ids.forEachIndexed { i, id -> queueDao.setPosition(id, i.toLong()) }
    }

    /**
     * The episode to play after [currentId]: the first one in the queue other than it. The queue
     * keeps the playing episode until it is played to the end.
     */
    suspend fun nextInQueue(currentId: Long?): Long? = queueDao.ids().firstOrNull { it != currentId }

    // ---- Statistics -------------------------------------------------------------------------

    /** Adds listening time to today's entry for the episode. */
    suspend fun logListening(articleId: Long, listenedMs: Long, contentMs: Long, finished: Boolean = false) {
        if (listenedMs <= 0 && !finished) return
        val article = articleDao.byId(articleId) ?: return
        val feed = feedDao.byId(article.feedId) ?: return
        val day = java.time.LocalDate.now().toEpochDay()
        val done = if (finished) 1 else 0
        db.withTransaction {
            val updated = listenDao.add(day, articleId, feed.title, listenedMs, contentMs, done)
            if (updated == 0) listenDao.insert(ListenEntry(day, articleId, feed.url, feed.title, listenedMs, contentMs, done))
        }
    }

    suspend fun setStarred(id: Long, starred: Boolean) =
        articleDao.setStarred(id, starred, System.currentTimeMillis())

    suspend fun setNote(id: Long, note: String?) =
        articleDao.setNote(id, note?.trim()?.ifBlank { null }, System.currentTimeMillis())

    // ---- sync -------------------------------------------------------------------------------

    suspend fun exportSyncState(): SyncState {
        val feeds = feedDao.all().map {
            SyncFeed(it.url, it.title, it.siteUrl, it.category, it.fetchFullText, it.updatedAt)
        }
        val tombstones = feedDao.tombstones().map { SyncFeed(url = it.url, updatedAt = it.deletedAt, deleted = true) }
        val articles = articleDao.syncRows().map {
            SyncArticle(it.feedUrl, it.guid, it.title, it.link, it.pubDate, it.starred, it.note, it.stateUpdatedAt)
        }
        return SyncState(feeds = feeds + tombstones, articles = articles)
    }

    /** Brings the local data in line with a merged state. Returns the number of feeds added. */
    suspend fun applySyncState(state: SyncState): Int = db.withTransaction {
        var added = 0
        val now = System.currentTimeMillis()
        for (f in state.feeds) {
            val local = feedDao.byUrl(f.url)
            if (f.deleted) {
                if (local != null) feedDao.delete(local.id)
                feedDao.putTombstone(DeletedFeed(f.url, f.updatedAt))
            } else {
                feedDao.removeTombstone(f.url)
                if (local == null) {
                    feedDao.insert(
                        Feed(
                            url = f.url,
                            title = f.title.ifBlank { f.url },
                            siteUrl = f.siteUrl,
                            category = f.category,
                            fetchFullText = f.fetchFullText,
                            updatedAt = f.updatedAt,
                        ),
                    )
                    added++
                } else if (f.updatedAt > local.updatedAt) {
                    feedDao.updateSubscription(
                        local.id, f.title.ifBlank { local.title }, f.url, f.category, f.fetchFullText, f.updatedAt,
                    )
                }
            }
        }
        val feedIds = feedDao.all().associate { it.url to it.id }
        for (a in state.articles) {
            val feedId = feedIds[a.feedUrl] ?: continue
            val local = articleDao.byGuid(feedId, a.guid)
            if (local == null) {
                if (a.starred || !a.note.isNullOrBlank()) {
                    articleDao.insertAll(
                        listOf(
                            Article(
                                feedId = feedId,
                                guid = a.guid,
                                title = a.title,
                                link = a.link,
                                pubDate = a.pubDate,
                                fetchedAt = now,
                                read = true,
                                starred = a.starred,
                                note = a.note,
                                stateUpdatedAt = a.updatedAt,
                            ),
                        ),
                    )
                }
            } else if (a.updatedAt > local.stateUpdatedAt) {
                articleDao.applySyncedState(local.id, a.starred, a.note, a.updatedAt)
            }
        }
        added
    }

    private suspend inline fun runCatchingCancellable(block: () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    private companion object {
        const val SUMMARY_LENGTH = 300
        const val MAX_FULL_TEXT_PER_REFRESH = 20
        const val MAX_SQL_VARIABLES = 900
        const val DAY_MS = 24L * 60 * 60 * 1000
    }
}
