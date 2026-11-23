package io.github.mohnstollen.news.playback

import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.Player

/**
 * The episode loaded in [PlaybackService], for what shows playback outside the app (the widget).
 * Published by the service; null while it does not run or has nothing to resume.
 */
data class NowPlaying(
    val articleId: Long,
    val title: String,
    val podcast: String,
    val artwork: String?,
    /** Playing, or about to once data has arrived. */
    val playing: Boolean,
    val positionMs: Long,
    /** 0 while unknown. */
    val durationMs: Long,
    val speed: Float,
    /** [SystemClock.elapsedRealtime] when [positionMs] was read. */
    val atElapsedMs: Long,
) {
    companion object {
        /** A finished episode counts as nothing loaded: "play" then starts the next one. */
        fun of(player: Player): NowPlaying? {
            val id = player.currentMediaItem?.mediaId?.toLongOrNull() ?: return null
            if (player.playbackState == Player.STATE_ENDED) return null
            val metadata = player.mediaMetadata
            return NowPlaying(
                articleId = id,
                title = metadata.title?.toString().orEmpty(),
                podcast = metadata.artist?.toString().orEmpty(),
                artwork = metadata.artworkUri?.toString(),
                playing = player.playWhenReady && player.playbackState != Player.STATE_IDLE,
                positionMs = player.currentPosition.coerceAtLeast(0),
                durationMs = player.duration.takeIf { it != C.TIME_UNSET }?.coerceAtLeast(0) ?: 0,
                speed = player.playbackParameters.speed,
                atElapsedMs = SystemClock.elapsedRealtime(),
            )
        }
    }
}
