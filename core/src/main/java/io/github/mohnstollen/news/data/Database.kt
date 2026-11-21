package io.github.mohnstollen.news.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Fts4
import androidx.room.FtsOptions
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "feeds", indices = [Index(value = ["url"], unique = true)])
data class Feed(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val url: String,
    val title: String,
    val siteUrl: String? = null,
    val category: String? = null,
    /** Download the full article text for new entries during a refresh. */
    val fetchFullText: Boolean = false,
    val lastFetched: Long = 0,
    val lastError: String? = null,
    val etag: String? = null,
    val lastModified: String? = null,
    /** Last time the user changed this subscription; used to resolve sync conflicts. */
    val updatedAt: Long = 0,
    /** Include this feed's new articles in the "new articles" notification. */
    @ColumnInfo(defaultValue = "1") val notify: Boolean = true,
    /** Cover art from the feed (podcast artwork); null if the feed has none. */
    val imageUrl: String? = null,
    /** Download new episodes of this feed after each update. */
    @ColumnInfo(defaultValue = "0") val autoDownload: Boolean = false,
)

@Entity(
    tableName = "articles",
    foreignKeys = [
        ForeignKey(
            entity = Feed::class,
            parentColumns = ["id"],
            childColumns = ["feedId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["feedId", "guid"], unique = true), Index("pubDate")],
)
data class Article(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val feedId: Long,
    val guid: String,
    val title: String,
    val link: String? = null,
    val author: String? = null,
    /** Plain-text teaser shown in the list. */
    val summary: String = "",
    /** HTML as delivered by the feed. */
    val content: String? = null,
    /** HTML extracted from the linked web page. */
    val fullText: String? = null,
    val pubDate: Long,
    val fetchedAt: Long,
    val read: Boolean = false,
    /** When it was marked read; 0 while unread. Read articles drop out of the lists after a while. */
    @ColumnInfo(defaultValue = "0") val readAt: Long = 0,
    val starred: Boolean = false,
    val note: String? = null,
    /** Last time star or note changed; 0 if never. Used for sync. */
    val stateUpdatedAt: Long = 0,
    /** Attached audio or video file (podcast episode); null for plain articles. */
    val enclosureUrl: String? = null,
    val enclosureType: String? = null,
    /** File size in bytes as stated by the feed; null if unknown. */
    val enclosureLength: Long? = null,
    /** Playing time in seconds as stated by the feed; null if unknown. */
    val durationSeconds: Long? = null,
    /** Artwork of the episode; null to fall back to the feed's image. */
    val imageUrl: String? = null,
    /** Where playback stopped, in milliseconds; 0 if never started or played to the end. */
    @ColumnInfo(defaultValue = "0") val playPosition: Long = 0,
    /** When the episode was played to the end; 0 if not. */
    @ColumnInfo(defaultValue = "0") val playedAt: Long = 0,
    /** Downloaded file in app storage; null if not downloaded. */
    val downloadPath: String? = null,
    /** When it was archived (left out of the lists); 0 if not archived. */
    @ColumnInfo(defaultValue = "0") val archivedAt: Long = 0,
    /** Chapters as JSON, see [Chapters]; null while none are known. */
    val chapters: String? = null,
    /** Podcasting 2.0 chapters file, loaded when the episode is played. */
    val chaptersUrl: String? = null,
)

/** Full-text index over the searchable columns of [Article]; Room keeps it in sync. */
@Fts4(contentEntity = Article::class, tokenizer = FtsOptions.TOKENIZER_UNICODE61)
@Entity(tableName = "articles_fts")
data class ArticleFts(
    val title: String,
    val summary: String,
    val content: String?,
    val fullText: String?,
    val note: String?,
)

/** Episodes waiting to be played, in the order of [position]. The current episode is not in it. */
@Entity(
    tableName = "queue",
    foreignKeys = [
        ForeignKey(entity = Article::class, parentColumns = ["id"], childColumns = ["articleId"], onDelete = ForeignKey.CASCADE),
    ],
)
data class QueueEntry(
    @PrimaryKey val articleId: Long,
    val position: Long,
)

data class QueueItem(
    val id: Long,
    val title: String,
    val feedTitle: String,
    val durationSeconds: Long?,
    val playPosition: Long,
    val downloaded: Boolean,
)

/**
 * Listening time per day and episode, for the statistics. Keeps the feed's address and title, so
 * the numbers survive when the episode or the feed is deleted.
 */
@Entity(tableName = "listen_log", primaryKeys = ["day", "articleId"])
data class ListenEntry(
    /** Local date as days since 1970-01-01. */
    val day: Long,
    val articleId: Long,
    val feedUrl: String,
    val feedTitle: String,
    /** Time actually spent listening. */
    val listenedMs: Long,
    /** Playing time of the episode that was covered; more than [listenedMs] at higher speeds. */
    val contentMs: Long,
    /** How often the episode was played to the end on that day. */
    val finished: Int = 0,
)

data class ListenTotals(
    val listenedMs: Long,
    val contentMs: Long,
    val finished: Int,
    val episodes: Int,
)

data class FeedListening(
    val feedUrl: String,
    val feedTitle: String,
    val listenedMs: Long,
    val episodes: Int,
)

data class DayListening(val day: Long, val listenedMs: Long)

@Dao
interface QueueDao {
    @Query(
        "SELECT a.id, a.title, f.title AS feedTitle, a.durationSeconds, a.playPosition, " +
            "(a.downloadPath IS NOT NULL) AS downloaded " +
            "FROM queue q JOIN articles a ON a.id = q.articleId JOIN feeds f ON f.id = a.feedId ORDER BY q.position",
    )
    fun observe(): Flow<List<QueueItem>>

    @Query("SELECT articleId FROM queue ORDER BY position")
    fun observeIds(): Flow<List<Long>>

    @Query("SELECT articleId FROM queue ORDER BY position")
    suspend fun ids(): List<Long>

    @Query("SELECT MIN(position) FROM queue")
    suspend fun minPosition(): Long?

    @Query("SELECT MAX(position) FROM queue")
    suspend fun maxPosition(): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entry: QueueEntry)

    @Query("DELETE FROM queue WHERE articleId = :articleId")
    suspend fun remove(articleId: Long)

    @Query("DELETE FROM queue")
    suspend fun clear()

    @Query("UPDATE queue SET position = :position WHERE articleId = :articleId")
    suspend fun setPosition(articleId: Long, position: Long)
}

@Dao
interface ListenDao {
    @Query(
        "UPDATE listen_log SET listenedMs = listenedMs + :listenedMs, contentMs = contentMs + :contentMs, " +
            "finished = finished + :finished, feedTitle = :feedTitle WHERE day = :day AND articleId = :articleId",
    )
    suspend fun add(day: Long, articleId: Long, feedTitle: String, listenedMs: Long, contentMs: Long, finished: Int): Int

    @Insert
    suspend fun insert(entry: ListenEntry)

    @Query(
        "SELECT COALESCE(SUM(listenedMs), 0) AS listenedMs, COALESCE(SUM(contentMs), 0) AS contentMs, " +
            "COALESCE(SUM(finished), 0) AS finished, COUNT(DISTINCT articleId) AS episodes " +
            "FROM listen_log WHERE day >= :fromDay",
    )
    fun observeTotals(fromDay: Long): Flow<ListenTotals>

    /** Feeds by listening time; the title is the one last seen. */
    @Query(
        "SELECT feedUrl, (SELECT l2.feedTitle FROM listen_log l2 WHERE l2.feedUrl = l.feedUrl ORDER BY l2.day DESC LIMIT 1) " +
            "AS feedTitle, SUM(listenedMs) AS listenedMs, COUNT(DISTINCT articleId) AS episodes " +
            "FROM listen_log l WHERE day >= :fromDay GROUP BY feedUrl ORDER BY listenedMs DESC LIMIT :limit",
    )
    fun observeTopFeeds(fromDay: Long, limit: Int): Flow<List<FeedListening>>

    @Query("SELECT day, SUM(listenedMs) AS listenedMs FROM listen_log WHERE day >= :fromDay GROUP BY day ORDER BY day")
    fun observeDays(fromDay: Long): Flow<List<DayListening>>

    @Query("SELECT DISTINCT feedUrl FROM listen_log")
    suspend fun feedUrls(): List<String>
}

/** Remembers unsubscribed feeds so a sync does not bring them back. */
@Entity(tableName = "deleted_feeds")
data class DeletedFeed(
    @PrimaryKey val url: String,
    val deletedAt: Long,
)

data class FeedWithUnread(
    @Embedded val feed: Feed,
    val unread: Int,
)

data class ArticleListItem(
    val id: Long,
    val feedId: Long,
    val feedTitle: String,
    val title: String,
    val summary: String,
    val pubDate: Long,
    val read: Boolean,
    val starred: Boolean,
    val hasNote: Boolean,
    /** Folder of the article's feed. */
    val category: String? = null,
    /** When it was marked read; 0 if unread or read before this was recorded. */
    val readAt: Long = 0,
    /** A podcast episode: the entry has an audio or video file. */
    val hasAudio: Boolean = false,
    val durationSeconds: Long? = null,
    val playPosition: Long = 0,
    val playedAt: Long = 0,
    val downloaded: Boolean = false,
    val archived: Boolean = false,
)

data class DownloadRow(val id: Long, val downloadPath: String)

data class DownloadedEpisode(
    val id: Long,
    val title: String,
    val feedTitle: String,
    val durationSeconds: Long?,
    val playPosition: Long,
    val downloadPath: String,
    val pubDate: Long,
)

/** Which articles [ArticleDao.observeList] returns, by archive state. */
const val NOT_ARCHIVED = 0
const val ARCHIVED = 1
const val ANY = 2

data class ArticleSyncRow(
    val feedUrl: String,
    val guid: String,
    val title: String,
    val link: String?,
    val pubDate: Long,
    val starred: Boolean,
    val note: String?,
    val stateUpdatedAt: Long,
)

@Dao
interface FeedDao {
    @Query(
        "SELECT f.*, (SELECT COUNT(*) FROM articles a WHERE a.feedId = f.id AND a.read = 0) AS unread " +
            "FROM feeds f ORDER BY f.title COLLATE NOCASE",
    )
    fun observeWithUnread(): Flow<List<FeedWithUnread>>

    @Query("SELECT * FROM feeds ORDER BY title COLLATE NOCASE")
    suspend fun all(): List<Feed>

    @Query("SELECT * FROM feeds WHERE id = :id")
    suspend fun byId(id: Long): Feed?

    @Query("SELECT * FROM feeds WHERE url = :url")
    suspend fun byUrl(url: String): Feed?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(feed: Feed): Long

    @Query(
        "UPDATE feeds SET title = :title, url = :url, category = :category, fetchFullText = :fetchFullText, " +
            "updatedAt = :updatedAt WHERE id = :id",
    )
    suspend fun updateSubscription(
        id: Long,
        title: String,
        url: String,
        category: String?,
        fetchFullText: Boolean,
        updatedAt: Long,
    )

    @Query("UPDATE feeds SET notify = :notify WHERE id = :id")
    suspend fun setNotify(id: Long, notify: Boolean)

    @Query("UPDATE feeds SET autoDownload = :autoDownload WHERE id = :id")
    suspend fun setAutoDownload(id: Long, autoDownload: Boolean)

    @Query("UPDATE feeds SET etag = NULL, lastModified = NULL WHERE id = :id")
    suspend fun clearCacheHeaders(id: Long)

    @Query(
        "UPDATE feeds SET lastFetched = :now, lastError = NULL, etag = :etag, lastModified = :lastModified, " +
            "siteUrl = COALESCE(:siteUrl, siteUrl), imageUrl = COALESCE(:imageUrl, imageUrl) WHERE id = :id",
    )
    suspend fun fetchSucceeded(
        id: Long,
        now: Long,
        etag: String?,
        lastModified: String?,
        siteUrl: String?,
        imageUrl: String? = null,
    )

    @Query("UPDATE feeds SET lastFetched = :now, lastError = :error WHERE id = :id")
    suspend fun fetchFailed(id: Long, now: Long, error: String)

    /** When the feeds were last loaded successfully or not; null without feeds. */
    @Query("SELECT MAX(lastFetched) FROM feeds")
    suspend fun lastFetched(): Long?

    @Query("DELETE FROM feeds WHERE id = :id")
    suspend fun delete(id: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putTombstone(tombstone: DeletedFeed)

    @Query("DELETE FROM deleted_feeds WHERE url = :url")
    suspend fun removeTombstone(url: String)

    @Query("SELECT * FROM deleted_feeds")
    suspend fun tombstones(): List<DeletedFeed>
}

@Dao
interface ArticleDao {
    @Query(
        "SELECT a.id, a.feedId, f.title AS feedTitle, a.title, a.summary, a.pubDate, a.read, a.starred, " +
            "(a.note IS NOT NULL AND a.note != '') AS hasNote, f.category AS category, a.readAt, " +
            "(a.enclosureUrl IS NOT NULL) AS hasAudio, a.durationSeconds, a.playPosition, a.playedAt, " +
            "(a.downloadPath IS NOT NULL) AS downloaded, (a.archivedAt > 0) AS archived " +
            "FROM articles a JOIN feeds f ON f.id = a.feedId " +
            "WHERE (:feedId IS NULL OR a.feedId = :feedId) AND (:starredOnly = 0 OR a.starred = 1) " +
            // archive: NOT_ARCHIVED (0), ARCHIVED (1) or ANY (2)
            "AND (:archive = 2 OR (:archive = 1) = (a.archivedAt > 0)) " +
            "ORDER BY a.pubDate DESC, a.id DESC LIMIT 5000",
    )
    fun observeList(feedId: Long?, starredOnly: Boolean, archive: Int = NOT_ARCHIVED): Flow<List<ArticleListItem>>

    /** [match] is an FTS expression, see [SearchQuery]. Read articles are included. */
    @Query(
        "SELECT a.id, a.feedId, f.title AS feedTitle, a.title, a.summary, a.pubDate, a.read, a.starred, " +
            "(a.note IS NOT NULL AND a.note != '') AS hasNote, f.category AS category, a.readAt, " +
            "(a.enclosureUrl IS NOT NULL) AS hasAudio, a.durationSeconds, a.playPosition, a.playedAt, " +
            "(a.downloadPath IS NOT NULL) AS downloaded, (a.archivedAt > 0) AS archived " +
            "FROM articles a JOIN feeds f ON f.id = a.feedId JOIN articles_fts ON articles_fts.docid = a.id " +
            "WHERE articles_fts MATCH :match " +
            "AND (:feedId IS NULL OR a.feedId = :feedId) AND (:starredOnly = 0 OR a.starred = 1) " +
            "ORDER BY a.pubDate DESC, a.id DESC LIMIT 1000",
    )
    fun observeSearch(match: String, feedId: Long?, starredOnly: Boolean): Flow<List<ArticleListItem>>

    @Query(
        "SELECT a.id, a.feedId, f.title AS feedTitle, a.title, a.summary, a.pubDate, a.read, a.starred, " +
            "(a.note IS NOT NULL AND a.note != '') AS hasNote, f.category AS category, a.readAt, " +
            "(a.enclosureUrl IS NOT NULL) AS hasAudio, a.durationSeconds, a.playPosition, a.playedAt, " +
            "(a.downloadPath IS NOT NULL) AS downloaded, (a.archivedAt > 0) AS archived " +
            "FROM articles a JOIN feeds f ON f.id = a.feedId WHERE a.id IN (:ids) " +
            "ORDER BY a.pubDate DESC, a.id DESC",
    )
    suspend fun listItems(ids: List<Long>): List<ArticleListItem>

    @Query(
        "SELECT a.id, a.feedId, f.title AS feedTitle, a.title, a.summary, a.pubDate, a.read, a.starred, " +
            "(a.note IS NOT NULL AND a.note != '') AS hasNote, f.category AS category, a.readAt, " +
            "(a.enclosureUrl IS NOT NULL) AS hasAudio, a.durationSeconds, a.playPosition, a.playedAt, " +
            "(a.downloadPath IS NOT NULL) AS downloaded, (a.archivedAt > 0) AS archived " +
            "FROM articles a JOIN feeds f ON f.id = a.feedId WHERE a.read = 0 " +
            "AND (:feedId IS NULL OR a.feedId = :feedId) AND (:folder IS NULL OR f.category = :folder) " +
            "ORDER BY a.pubDate DESC, a.id DESC LIMIT :limit",
    )
    suspend fun newestUnread(limit: Int, feedId: Long? = null, folder: String? = null): List<ArticleListItem>

    @Query(
        "SELECT COUNT(*) FROM articles a JOIN feeds f ON f.id = a.feedId WHERE a.read = 0 " +
            "AND (:feedId IS NULL OR a.feedId = :feedId) AND (:folder IS NULL OR f.category = :folder)",
    )
    suspend fun countUnread(feedId: Long? = null, folder: String? = null): Int

    @Query("SELECT COUNT(*) FROM articles WHERE read = 0")
    fun observeUnreadCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM articles WHERE starred = 1")
    fun observeStarredCount(): Flow<Int>

    @Query("UPDATE articles SET archivedAt = :archivedAt WHERE id IN (:ids)")
    suspend fun setArchived(ids: List<Long>, archivedAt: Long)

    /** Unlike [setRead], keeps the time already read articles were read. */
    @Query("UPDATE articles SET read = 1, readAt = :now WHERE id IN (:ids) AND read = 0")
    suspend fun markReadIfUnread(ids: List<Long>, now: Long)

    @Query("SELECT EXISTS(SELECT 1 FROM articles WHERE feedId = :feedId AND enclosureUrl IS NOT NULL)")
    suspend fun hasEpisodes(feedId: Long): Boolean

    @Query("SELECT title FROM articles WHERE feedId = :feedId ORDER BY pubDate DESC LIMIT :limit")
    suspend fun recentTitles(feedId: Long, limit: Int): List<String>

    @Query("SELECT id, downloadPath FROM articles WHERE downloadPath IS NOT NULL")
    suspend fun downloads(): List<DownloadRow>

    @Query("SELECT downloadPath FROM articles WHERE feedId = :feedId AND downloadPath IS NOT NULL")
    suspend fun downloadPaths(feedId: Long): List<String>

    @Query("SELECT * FROM articles WHERE id = :id")
    fun observe(id: Long): Flow<Article?>

    @Query("SELECT * FROM articles WHERE id = :id")
    suspend fun byId(id: Long): Article?

    @Query("SELECT * FROM articles WHERE feedId = :feedId AND guid = :guid")
    suspend fun byGuid(feedId: Long, guid: String): Article?

    /** Returns the new row ids; -1 for entries that already existed. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(articles: List<Article>): List<Long>

    /** [readAt] is the current time when marking read, 0 when marking unread. */
    @Query("UPDATE articles SET read = :read, readAt = :readAt WHERE id IN (:ids)")
    suspend fun setRead(ids: List<Long>, read: Boolean, readAt: Long)

    @Query(
        "UPDATE articles SET read = 1, readAt = :now WHERE read = 0 " +
            "AND (:feedId IS NULL OR feedId = :feedId) AND (:starredOnly = 0 OR starred = 1)",
    )
    suspend fun markAllRead(feedId: Long?, starredOnly: Boolean, now: Long)

    @Query("UPDATE articles SET starred = :starred, stateUpdatedAt = :now WHERE id = :id")
    suspend fun setStarred(id: Long, starred: Boolean, now: Long)

    @Query("UPDATE articles SET note = :note, stateUpdatedAt = :now WHERE id = :id")
    suspend fun setNote(id: Long, note: String?, now: Long)

    @Query("UPDATE articles SET starred = :starred, note = :note, stateUpdatedAt = :stateUpdatedAt WHERE id = :id")
    suspend fun applySyncedState(id: Long, starred: Boolean, note: String?, stateUpdatedAt: Long)

    @Query("UPDATE articles SET fullText = :html WHERE id = :id")
    suspend fun setFullText(id: Long, html: String?)

    @Query("SELECT guid FROM articles WHERE feedId = :feedId AND content IS NULL")
    suspend fun guidsWithoutContent(feedId: Long): List<String>

    @Query(
        "UPDATE articles SET content = :content, summary = :summary " +
            "WHERE feedId = :feedId AND guid = :guid AND content IS NULL",
    )
    suspend fun backfillContent(feedId: Long, guid: String, content: String?, summary: String)

    /** Entries stored without an attached media file: from before version 4, or added through sync. */
    @Query("SELECT guid FROM articles WHERE feedId = :feedId AND enclosureUrl IS NULL")
    suspend fun guidsWithoutEnclosure(feedId: Long): List<String>

    @Query(
        "UPDATE articles SET enclosureUrl = :url, enclosureType = :type, enclosureLength = :length, " +
            "durationSeconds = :durationSeconds, imageUrl = COALESCE(imageUrl, :imageUrl) " +
            "WHERE feedId = :feedId AND guid = :guid AND enclosureUrl IS NULL",
    )
    suspend fun backfillEnclosure(
        feedId: Long,
        guid: String,
        url: String,
        type: String?,
        length: Long?,
        durationSeconds: Long?,
        imageUrl: String?,
    )


    /** The player found out the length; only fills it in where the feed did not say. */
    @Query("UPDATE articles SET durationSeconds = :seconds WHERE id = :id AND durationSeconds IS NULL")
    suspend fun learnDuration(id: Long, seconds: Long)

    @Query(
        "SELECT a.id, a.title, f.title AS feedTitle, a.durationSeconds, a.playPosition, a.downloadPath, a.pubDate " +
            "FROM articles a JOIN feeds f ON f.id = a.feedId WHERE a.downloadPath IS NOT NULL ORDER BY a.pubDate DESC",
    )
    fun observeDownloaded(): Flow<List<DownloadedEpisode>>

    /** Only where none are known yet: chapters from the feed win over those found later. */
    @Query("UPDATE articles SET chapters = :chapters WHERE id = :id AND chapters IS NULL")
    suspend fun setChaptersIfMissing(id: Long, chapters: String)

    @Query("SELECT guid FROM articles WHERE feedId = :feedId AND chapters IS NULL AND chaptersUrl IS NULL")
    suspend fun guidsWithoutChapters(feedId: Long): List<String>

    @Query(
        "UPDATE articles SET chapters = COALESCE(chapters, :chapters), chaptersUrl = COALESCE(chaptersUrl, :chaptersUrl) " +
            "WHERE feedId = :feedId AND guid = :guid",
    )
    suspend fun backfillChapters(feedId: Long, guid: String, chapters: String?, chaptersUrl: String?)

    @Query("UPDATE articles SET playPosition = :positionMs WHERE id = :id")
    suspend fun setPlayPosition(id: Long, positionMs: Long)

    /** [playedAt] is the current time when finished, 0 to mark as not played. Resets the position. */
    @Query("UPDATE articles SET playedAt = :playedAt, playPosition = 0 WHERE id = :id")
    suspend fun setPlayed(id: Long, playedAt: Long)

    @Query("UPDATE articles SET downloadPath = :path WHERE id = :id")
    suspend fun setDownloadPath(id: Long, path: String?)

    /**
     * Removes old, read entries that the feed no longer lists (so they cannot come back as unread).
     * Downloaded episodes stay until their file is deleted.
     */
    @Query(
        "DELETE FROM articles WHERE feedId = :feedId AND read = 1 AND starred = 0 " +
            "AND (note IS NULL OR note = '') AND downloadPath IS NULL " +
            "AND pubDate < :cutoff AND guid NOT IN (:keepGuids)",
    )
    suspend fun deleteOld(feedId: Long, cutoff: Long, keepGuids: List<String>): Int

    @Query(
        "SELECT f.url AS feedUrl, a.guid, a.title, a.link, a.pubDate, a.starred, a.note, a.stateUpdatedAt " +
            "FROM articles a JOIN feeds f ON f.id = a.feedId WHERE a.stateUpdatedAt > 0",
    )
    suspend fun syncRows(): List<ArticleSyncRow>
}

@Database(
    entities = [
        Feed::class, Article::class, DeletedFeed::class, ArticleFts::class, QueueEntry::class, ListenEntry::class,
    ],
    version = 7,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun feedDao(): FeedDao
    abstract fun articleDao(): ArticleDao
    abstract fun queueDao(): QueueDao
    abstract fun listenDao(): ListenDao

    companion object {
        /** Adds the per-feed notification switch and the search index. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE feeds ADD COLUMN notify INTEGER NOT NULL DEFAULT 1")
                db.execSQL(
                    "CREATE VIRTUAL TABLE IF NOT EXISTS `articles_fts` USING FTS4(`title` TEXT NOT NULL, " +
                        "`summary` TEXT NOT NULL, `content` TEXT, `fullText` TEXT, `note` TEXT, " +
                        "tokenize=unicode61, content=`articles`)",
                )
                db.execSQL("INSERT INTO articles_fts(articles_fts) VALUES('rebuild')")
            }
        }

        /** Records when an article was read. Articles read before count as read long ago. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE articles ADD COLUMN readAt INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** Podcast support: attached media files, artwork, playback position and downloads. */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE feeds ADD COLUMN imageUrl TEXT")
                db.execSQL("ALTER TABLE articles ADD COLUMN enclosureUrl TEXT")
                db.execSQL("ALTER TABLE articles ADD COLUMN enclosureType TEXT")
                db.execSQL("ALTER TABLE articles ADD COLUMN enclosureLength INTEGER")
                db.execSQL("ALTER TABLE articles ADD COLUMN durationSeconds INTEGER")
                db.execSQL("ALTER TABLE articles ADD COLUMN imageUrl TEXT")
                db.execSQL("ALTER TABLE articles ADD COLUMN playPosition INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE articles ADD COLUMN playedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE articles ADD COLUMN downloadPath TEXT")
            }
        }

        /** Archive and automatic downloads. */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE articles ADD COLUMN archivedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE feeds ADD COLUMN autoDownload INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** Queue of episodes and the listening log for the statistics. */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `queue` (`articleId` INTEGER NOT NULL, `position` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`articleId`), FOREIGN KEY(`articleId`) REFERENCES `articles`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE )",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `listen_log` (`day` INTEGER NOT NULL, `articleId` INTEGER NOT NULL, " +
                        "`feedUrl` TEXT NOT NULL, `feedTitle` TEXT NOT NULL, `listenedMs` INTEGER NOT NULL, " +
                        "`contentMs` INTEGER NOT NULL, `finished` INTEGER NOT NULL, PRIMARY KEY(`day`, `articleId`))",
                )
            }
        }

        /** Chapters of episodes. */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE articles ADD COLUMN chapters TEXT")
                db.execSQL("ALTER TABLE articles ADD COLUMN chaptersUrl TEXT")
            }
        }

        val MIGRATIONS = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
    }
}

/** Whether a list still shows an article, given the "hide read articles" setting. */
object ReadVisibility {
    const val NEVER = -1

    /**
     * [hideAfterMinutes]: [NEVER] keeps read articles in the list, 0 drops them at once, otherwise
     * they stay for that long after being read.
     */
    fun isVisible(read: Boolean, readAt: Long, hideAfterMinutes: Int, now: Long): Boolean =
        !read || hideAfterMinutes == NEVER || (hideAfterMinutes > 0 && readAt > now - hideAfterMinutes * 60_000L)
}

/** Turns what the user typed into an FTS expression: every word must occur, as a word prefix. */
object SearchQuery {
    private val separators = Regex("[^\\p{L}\\p{N}]+")

    /** Returns null if the input contains nothing to search for. */
    fun toMatch(input: String): String? =
        input.lowercase().split(separators).filter { it.isNotEmpty() }
            .joinToString(" ") { "$it*" }.ifEmpty { null }
}
