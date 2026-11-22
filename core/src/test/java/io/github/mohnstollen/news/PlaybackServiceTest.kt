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

    private val services = mutableListOf<PlaybackService>()

    /** Started services are always stopped afterwards: a media session left open blocks the next test's. */
    private fun startService(): PlaybackService =
        Robolectric.buildService(PlaybackService::class.java).create().get().also { services += it }

    @org.junit.After
    fun stopServices() {
        services.forEach { it.onDestroy() }
    }

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

        val service = startService()
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
        // Stopping the service stores where playback was.
        services.remove(service)
        service.onDestroy()
        waitFor("saved position") { runBlocking { repo.articleDao.byId(id)!!.playPosition } == 1_000_000L }
    }

    @Test
    fun sleepTimerFadesOutAndPauses() {
        val service = startService()
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
    }

    /** Silence as a WAV file (half a second by default), which the player can play without a decoder. */
    private fun silence(name: String, seconds: Double = 0.5): java.io.File {
        val rate = 4000
        val samples = (rate * seconds).toInt()
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
        val service = startService()
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
            assertEquals("the finished one left, the playing one stays until it ends", listOf(second), repo.queueDao.ids())
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
    }

    @Test
    fun theEndBufferSkipsTheOutroDeletesTheDownloadAndGoesOn() {
        val (first, second, file) = runBlocking {
            val feedId = repo.feedDao.insert(Feed(url = "https://example.org/feed", title = "Der Podcast"))
            val long = silence("long.wav", seconds = 100.0)
            fun episode(guid: String, path: String) = Article(
                feedId = feedId, guid = guid, title = "Folge $guid", pubDate = 0, fetchedAt = 0,
                enclosureUrl = "https://cdn.example.org/$guid.wav", enclosureType = "audio/wav", downloadPath = path,
            )
            val ids = repo.articleDao.insertAll(listOf(episode("a", long.path), episode("b", silence("b.wav").path)))
            repo.enqueue(ids[1])
            Triple(ids[0], ids[1], long)
        }
        context.container.settings.update { it.copy(endBufferSeconds = 30, deletePlayedDownloads = true) }
        val service = startService()
        val session = service.sessions.single()
        val player = session.player
        val future = MediaController.Builder(context, session.token).buildAsync()
        waitFor("controller") { future.isDone }
        val controller = future.get()

        controller.setMediaItem(MediaItem.Builder().setMediaId(first.toString()).build())
        controller.prepare()
        waitFor("prepared") { player.duration > 0 }
        assertEquals(100_000L, player.duration)
        // The feed did not say how long it is; the player found out.
        waitFor("length learned") { runBlocking { repo.articleDao.byId(first)!!.durationSeconds } == 100L }

        // Far from the end: playing a moment changes nothing. (Robolectric plays faster than real
        // time, so this keeps a wide margin; Episodes.inEndBuffer has the exact limits.)
        controller.seekTo(10_000)
        controller.play()
        repeat(2) { shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(100)) }
        controller.pause()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(first.toString(), player.currentMediaItem?.mediaId)
        assertEquals(0L, runBlocking { repo.articleDao.byId(first)!!.playedAt })

        // 25 seconds left: inside the 30-second buffer.
        controller.seekTo(75_000)
        controller.play()
        waitFor("next episode") {
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(100))
            player.currentMediaItem?.mediaId == second.toString()
        }
        runBlocking {
            val a = repo.articleDao.byId(first)!!
            assertTrue("counts as played", a.playedAt > 0)
            assertEquals(0L, a.playPosition)
        }
        waitFor("download deleted") { !file.exists() && runBlocking { repo.articleDao.byId(first)!!.downloadPath } == null }
        controller.release()
    }

    @Test
    fun linkedChapterFilesAreLoadedWhenTheEpisodeIsPlayed() {
        val server = okhttp3.mockwebserver.MockWebServer()
        server.enqueue(
            okhttp3.mockwebserver.MockResponse().setBody(
                """{"version":"1.2.0","chapters":[{"startTime":0,"title":"Intro"},{"startTime":30,"title":"Thema"}]}""",
            ),
        )
        server.start()
        try {
            val id = runBlocking {
                val feedId = repo.feedDao.insert(Feed(url = "https://example.org/feed", title = "Der Podcast"))
                repo.articleDao.insertAll(
                    listOf(
                        Article(
                            feedId = feedId, guid = "c", title = "Mit Kapiteln", pubDate = 0, fetchedAt = 0,
                            enclosureUrl = "https://cdn.example.org/c.wav", downloadPath = silence("c.wav").path,
                            chaptersUrl = server.url("/c.json").toString(),
                        ),
                    ),
                ).single()
            }
            val service = startService()
            val session = service.sessions.single()
            val future = MediaController.Builder(context, session.token).buildAsync()
            waitFor("controller") { future.isDone }
            val controller = future.get()
            controller.setMediaItem(MediaItem.Builder().setMediaId(id.toString()).build())
            waitFor("chapters") { runBlocking { repo.articleDao.byId(id)!!.chapters } != null }
            val chapters = io.github.mohnstollen.news.data.Chapters.decode(runBlocking { repo.articleDao.byId(id)!!.chapters })
            assertEquals(listOf(0L, 30_000L), chapters.map { it.startMs })
            assertEquals("Thema", chapters[1].title)
            controller.release()
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun lockScreenButtonsInBothLayouts() {
        val episodes = io.github.mohnstollen.news.playback.LockScreenControls
        // Previous/next episode next to play: the 10 s and 30 s buttons come after, 30 s first.
        val episodesFirst = episodes.buttons(seekMain = false)
        assertEquals(listOf("podfang.SEEK_FORWARD", "podfang.SEEK_BACK"), episodesFirst.map { it.sessionCommand!!.customAction })
        assertTrue(episodesFirst.all { it.slots.get(0) == androidx.media3.session.CommandButton.SLOT_OVERFLOW })
        assertTrue(episodes.notificationPlayerCommands(false).contains(androidx.media3.common.Player.COMMAND_SEEK_TO_NEXT))
        // 10 s / 30 s next to play: they take those places, the episode buttons follow.
        val seekFirst = episodes.buttons(seekMain = true)
        assertEquals(androidx.media3.session.CommandButton.SLOT_BACK, seekFirst[0].slots.get(0))
        assertEquals(androidx.media3.session.CommandButton.SLOT_FORWARD, seekFirst[1].slots.get(0))
        assertEquals(listOf("podfang.NEXT_EPISODE", "podfang.PREVIOUS_EPISODE"), seekFirst.drop(2).map { it.sessionCommand!!.customAction })
        val commands = episodes.notificationPlayerCommands(true)
        assertTrue(!commands.contains(androidx.media3.common.Player.COMMAND_SEEK_TO_NEXT))
        assertTrue(!commands.contains(androidx.media3.common.Player.COMMAND_SEEK_TO_PREVIOUS))
    }

    @Test
    fun nextPreviousAndSeekButtonsWorkLikeOnTheLockScreen() {
        val (a, b) = runBlocking {
            val feedId = repo.feedDao.insert(Feed(url = "https://example.org/feed", title = "Der Podcast"))
            fun episode(guid: String) = Article(
                feedId = feedId, guid = guid, title = "Folge $guid", pubDate = 0, fetchedAt = 0,
                enclosureUrl = "https://cdn.example.org/$guid.wav", enclosureType = "audio/wav",
                downloadPath = silence("$guid.wav", seconds = 100.0).path,
            )
            val ids = repo.articleDao.insertAll(listOf(episode("a"), episode("b")))
            repo.enqueue(ids[1], handOn = false)
            ids[0] to ids[1]
        }
        val service = startService()
        val session = service.sessions.single()
        val player = session.player
        val future = MediaController.Builder(context, session.token).buildAsync()
        waitFor("controller") { future.isDone }
        val controller = future.get()
        assertTrue("next is offered", controller.isCommandAvailable(androidx.media3.common.Player.COMMAND_SEEK_TO_NEXT))
        assertTrue(controller.availableSessionCommands.contains(io.github.mohnstollen.news.playback.LockScreenControls.SEEK_FORWARD))

        controller.setMediaItem(MediaItem.Builder().setMediaId(a.toString()).build())
        controller.prepare()
        waitFor("prepared") { player.duration > 0 }
        // Still offered after the player changed its own commands for the new episode.
        assertTrue(controller.isCommandAvailable(androidx.media3.common.Player.COMMAND_SEEK_TO_NEXT))
        assertTrue(controller.isCommandAvailable(androidx.media3.common.Player.COMMAND_SEEK_TO_PREVIOUS))
        // Events reach the controllers (the app's mini player depends on them).
        waitFor("controller sees the episode") { controller.currentMediaItem?.mediaId == a.toString() }

        // +30 s and -10 s, as the lock screen sends them.
        controller.sendCustomCommand(io.github.mohnstollen.news.playback.LockScreenControls.SEEK_FORWARD, android.os.Bundle.EMPTY)
        waitFor("forward") { player.currentPosition in 30_000L..31_000L }
        controller.sendCustomCommand(io.github.mohnstollen.news.playback.LockScreenControls.SEEK_BACK, android.os.Bundle.EMPTY)
        waitFor("back") { player.currentPosition in 20_000L..21_000L }

        // Next: the next episode of the queue; a keeps its place.
        controller.seekToNext()
        waitFor("next episode") { player.currentMediaItem?.mediaId == b.toString() }
        assertEquals(listOf(a, b), runBlocking { repo.queueDao.ids() })
        waitFor("a's position saved") { runBlocking { repo.articleDao.byId(a)!!.playPosition } in 20_000L..21_000L }

        // Previous, right at the start: back to a, where it was left.
        controller.seekToPrevious()
        waitFor("previous episode") { player.currentMediaItem?.mediaId == a.toString() }
        waitFor("resumed") { player.currentPosition in 20_000L..21_000L }

        // Previous, well into the episode: to its start.
        controller.seekToPrevious()
        waitFor("restart") { player.currentPosition < 1_000L }
        assertEquals(a.toString(), player.currentMediaItem?.mediaId)
        controller.release()
    }
}
