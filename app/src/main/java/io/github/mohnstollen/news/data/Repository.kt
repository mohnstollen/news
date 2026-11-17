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

class Repository(
    private val db: AppDatabase,
    private val fetcher: FeedFetcher,
    private val extractor: FullTextExtractor,
    private val settings: SettingsStore,
) {
    val feedDao = db.feedDao()
    val articleDao = db.articleDao()

    /** Outlives screens, so a refresh keeps running when the user navigates away. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing

    private val refreshMutex = Mutex()

    /**
     * Read markings from the last sync for articles that are not here yet (feed address to guid
     * to marking, see [SyncState.reads]). Applied when the articles arrive with the next refresh.
     */
    private val pendingReads = java.util.concurrent.ConcurrentHashMap<Pair<String, String>, Long>()

    // ---- refresh ----------------------------------------------------------------------------

    suspend fun refreshAll(): RefreshResult = refresh(null)

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
                    feedDao.fetchSucceeded(feed.id, now, result.etag, result.lastModified, result.feed.siteUrl)
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
            )
        }
        val retention = settings.current.retentionDays
        return db.withTransaction {
            val ids = articleDao.insertAll(articles)

            // Articles that another device already marked read arrive as read, and are not "new".
            val alreadyRead = mutableSetOf<Long>()
            if (pendingReads.isNotEmpty()) {
                articles.forEachIndexed { i, article ->
                    val marked = pendingReads.remove(feed.url to article.guid) ?: return@forEachIndexed
                    if (ids[i] != -1L && marked > 0) {
                        articleDao.applySyncedRead(ids[i], true, marked, marked)
                        alreadyRead += ids[i]
                    }
                }
            }

            // Entries that came in through sync have no text yet.
            val missing = articleDao.guidsWithoutContent(feed.id).toSet()
            if (missing.isNotEmpty()) {
                articles.filter { it.guid in missing && it.content != null }.forEach {
                    articleDao.backfillContent(feed.id, it.guid, it.content, it.summary)
                }
            }

            val guids = articles.map { it.guid }
            if (retention > 0 && guids.size <= MAX_SQL_VARIABLES) {
                articleDao.deleteOld(feed.id, now - retention * DAY_MS, guids)
            }
            ids.filter { it != -1L && it !in alreadyRead }
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
    suspend fun addFeed(input: String, category: String?): Feed = withContext(Dispatchers.IO) {
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
        )
        val id = feedDao.insert(feed)
        feedDao.removeTombstone(found.url)
        val stored = feed.copy(id = id)
        storeItems(stored, found.feed, now)
        feedDao.fetchSucceeded(id, now, null, null, found.feed.siteUrl)
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
            }
        }

    suspend fun deleteFeed(id: Long) = db.withTransaction {
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
        val now = System.currentTimeMillis()
        ids.chunked(MAX_SQL_VARIABLES).forEach { articleDao.setRead(it, read, readAt, now) }
    }

    suspend fun setStarred(id: Long, starred: Boolean) =
        articleDao.setStarred(id, starred, System.currentTimeMillis())

    suspend fun setNote(id: Long, note: String?) =
        articleDao.setNote(id, note?.trim()?.ifBlank { null }, System.currentTimeMillis())

    // ---- sync -------------------------------------------------------------------------------

    suspend fun exportSyncState(includeReads: Boolean = true): SyncState {
        val feeds = feedDao.all().map {
            SyncFeed(it.url, it.title, it.siteUrl, it.category, it.fetchFullText, it.updatedAt)
        }
        val tombstones = feedDao.tombstones().map { SyncFeed(url = it.url, updatedAt = it.deletedAt, deleted = true) }
        val articles = articleDao.syncRows().map {
            SyncArticle(it.feedUrl, it.guid, it.title, it.link, it.pubDate, it.starred, it.note, it.stateUpdatedAt)
        }
        val reads = if (!includeReads) emptyMap() else {
            articleDao.readSyncRows(System.currentTimeMillis() - SyncMerge.READ_KEEP_MS)
                .groupBy { it.feedUrl }
                .mapValues { (_, rows) ->
                    rows.associate { it.guid to if (it.read) it.readUpdatedAt else -it.readUpdatedAt }
                }
        }
        return SyncState(feeds = feeds + tombstones, articles = articles, reads = reads)
    }

    /** Brings the local data in line with a merged state. Returns the number of feeds added. */
    suspend fun applySyncState(state: SyncState, applyReads: Boolean = true): Int = db.withTransaction {
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
        if (applyReads) {
            pendingReads.clear()
            for ((feedUrl, entries) in state.reads) {
                val feedId = feedIds[feedUrl] ?: continue
                for ((guid, marked) in entries) {
                    val local = articleDao.byGuid(feedId, guid)
                    val changedAt = kotlin.math.abs(marked)
                    when {
                        local == null -> pendingReads[feedUrl to guid] = marked
                        changedAt > local.readUpdatedAt -> articleDao.applySyncedRead(
                            local.id, marked > 0, if (marked > 0) changedAt else 0L, changedAt,
                        )
                    }
                }
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
