package io.github.mohnstollen.news.playback

import android.app.PendingIntent
import android.content.Intent
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.extractor.metadata.id3.ChapterFrame
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import io.github.mohnstollen.news.data.Chapter
import io.github.mohnstollen.news.data.Chapters
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
import kotlinx.coroutines.withContext

/**
 * Plays podcast episodes in the background. The media session gives the system notification,
 * lock screen, headset buttons and Bluetooth controls; the app's screens connect through
 * [PlayerConnection]. One episode at a time; its media id is the article id.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private val main = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * Database writes, one after the other in the order they were made: a position saved just
     * before the end must not land after "played", which resets it. Not cancelled on destroy,
     * so the last writes still happen.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val writes = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private var saveJob: Job? = null
    private var sleepJob: Job? = null
    private var endBufferJob: Job? = null

    /** Listening time not yet written to the log: since when, and for which episode. */
    private var listeningSince = 0L
    private var listeningId: Long? = null

    private val repo get() = container.repository

    override fun onCreate() {
        super.onCreate()
        val dataSources = DefaultDataSource.Factory(this, OkHttpDataSource.Factory(container.mediaHttp))
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

        main.launch { container.sleepTimer.mode.collect(::onSleepTimer) }
    }

    private fun onSleepTimer(mode: SleepTimer.Mode?) {
        sleepJob?.cancel()
        val player = session?.player ?: return
        player.volume = 1f
        if (mode !is SleepTimer.Mode.Until) return
        sleepJob = main.launch {
            delay((mode.atElapsed - SystemClock.elapsedRealtime() - SleepTimer.FADE_MS).coerceAtLeast(0))
            // Fade out instead of stopping mid-sentence at full volume.
            val steps = 20
            for (i in 1..steps) {
                player.volume = 1f - i.toFloat() / steps
                delay(SleepTimer.FADE_MS / steps)
            }
            player.pause()
            player.volume = 1f
            container.sleepTimer.cancel()
        }
    }

    /** Loads an episode from the queue and plays it from where it was left. */
    private suspend fun playFromQueue(articleId: Long) {
        val (item, start) = Episodes.resolve(articleId.toString(), repo.articleDao, repo.feedDao, endBufferMs()) ?: return
        val player = session?.player ?: return
        player.setMediaItem(item, start)
        player.prepare()
        player.play()
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
        logListening()
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
            // The episode that is being replaced keeps its place and its listening time.
            savePosition()
            logListening()
            return main.future {
                val wanted = mediaItems.getOrNull(startIndex.coerceAtLeast(0))
                    ?: throw IllegalArgumentException("Keine Folge angegeben")
                val (item, savedPosition) = Episodes.resolve(wanted.mediaId, repo.articleDao, repo.feedDao, endBufferMs())
                    ?: throw IllegalArgumentException("Folge ${wanted.mediaId} hat keine Audiodatei")
                val start = if (startPositionMs == C.TIME_UNSET) savedPosition else startPositionMs
                // What is being listened to is in the queue, at the top if it was not yet there.
                // Not downloaded for that: it is streaming already.
                wanted.mediaId.toLongOrNull()?.let { repo.enqueueIfAbsent(it, next = true, handOn = false) }
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
            endBufferJob?.cancel()
            if (isPlaying) {
                endBufferJob = main.launch { watchEndBuffer() }
                listeningSince = SystemClock.elapsedRealtime()
                listeningId = currentArticleId()
                saveJob = main.launch {
                    while (isActive) {
                        delay(SAVE_INTERVAL_MS)
                        savePosition()
                        logListening()
                    }
                }
            } else {
                savePosition()
                logListening()
                listeningId = null
            }
        }

        override fun onTracksChanged(tracks: Tracks) {
            val id = currentArticleId() ?: return
            val chapters = id3Chapters(tracks).takeIf { it.isNotEmpty() } ?: return
            writes.launch { repo.articleDao.setChaptersIfMissing(id, Chapters.encode(chapters)) }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            currentArticleId()?.let(::loadLinkedChapters)
            // Time so far belongs to the previous episode.
            logListening()
            listeningId = if (session?.player?.isPlaying == true) currentArticleId() else null
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY) learnDuration()
            if (playbackState != Player.STATE_ENDED) return
            val id = currentArticleId() ?: return
            val downloads = container.downloads
            val timer = container.sleepTimer
            val stopHere = timer.mode.value == SleepTimer.Mode.EndOfEpisode
            if (stopHere) timer.cancel()
            writes.launch {
                repo.setPlayed(id, true)
                repo.logListening(id, 0, 0, finished = true)
                downloads.onPlayed(id)
                if (stopHere) return@launch
                // setPlayed took the finished episode out of the queue.
                val next = repo.nextInQueue(id) ?: return@launch
                withContext(Dispatchers.Main) { playFromQueue(next) }
            }
        }

        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
            val speed = playbackParameters.speed
            if (speed != container.settings.current.playbackSpeed) {
                container.settings.update { it.copy(playbackSpeed = speed) }
            }
        }
    }

    /** Podcasting 2.0: the feed links a JSON file with the chapters; loaded once, when played. */
    private fun loadLinkedChapters(articleId: Long) {
        val http = container.http
        writes.launch {
            val article = repo.articleDao.byId(articleId) ?: return@launch
            val url = article.chaptersUrl ?: return@launch
            if (article.chapters != null) return@launch
            val chapters = runCatching {
                http.newCall(okhttp3.Request.Builder().url(url).build()).execute().use { response ->
                    if (!response.isSuccessful) return@use emptyList()
                    Chapters.parseJson(response.body?.string().orEmpty())
                }
            }.getOrDefault(emptyList())
            if (chapters.isNotEmpty()) repo.articleDao.setChaptersIfMissing(articleId, Chapters.encode(chapters))
        }
    }

    /** ID3 chapter frames (CHAP) in an MP3 file, as the player found them. */
    private fun id3Chapters(tracks: Tracks): List<Chapter> {
        val found = mutableListOf<Chapter>()
        for (group in tracks.groups) {
            for (i in 0 until group.length) {
                val metadata = group.getTrackFormat(i).metadata ?: continue
                for (j in 0 until metadata.length()) {
                    val frame = metadata.get(j) as? ChapterFrame ?: continue
                    val title = (0 until frame.subFrameCount).map { frame.getSubFrame(it) }
                        .filterIsInstance<TextInformationFrame>().firstOrNull { it.id == "TIT2" }?.values?.firstOrNull()
                    found += Chapter(frame.startTimeMs.toLong(), title.orEmpty())
                }
            }
        }
        return Chapters.clean(found)
    }

    /** Many feeds do not state the length; the player knows it once the file is open. */
    private fun learnDuration() {
        val player = session?.player ?: return
        val id = currentArticleId() ?: return
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: return
        writes.launch { repo.articleDao.learnDuration(id, duration / 1000) }
    }

    private fun endBufferMs(): Long = container.settings.current.endBufferSeconds * 1000L

    /**
     * While playing: once only the end buffer is left, jump to the end. Reaching the end does the
     * rest as usual (played, download deleted, next in the queue).
     */
    private suspend fun watchEndBuffer() {
        while (true) {
            val player = session?.player ?: return
            val buffer = endBufferMs()
            if (buffer > 0 && player.duration != C.TIME_UNSET &&
                Episodes.inEndBuffer(player.currentPosition, player.duration, buffer)
            ) {
                player.seekTo(player.duration)
                return
            }
            delay(END_BUFFER_CHECK_MS)
        }
    }

    /** Writes the listening time since the last call to the statistics. */
    private fun logListening() {
        val id = listeningId ?: return
        val player = session?.player ?: return
        val now = SystemClock.elapsedRealtime()
        val listened = now - listeningSince
        listeningSince = now
        if (listened <= 0) return
        val content = (listened * player.playbackParameters.speed).toLong()
        writes.launch { repo.logListening(id, listened, content) }
    }

    private fun currentArticleId(): Long? = session?.player?.currentMediaItem?.mediaId?.toLongOrNull()

    /** Stores the position of the current episode. A finished episode is handled by [Player.STATE_ENDED]. */
    private fun savePosition() {
        val player = session?.player ?: return
        val id = currentArticleId() ?: return
        if (player.playbackState == Player.STATE_ENDED) return
        val position = player.currentPosition
        writes.launch { repo.savePlayPosition(id, position) }
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
        private const val END_BUFFER_CHECK_MS = 500L
    }
}
