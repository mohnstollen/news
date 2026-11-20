package io.github.mohnstollen.news.playback

import android.content.ComponentName
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** What the screens show about playback. */
data class PlayerState(
    /** Article of the loaded episode; null when nothing is loaded. */
    val articleId: Long? = null,
    val title: String = "",
    val subtitle: String = "",
    val playing: Boolean = false,
    /** Wants to play but is waiting for data. */
    val buffering: Boolean = false,
    val ended: Boolean = false,
    val positionMs: Long = 0,
    /** 0 while unknown. */
    val durationMs: Long = 0,
    val speed: Float = 1f,
    val error: String? = null,
) {
    fun isPlaying(articleId: Long) = this.articleId == articleId && (playing || buffering)
}

/**
 * The screens' handle on [PlaybackService]. Connect while the app is visible; commands given
 * before the connection is up are carried out once it is.
 */
class PlayerConnection(context: Context) {
    private val context = context.applicationContext
    private val main = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var future: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private val pending = mutableListOf<(MediaController) -> Unit>()
    private var ticker: Job? = null

    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = update()
    }

    fun connect() {
        if (future != null) return
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val f = MediaController.Builder(context, token).buildAsync()
        future = f
        f.addListener(
            {
                if (future !== f) return@addListener
                val c = runCatching { f.get() }.getOrNull() ?: return@addListener
                controller = c
                c.addListener(listener)
                pending.toList().forEach { it(c) }
                pending.clear()
                update()
                // The position moves without player events; poll it while connected.
                ticker = main.launch {
                    while (isActive) {
                        delay(500)
                        update()
                    }
                }
            },
            ContextCompat.getMainExecutor(context),
        )
    }

    fun release() {
        ticker?.cancel()
        ticker = null
        controller?.removeListener(listener)
        controller = null
        future?.let(MediaController::releaseFuture)
        future = null
        pending.clear()
    }

    private fun command(action: (MediaController) -> Unit) {
        val c = controller
        if (c != null) action(c) else {
            pending += action
            connect()
        }
    }

    /** Plays the episode of [articleId], from where it was left; resumes if it is already loaded. */
    fun play(articleId: Long) = command { c ->
        if (c.currentMediaItem?.mediaId == articleId.toString() && c.playbackState != Player.STATE_IDLE) {
            if (c.playbackState == Player.STATE_ENDED) c.seekTo(0)
            c.play()
        } else {
            // The service looks up address and saved position by the id.
            c.setMediaItem(MediaItem.Builder().setMediaId(articleId.toString()).build())
            c.prepare()
            c.play()
        }
    }

    fun toggle(articleId: Long) {
        if (_state.value.isPlaying(articleId)) pause() else play(articleId)
    }

    fun pause() = command { it.pause() }

    fun seekBack() = command { it.seekBack() }

    fun seekForward() = command { it.seekForward() }

    fun seekTo(positionMs: Long) = command { it.seekTo(positionMs) }

    fun setSpeed(speed: Float) = command { it.setPlaybackSpeed(speed) }

    /** Stops and unloads the episode; its position is kept. */
    fun stop() = command {
        it.stop()
        it.clearMediaItems()
    }

    private fun update() {
        val c = controller ?: return
        val item = c.currentMediaItem
        val metadata = c.mediaMetadata
        _state.value = PlayerState(
            articleId = item?.mediaId?.toLongOrNull(),
            title = metadata.title?.toString().orEmpty(),
            subtitle = metadata.artist?.toString().orEmpty(),
            playing = c.isPlaying,
            buffering = c.playWhenReady && c.playbackState == Player.STATE_BUFFERING,
            ended = c.playbackState == Player.STATE_ENDED,
            positionMs = c.currentPosition.coerceAtLeast(0),
            durationMs = c.duration.takeIf { it != C.TIME_UNSET }?.coerceAtLeast(0) ?: 0,
            speed = c.playbackParameters.speed,
            error = c.playerError?.let(::describe),
        )
    }

    companion object {
        val SPEEDS = listOf(0.8f, 1f, 1.2f, 1.5f, 1.8f, 2f)

        fun describe(e: PlaybackException): String = when (e.errorCode) {
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            -> "Keine Verbindung zum Server der Folge"
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
            -> "Die Audiodatei ist nicht (mehr) abrufbar"
            PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED -> "Unverschlüsselte Verbindung nicht erlaubt"
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            -> "Dieses Audioformat kann nicht abgespielt werden"
            else -> "Wiedergabe fehlgeschlagen (${e.errorCodeName})"
        }
    }
}
