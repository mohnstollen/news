package io.github.mohnstollen.news.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
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
    val starred: Boolean = false,
    val note: String? = null,
    /** Last time star or note changed; 0 if never. Used for sync. */
    val stateUpdatedAt: Long = 0,
)

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
)

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

    @Query("UPDATE feeds SET etag = NULL, lastModified = NULL WHERE id = :id")
    suspend fun clearCacheHeaders(id: Long)

    @Query(
        "UPDATE feeds SET lastFetched = :now, lastError = NULL, etag = :etag, lastModified = :lastModified, " +
            "siteUrl = COALESCE(:siteUrl, siteUrl) WHERE id = :id",
    )
    suspend fun fetchSucceeded(id: Long, now: Long, etag: String?, lastModified: String?, siteUrl: String?)

    @Query("UPDATE feeds SET lastFetched = :now, lastError = :error WHERE id = :id")
    suspend fun fetchFailed(id: Long, now: Long, error: String)

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
            "(a.note IS NOT NULL AND a.note != '') AS hasNote " +
            "FROM articles a JOIN feeds f ON f.id = a.feedId " +
            "WHERE (:feedId IS NULL OR a.feedId = :feedId) AND (:starredOnly = 0 OR a.starred = 1) " +
            "ORDER BY a.pubDate DESC, a.id DESC LIMIT 5000",
    )
    fun observeList(feedId: Long?, starredOnly: Boolean): Flow<List<ArticleListItem>>

    @Query("SELECT COUNT(*) FROM articles WHERE read = 0")
    fun observeUnreadCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM articles WHERE starred = 1")
    fun observeStarredCount(): Flow<Int>

    @Query("SELECT * FROM articles WHERE id = :id")
    fun observe(id: Long): Flow<Article?>

    @Query("SELECT * FROM articles WHERE id = :id")
    suspend fun byId(id: Long): Article?

    @Query("SELECT * FROM articles WHERE feedId = :feedId AND guid = :guid")
    suspend fun byGuid(feedId: Long, guid: String): Article?

    /** Returns the new row ids; -1 for entries that already existed. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(articles: List<Article>): List<Long>

    @Query("UPDATE articles SET read = :read WHERE id IN (:ids)")
    suspend fun setRead(ids: List<Long>, read: Boolean)

    @Query(
        "UPDATE articles SET read = 1 WHERE read = 0 " +
            "AND (:feedId IS NULL OR feedId = :feedId) AND (:starredOnly = 0 OR starred = 1)",
    )
    suspend fun markAllRead(feedId: Long?, starredOnly: Boolean)

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

    /** Removes old, read entries that the feed no longer lists (so they cannot come back as unread). */
    @Query(
        "DELETE FROM articles WHERE feedId = :feedId AND read = 1 AND starred = 0 " +
            "AND (note IS NULL OR note = '') AND pubDate < :cutoff AND guid NOT IN (:keepGuids)",
    )
    suspend fun deleteOld(feedId: Long, cutoff: Long, keepGuids: List<String>): Int

    @Query(
        "SELECT f.url AS feedUrl, a.guid, a.title, a.link, a.pubDate, a.starred, a.note, a.stateUpdatedAt " +
            "FROM articles a JOIN feeds f ON f.id = a.feedId WHERE a.stateUpdatedAt > 0",
    )
    suspend fun syncRows(): List<ArticleSyncRow>
}

@Database(entities = [Feed::class, Article::class, DeletedFeed::class], version = 1, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun feedDao(): FeedDao
    abstract fun articleDao(): ArticleDao
}
