package io.github.mohnstollen.news

import android.content.Context
import android.net.Uri
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaController
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.mohnstollen.news.data.Article
import io.github.mohnstollen.news.data.Feed
import io.github.mohnstollen.news.playback.PlaybackService
import io.github.mohnstollen.news.ui.container
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The service with a real player and media session; a controller talks to it like the screens do. */
@RunWith(AndroidJUnit4::class)
@Config(application = UiTestApp::class, sdk = [34])
class PlaybackServiceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val repo get() = context.container.repository

    /** Lets the main looper and the database threads work until [done] holds. */
    private fun waitFor(what: String, done: () -> Boolean) {
        repeat(500) {
            shadowOf(Looper.getMainLooper()).idle()
            if (done()) return
            Thread.sleep(10)
        }
        throw AssertionError("timed out waiting for $what")
    }

    @Test
    fun episodeIsLoadedByIdAtItsSavedPositionAndThePositionIsKept() {
        val id = runBlocking {
            val feedId = repo.feedDao.insert(Feed(url = "https://example.org/feed", title = "Der Podcast"))
            repo.articleDao.insertAll(
                listOf(
                    Article(
                        feedId = feedId, guid = "ep", title = "Folge 1", pubDate = 0, fetchedAt = 0,
                        enclosureUrl = "https://cdn.example.org/ep1.mp3", enclosureType = "audio/mpeg",
                        durationSeconds = 3600, playPosition = 754_000,
                    ),
                ),
            ).single()
        }

        val service = Robolectric.buildService(PlaybackService::class.java).create().get()
        val session = service.sessions.single()
        val player = session.player
        assertEquals("speed from the settings", 1f, player.playbackParameters.speed)

        val future = MediaController.Builder(context, session.token).buildAsync()
        waitFor("controller") { future.isDone }
        val controller = future.get()

        // The screens only send the article id.
        controller.setMediaItem(MediaItem.Builder().setMediaId(id.toString()).build())
        waitFor("episode") { player.currentMediaItem != null }
        val item = player.currentMediaItem!!
        assertEquals(Uri.parse("https://cdn.example.org/ep1.mp3"), item.localConfiguration!!.uri)
        assertEquals("Folge 1", item.mediaMetadata.title)
        assertEquals("Der Podcast", item.mediaMetadata.artist)
        assertEquals("resumes where it stopped", 754_000L, player.currentPosition)

        // Seek steps of the notification buttons.
        assertEquals(PlaybackService.SEEK_BACK_MS, player.seekBackIncrement)
        assertEquals(PlaybackService.SEEK_FORWARD_MS, player.seekForwardIncrement)

        controller.setPlaybackSpeed(1.5f)
        waitFor("speed") { context.container.settings.current.playbackSpeed == 1.5f }

        player.seekTo(1_000_000)
        controller.release()
        service.onDestroy()
        waitFor("saved position") { runBlocking { repo.articleDao.byId(id)!!.playPosition } == 1_000_000L }
    }
}
