package io.github.mohnstollen.news.playback

import android.net.Uri
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaConstants
import io.github.mohnstollen.news.data.ArticleListItem
import io.github.mohnstollen.news.data.Feed
import io.github.mohnstollen.news.data.QueueItem
import io.github.mohnstollen.news.data.Repository
import kotlinx.coroutines.flow.first

/**
 * What Android Auto (and other media browsers) can browse: the queue, the inbox ("Neu") and the
 * podcasts with their episodes. Episodes have the article id as media id, as everywhere in
 * playback; folders have ids of their own.
 */
@OptIn(UnstableApi::class)
class Library(private val repo: Repository, private val covers: (CoverProvider.Cover) -> Uri?) {

    fun root(): MediaItem = folder(ROOT, "Podfang", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)

    /** The children of [parentId]; null if there is no such folder. */
    suspend fun children(parentId: String): List<MediaItem>? = when {
        parentId == ROOT -> listOf(
            folder(QUEUE, "Warteschlange", MediaMetadata.MEDIA_TYPE_FOLDER_PODCASTS),
            folder(NEW, "Neu", MediaMetadata.MEDIA_TYPE_FOLDER_PODCASTS),
            // The podcasts as a grid of covers.
            folder(PODCASTS, "Podcasts", MediaMetadata.MEDIA_TYPE_FOLDER_PODCASTS, grid = true),
        )
        parentId == QUEUE -> repo.queueDao.observe().first().map(::episode)
        parentId == NEW -> repo.articleDao.inbox(LIMIT).map(::episode)
        parentId == PODCASTS -> repo.feedDao.all().filter { repo.articleDao.hasEpisodes(it.id) }.map(::podcast)
        parentId.startsWith(PODCAST) -> parentId.removePrefix(PODCAST).toLongOrNull()
            ?.let { repo.articleDao.episodes(it, LIMIT) }?.map(::episode)
        else -> null
    }

    /** A single item by its id: a folder, a podcast or an episode. */
    suspend fun item(id: String): MediaItem? {
        if (id == ROOT) return root()
        children(ROOT)!!.firstOrNull { it.mediaId == id }?.let { return it }
        if (id.startsWith(PODCAST)) {
            return id.removePrefix(PODCAST).toLongOrNull()?.let { repo.feedDao.byId(it) }?.let(::podcast)
        }
        val articleId = id.toLongOrNull() ?: return null
        return repo.articleDao.listItems(listOf(articleId)).firstOrNull()?.let(::episode)
    }

    /**
     * What a spoken request ("Spiele Lage der Nation auf Podfang") means: the newest unplayed
     * episode of a podcast with that name, else an episode with it in its title. Without words,
     * or when nothing fits: the first of the queue, else the newest of the inbox.
     */
    suspend fun forVoice(query: String?): Long? {
        val words = query?.trim().orEmpty()
        if (words.isNotEmpty()) {
            val feed = matchingFeed(words, repo.feedDao.all())
            if (feed != null) {
                val episodes = repo.articleDao.episodes(feed.id, LIMIT)
                (episodes.firstOrNull { it.playedAt == 0L && !it.archived } ?: episodes.firstOrNull())?.let { return it.id }
            }
            repo.articleDao.episodesTitled(words, 1).firstOrNull()?.let { return it.id }
        }
        return repo.queueDao.ids().firstOrNull() ?: repo.articleDao.inbox(1).firstOrNull()?.id
    }

    private fun folder(id: String, title: String, type: Int, grid: Boolean = false): MediaItem {
        val extras = Bundle().apply {
            if (grid) putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM)
        }
        return MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setMediaType(type)
                    .setExtras(extras)
                    .build(),
            )
            .build()
    }

    private fun podcast(feed: Feed): MediaItem = MediaItem.Builder()
        .setMediaId(PODCAST + feed.id)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(feed.title)
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_PODCAST)
                .setArtworkUri(feed.imageUrl?.let { covers(CoverProvider.Cover.Podcast(feed.id)) })
                .build(),
        )
        .build()

    private fun episode(item: QueueItem) = episode(
        id = item.id, title = item.title, podcast = item.feedTitle, durationSeconds = item.durationSeconds,
        positionMs = item.playPosition, played = false, hasArtwork = (item.imageUrl ?: item.feedImageUrl) != null,
    )

    private fun episode(item: ArticleListItem) = episode(
        id = item.id, title = item.title, podcast = item.feedTitle, durationSeconds = item.durationSeconds,
        positionMs = item.playPosition, played = item.playedAt > 0, hasArtwork = (item.imageUrl ?: item.feedImageUrl) != null,
    )

    private fun episode(
        id: Long,
        title: String,
        podcast: String,
        durationSeconds: Long?,
        positionMs: Long,
        played: Boolean,
        hasArtwork: Boolean,
    ): MediaItem {
        val length = Episodes.label(durationSeconds, positionMs, 0)
        // The car shows how far an episode was heard.
        val extras = Bundle().apply {
            val status = when {
                played -> MediaConstants.EXTRAS_VALUE_COMPLETION_STATUS_FULLY_PLAYED
                positionMs > 0 -> MediaConstants.EXTRAS_VALUE_COMPLETION_STATUS_PARTIALLY_PLAYED
                else -> MediaConstants.EXTRAS_VALUE_COMPLETION_STATUS_NOT_PLAYED
            }
            putInt(MediaConstants.EXTRAS_KEY_COMPLETION_STATUS, status)
            val total = durationSeconds?.takeIf { it > 0 }?.times(1000)
            if (status == MediaConstants.EXTRAS_VALUE_COMPLETION_STATUS_PARTIALLY_PLAYED && total != null) {
                putDouble(MediaConstants.EXTRAS_KEY_COMPLETION_PERCENTAGE, (positionMs.toDouble() / total).coerceIn(0.0, 1.0))
            }
        }
        return MediaItem.Builder()
            .setMediaId(id.toString())
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(podcast)
                    .setSubtitle(listOfNotNull(podcast, length).joinToString(" · "))
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_PODCAST_EPISODE)
                    .setArtworkUri(if (hasArtwork) covers(CoverProvider.Cover.Episode(id)) else null)
                    .setExtras(extras)
                    .build(),
            )
            .build()
    }

    companion object {
        const val ROOT = "root"
        const val QUEUE = "queue"
        const val NEW = "new"
        const val PODCASTS = "podcasts"
        const val PODCAST = "podcast/"

        /** Lists in the car stay short; drivers do not scroll through hundreds. */
        const val LIMIT = 100

        /** Options for the whole library: lists, except where a folder asks for a grid. */
        val rootExtras: Bundle
            get() = Bundle().apply {
                // Older Android Auto versions only use the styles below when told so.
                putBoolean("android.media.browse.CONTENT_STYLE_SUPPORTED", true)
                putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM)
                putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM)
            }

        /** The podcast meant by spoken [words]: its name in them, or they in its name. */
        fun matchingFeed(words: String, feeds: List<Feed>): Feed? {
            fun norm(s: String) = s.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
            val wanted = norm(words)
            if (wanted.isEmpty()) return null
            return feeds.firstOrNull { norm(it.title) == wanted }
                ?: feeds.filter { norm(it.title).let { t -> t.isNotEmpty() && (wanted in t || t in wanted) } }
                    .minByOrNull { (norm(it.title).length - wanted.length).let(Math::abs) }
        }
    }
}
