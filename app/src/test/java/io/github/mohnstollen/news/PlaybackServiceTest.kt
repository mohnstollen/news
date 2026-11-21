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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
        repeat(1500) {
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

    @Test
    fun sleepTimerFadesOutAndPauses() {
        val service = Robolectric.buildService(PlaybackService::class.java).create().get()
        val player = service.sessions.single().player
        player.play()
        val timer = context.container.sleepTimer
        timer.start(5)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMinutes(5).minusSeconds(5))
        assertTrue("fading", player.volume < 1f && player.volume > 0f)
        assertTrue(player.playWhenReady)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(10))
        assertEquals(false, player.playWhenReady)
        assertEquals("volume back for next time", 1f, player.volume)
        assertNull(timer.mode.value)

        // Cancelling restores the volume at once.
        player.play()
        timer.start(1)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(55))
        timer.cancel()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1f, player.volume)
        assertTrue(player.playWhenReady)
        service.onDestroy()
    }

    /** Half a second of silence as a WAV file, which the player can play without a decoder. */
    private fun silence(name: String): java.io.File {
        val rate = 8000
        val samples = rate / 2
        val data = ByteArray(samples * 2)
        val header = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + data.size); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1); putInt(rate); putInt(rate * 2)
            putShort(2); putShort(16)
            put("data".toByteArray()); putInt(data.size)
        }.array()
        return java.io.File(context.filesDir, name).apply { writeBytes(header + data) }
    }

    @Test
    fun theNextQueuedEpisodeFollowsAndListeningIsCounted() {
        val (first, second) = runBlocking {
            val feedId = repo.feedDao.insert(Feed(url = "https://example.org/feed", title = "Der Podcast"))
            fun episode(guid: String) = Article(
                feedId = feedId, guid = guid, title = "Folge $guid", pubDate = 0, fetchedAt = 0,
                enclosureUrl = "https://cdn.example.org/$guid.wav", enclosureType = "audio/wav",
                downloadPath = silence("$guid.wav").path,
            )
            val ids = repo.articleDao.insertAll(listOf(episode("a"), episode("b")))
            repo.enqueue(ids[1])
            ids[0] to ids[1]
        }
        context.container.settings.update { it.copy(deletePlayedDownloads = false) }
        val service = Robolectric.buildService(PlaybackService::class.java).create().get()
        val session = service.sessions.single()
        val player = session.player
        val future = MediaController.Builder(context, session.token).buildAsync()
        waitFor("controller") { future.isDone }
        val controller = future.get()

        controller.setMediaItem(MediaItem.Builder().setMediaId(first.toString()).build())
        controller.prepare()
        controller.play()
        waitFor("second episode from the queue") {
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(200))
            player.currentMediaItem?.mediaId == second.toString()
        }
        runBlocking {
            assertTrue("first marked played", repo.articleDao.byId(first)!!.playedAt > 0)
            assertTrue("queue used up", repo.queueDao.ids().isEmpty())
        }
        waitFor("finished counted") {
            runBlocking { repo.listenDao.observeTotals(0).first().finished } >= 1
        }

        // "End of episode": the second one stops instead of going on.
        runBlocking { repo.enqueue(first) }
        context.container.sleepTimer.endOfEpisode()
        waitFor("stopped after the second") {
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(200))
            player.playbackState == androidx.media3.common.Player.STATE_ENDED &&
                runBlocking { repo.articleDao.byId(second)!!.playedAt } > 0
        }
        assertEquals(second.toString(), player.currentMediaItem?.mediaId)
        assertNull(context.container.sleepTimer.mode.value)
        runBlocking { assertEquals("still queued", listOf(first), repo.queueDao.ids()) }
        controller.release()
        service.onDestroy()
    }
}
