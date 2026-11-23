package io.github.mohnstollen.news.playback

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Playback steered from outside the app's screens (widget, notification): through a media
 * controller, as the lock screen does. Call on the main thread.
 */
object RemotePlayback {
    /** How long a start may take before the controller lets go anyway. */
    const val START_TIMEOUT_MS = 5_000L

    suspend fun connect(context: Context): MediaController {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        return MediaController.Builder(context, token).buildAsync().await()
    }

    /** Plays [articleId] from where it was left; the service puts it at the top of the queue. */
    suspend fun play(context: Context, articleId: Long) {
        val controller = connect(context)
        try {
            controller.setMediaItem(MediaItem.Builder().setMediaId(articleId.toString()).build())
            controller.prepare()
            controller.play()
            awaitStarted(controller)
        } finally {
            controller.release()
        }
    }

    /**
     * The service may run only for this controller; letting go of it before playback is under
     * way (and so in the foreground) would end the service again.
     */
    suspend fun awaitStarted(controller: MediaController) {
        withTimeoutOrNull(START_TIMEOUT_MS) { awaitUntil(controller) { it.isPlaying || it.playerError != null } }
    }

    /** Returns once [condition] holds for [controller]. */
    suspend fun awaitUntil(controller: MediaController, condition: (Player) -> Boolean) =
        suspendCancellableCoroutine { done ->
            val listener = object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) {
                    if (condition(player) && done.isActive) {
                        player.removeListener(this)
                        done.resume(Unit)
                    }
                }
            }
            done.invokeOnCancellation { controller.removeListener(listener) }
            if (condition(controller)) done.resume(Unit) else controller.addListener(listener)
        }
}
