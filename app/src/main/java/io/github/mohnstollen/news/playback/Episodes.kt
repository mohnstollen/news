package io.github.mohnstollen.news.playback

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import io.github.mohnstollen.news.data.Article
import io.github.mohnstollen.news.data.ArticleDao
import io.github.mohnstollen.news.data.Feed
import io.github.mohnstollen.news.data.FeedDao
import java.io.File
import java.util.Locale

/** Turns stored episodes into something the player can play, and describes their progress. */
object Episodes {
    /** Closer to the end than this, an episode starts over instead of resuming. */
    private const val RESUME_END_MARGIN_MS = 10_000L

    /** Shorter episodes play to the end whatever the buffer, so a buffer cannot swallow them. */
    private const val MIN_LENGTH_FOR_BUFFER_MS = 60_000L

    /** Choices for the end buffer in seconds; 0 is off. */
    val END_BUFFER_CHOICES = listOf(0, 10, 30, 60, 120)

    /** The player's item for [article]; null if it has no media file. The media id is the article id. */
    fun mediaItem(article: Article, feed: Feed?): MediaItem? {
        val local = article.downloadPath?.let(::File)?.takeIf { it.isFile }
        val uri = local?.let(Uri::fromFile) ?: article.enclosureUrl?.let(Uri::parse) ?: return null
        val artwork = article.imageUrl ?: feed?.imageUrl
        return MediaItem.Builder()
            .setMediaId(article.id.toString())
            .setUri(uri)
            .setMimeType(article.enclosureType)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(article.title)
                    .setArtist(feed?.title ?: article.author)
                    .setAlbumTitle(feed?.title)
                    .setArtworkUri(artwork?.let(Uri::parse))
                    .setMediaType(MediaMetadata.MEDIA_TYPE_PODCAST_EPISODE)
                    .setIsPlayable(true)
                    .setIsBrowsable(false)
                    .build(),
            )
            .build()
    }

    /**
     * Where to start: the saved position, unless the episode was finished or nearly so; with an
     * end buffer ([endBufferMs]), a position in the outro starts over too.
     */
    fun startPosition(article: Article, endBufferMs: Long = 0): Long {
        val position = article.playPosition
        if (position <= 0 || article.playedAt > 0) return 0
        val durationMs = article.durationSeconds?.times(1000) ?: return position
        return if (position < durationMs - maxOf(RESUME_END_MARGIN_MS, endBufferMs)) position else 0
    }

    /**
     * Whether playback reached the end buffer, so the rest (usually the outro) can be skipped and
     * the episode counts as played. Only for episodes clearly longer than the buffer.
     */
    fun inEndBuffer(positionMs: Long, durationMs: Long, endBufferMs: Long): Boolean =
        endBufferMs > 0 && durationMs > endBufferMs + MIN_LENGTH_FOR_BUFFER_MS &&
            positionMs >= durationMs - endBufferMs && positionMs < durationMs

    /** Loads the episode for a media id sent by a controller; null if there is none. */
    suspend fun resolve(
        mediaId: String,
        articles: ArticleDao,
        feeds: FeedDao,
        endBufferMs: Long = 0,
    ): Pair<MediaItem, Long>? {
        val article = mediaId.toLongOrNull()?.let { articles.byId(it) } ?: return null
        val item = mediaItem(article, feeds.byId(article.feedId)) ?: return null
        return item to startPosition(article, endBufferMs)
    }

    /** Short status for lists: "45 Min", "noch 12 Min", "Gehört"; null if nothing is known. */
    fun label(durationSeconds: Long?, playPositionMs: Long, playedAt: Long): String? {
        if (playedAt > 0) return "Gehört"
        val total = durationSeconds?.takeIf { it > 0 }
        if (playPositionMs > 0) {
            if (total == null) return "Begonnen"
            val left = total - playPositionMs / 1000
            if (left > 0) return "noch " + minutes(left)
        }
        return total?.let(::minutes)
    }

    /** "45 Min", "1 Std 5 Min"; at least one minute. */
    fun minutes(seconds: Long): String {
        val total = ((seconds + 59) / 60).coerceAtLeast(1)
        val hours = total / 60
        val mins = total % 60
        return when {
            hours == 0L -> "$mins Min"
            mins == 0L -> "$hours Std"
            else -> "$hours Std $mins Min"
        }
    }

    /** Clock-style time for the player: "4:05", "1:02:03". */
    fun clock(ms: Long): String {
        val s = (ms.coerceAtLeast(0) / 1000)
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, sec)
        else String.format(Locale.ROOT, "%d:%02d", m, sec)
    }
}
