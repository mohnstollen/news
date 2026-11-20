package io.github.mohnstollen.news.playback

import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.ListenableFuture
import io.github.mohnstollen.news.ui.MainActivity
import io.github.mohnstollen.news.ui.container
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Plays podcast episodes in the background. The media session gives the system notification,
 * lock screen, headset buttons and Bluetooth controls; the app's screens connect through
 * [PlayerConnection]. One episode at a time; its media id is the article id.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private val main = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var saveJob: Job? = null

    private val repo get() = container.repository

    override fun onCreate() {
        super.onCreate()
        // Streams run for an hour or more: no limit on the whole call, unlike feed downloads.
        val http = container.http.newBuilder().callTimeout(0, java.util.concurrent.TimeUnit.SECONDS).build()
        val dataSources = DefaultDataSource.Factory(this, OkHttpDataSource.Factory(http))
        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(dataSources).setLoadErrorHandlingPolicy(NoRetryOnClientError()),
            )
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setSeekBackIncrementMs(SEEK_BACK_MS)
            .setSeekForwardIncrementMs(SEEK_FORWARD_MS)
            .build()
        player.playbackParameters = PlaybackParameters(container.settings.current.playbackSpeed)
        player.addListener(listener)

        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, player)
            .setCallback(callback)
            .setSessionActivity(openApp)
            // Notification and lock screen: back 10 s, play/pause, forward 30 s.
            .setMediaButtonPreferences(
                listOf(
                    CommandButton.Builder(CommandButton.ICON_SKIP_BACK_10)
                        .setPlayerCommand(Player.COMMAND_SEEK_BACK)
                        .setDisplayName("10 Sekunden zurück")
                        .setSlots(CommandButton.SLOT_BACK)
                        .build(),
                    CommandButton.Builder(CommandButton.ICON_SKIP_FORWARD_30)
                        .setPlayerCommand(Player.COMMAND_SEEK_FORWARD)
                        .setDisplayName("30 Sekunden vor")
                        .setSlots(CommandButton.SLOT_FORWARD)
                        .build(),
                ),
            )
            .build()
            .also(::addSession)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    /** Swiping the app away stops the service, unless something is playing. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            savePosition()
            stopSelf()
        }
    }

    override fun onDestroy() {
        savePosition()
        session?.run {
            player.release()
            release()
        }
        session = null
        main.cancel()
        super.onDestroy()
    }

    private val callback = object : MediaSession.Callback {
        /** Controllers only send the article id; the stored episode supplies address and position. */
        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            // The episode that is being replaced keeps its place.
            savePosition()
            return main.future {
                val wanted = mediaItems.getOrNull(startIndex.coerceAtLeast(0))
                    ?: throw IllegalArgumentException("Keine Folge angegeben")
                val (item, savedPosition) = Episodes.resolve(wanted.mediaId, repo.articleDao, repo.feedDao)
                    ?: throw IllegalArgumentException("Folge ${wanted.mediaId} hat keine Audiodatei")
                val start = if (startPositionMs == C.TIME_UNSET) savedPosition else startPositionMs
                MediaSession.MediaItemsWithStartPosition(listOf(item), 0, start)
            }
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> = main.future {
            mediaItems.mapNotNull { Episodes.resolve(it.mediaId, repo.articleDao, repo.feedDao)?.first }
                .toMutableList()
        }
    }

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            saveJob?.cancel()
            if (isPlaying) {
                saveJob = main.launch {
                    while (isActive) {
                        delay(SAVE_INTERVAL_MS)
                        savePosition()
                    }
                }
            } else {
                savePosition()
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState != Player.STATE_ENDED) return
            val id = currentArticleId() ?: return
            repo.scope.launch { repo.setPlayed(id, true) }
        }

        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
            val speed = playbackParameters.speed
            if (speed != container.settings.current.playbackSpeed) {
                container.settings.update { it.copy(playbackSpeed = speed) }
            }
        }
    }

    private fun currentArticleId(): Long? = session?.player?.currentMediaItem?.mediaId?.toLongOrNull()

    /** Stores the position of the current episode. A finished episode is handled by [Player.STATE_ENDED]. */
    private fun savePosition() {
        val player = session?.player ?: return
        val id = currentArticleId() ?: return
        if (player.playbackState == Player.STATE_ENDED) return
        val position = player.currentPosition
        repo.scope.launch { repo.savePlayPosition(id, position) }
    }

    /** A missing or forbidden file does not come back by asking again; network trouble may pass. */
    private class NoRetryOnClientError : DefaultLoadErrorHandlingPolicy() {
        override fun getRetryDelayMsFor(info: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
            val e = info.exception
            if (e is HttpDataSource.InvalidResponseCodeException && e.responseCode in 400..499) return C.TIME_UNSET
            return super.getRetryDelayMsFor(info)
        }
    }

    companion object {
        const val SEEK_BACK_MS = 10_000L
        const val SEEK_FORWARD_MS = 30_000L
        /** While playing, the position is stored this often, so little is lost if the app is killed. */
        private const val SAVE_INTERVAL_MS = 10_000L
    }
}
