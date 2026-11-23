package io.github.mohnstollen.news

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaBrowser
import androidx.media3.session.MediaConstants
import androidx.media3.session.MediaController
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.mohnstollen.news.data.Article
import io.github.mohnstollen.news.data.Feed
import io.github.mohnstollen.news.playback.CoverProvider
import io.github.mohnstollen.news.playback.Library
import io.github.mohnstollen.news.playback.PlaybackService
import io.github.mohnstollen.news.ui.container
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Android Auto: browsing, spoken requests, "play" with nothing loaded, covers. */
@RunWith(AndroidJUnit4::class)
@Config(application = UiTestApp::class, sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CarTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val repo get() = context.container.repository
    private val services = mutableListOf<PlaybackService>()
    private val server = MockWebServer()

    @After
    fun tearDown() {
        services.forEach { it.onDestroy() }
        server.shutdown()
    }

    private fun waitFor(what: String, done: () -> Boolean) {
        repeat(1500) {
            shadowOf(Looper.getMainLooper()).idle()
            if (done()) return
            Thread.sleep(10)
        }
        throw AssertionError("timed out waiting for $what")
    }

    private fun <T> await(future: com.google.common.util.concurrent.ListenableFuture<T>): T {
        waitFor("result") { future.isDone }
        return future.get()
    }

    private data class Seeded(val lage: Long, val lageOld: Long, val sport: Long, val queued: Long, val lageFeed: Long)

    private fun seed(imageUrl: String? = null): Seeded = runBlocking {
        val lageFeed = repo.feedDao.insert(Feed(url = "https://lage.example/feed", title = "Lage der Nation", imageUrl = imageUrl))
        val sportFeed = repo.feedDao.insert(Feed(url = "https://sport.example/feed", title = "Sportschau – Der Podcast"))
        fun episode(feedId: Long, guid: String, title: String, pubDate: Long, played: Boolean = false, position: Long = 0) = Article(
            feedId = feedId, guid = guid, title = title, pubDate = pubDate, fetchedAt = 0,
            enclosureUrl = "https://cdn.example/$guid.mp3", enclosureType = "audio/mpeg",
            durationSeconds = 3600, playPosition = position, playedAt = if (played) 1 else 0, read = played,
        )
        val ids = repo.articleDao.insertAll(
            listOf(
                episode(lageFeed, "lage-new", "Lage 412", 3_000),
                episode(lageFeed, "lage-old", "Lage 411", 2_000, played = true),
                episode(sportFeed, "sport", "Bundesliga am Sonntag", 2_500),
                episode(sportFeed, "queued", "Halbzeit", 1_000, position = 1_800_000),
            ),
        )
        repo.enqueue(ids[3], handOn = false)
        Seeded(ids[0], ids[1], ids[2], ids[3], lageFeed)
    }

    private fun startService(): PlaybackService =
        Robolectric.buildService(PlaybackService::class.java).create().get().also { services += it }

    @Test
    fun theCarBrowsesQueueInboxAndPodcasts() {
        val s = seed(imageUrl = "https://lage.example/cover.jpg")
        val service = startService()
        val browser = await(MediaBrowser.Builder(context, service.sessions.single().token).buildAsync())

        val root = await(browser.getLibraryRoot(null))
        assertEquals(Library.ROOT, root.value!!.mediaId)
        val tabs = await(browser.getChildren(Library.ROOT, 0, 100, null)).value!!
        assertEquals(listOf("Warteschlange", "Neu", "Podcasts"), tabs.map { it.mediaMetadata.title.toString() })
        assertTrue(tabs.all { it.mediaMetadata.isBrowsable == true && it.mediaMetadata.isPlayable == false })
        assertEquals(
            "podcasts as a grid of covers",
            MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM,
            tabs[2].mediaMetadata.extras!!.getInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE),
        )

        val queue = await(browser.getChildren(Library.QUEUE, 0, 100, null)).value!!
        assertEquals(listOf(s.queued.toString()), queue.map { it.mediaId })
        val halbzeit = queue.single().mediaMetadata
        assertEquals("Halbzeit", halbzeit.title.toString())
        assertEquals("Sportschau – Der Podcast · noch 30 Min", halbzeit.subtitle.toString())
        assertEquals(true, halbzeit.isPlayable)
        assertEquals(
            MediaConstants.EXTRAS_VALUE_COMPLETION_STATUS_PARTIALLY_PLAYED,
            halbzeit.extras!!.getInt(MediaConstants.EXTRAS_KEY_COMPLETION_STATUS),
        )
        assertEquals(0.5, halbzeit.extras!!.getDouble(MediaConstants.EXTRAS_KEY_COMPLETION_PERCENTAGE), 0.001)

        // The inbox: neither played nor queued, newest first.
        val inbox = await(browser.getChildren(Library.NEW, 0, 100, null)).value!!
        assertEquals(listOf(s.lage, s.sport).map { it.toString() }, inbox.map { it.mediaId })
        // Covers come from the app's own provider.
        assertEquals("content", inbox[0].mediaMetadata.artworkUri?.scheme)
        assertEquals(CoverProvider.Cover.Episode(s.lage), CoverProvider.parse(inbox[0].mediaMetadata.artworkUri!!))
        assertNull("no cover, no picture", inbox[1].mediaMetadata.artworkUri)

        val podcasts = await(browser.getChildren(Library.PODCASTS, 0, 100, null)).value!!
        assertEquals(listOf("Lage der Nation", "Sportschau – Der Podcast"), podcasts.map { it.mediaMetadata.title.toString() })
        val episodes = await(browser.getChildren(podcasts[0].mediaId, 0, 100, null)).value!!
        assertEquals(listOf("Lage 412", "Lage 411"), episodes.map { it.mediaMetadata.title.toString() })
        assertEquals(
            MediaConstants.EXTRAS_VALUE_COMPLETION_STATUS_FULLY_PLAYED,
            episodes[1].mediaMetadata.extras!!.getInt(MediaConstants.EXTRAS_KEY_COMPLETION_STATUS),
        )
        // Pages, as the car asks for them.
        assertEquals(listOf("Lage 411"), await(browser.getChildren(podcasts[0].mediaId, 1, 1, null)).value!!.map { it.mediaMetadata.title.toString() })
        assertEquals("Lage 412", await(browser.getItem(s.lage.toString())).value!!.mediaMetadata.title.toString())

        // Playing from the car: as in the app, the episode goes to the top of the queue.
        browser.setMediaItem(MediaItem.Builder().setMediaId(s.lage.toString()).build())
        val player = service.sessions.single().player
        waitFor("loaded") { player.currentMediaItem?.mediaId == s.lage.toString() }
        waitFor("queued") { runBlocking { repo.queueDao.ids() } == listOf(s.lage, s.queued) }
        browser.release()
    }

    @Test
    fun spokenRequestsFindThePodcastOrTheEpisode() {
        val s = seed()
        val feeds = runBlocking { repo.feedDao.all() }
        assertEquals("Lage der Nation", Library.matchingFeed("lage der nation", feeds)?.title)
        assertEquals("Sportschau – Der Podcast", Library.matchingFeed("Sportschau", feeds)?.title)
        assertNull(Library.matchingFeed("Tagesschau", feeds))

        val service = startService()
        val player = service.sessions.single().player
        val controller = await(MediaController.Builder(context, service.sessions.single().token).buildAsync())
        fun say(words: String) {
            controller.setMediaItem(
                MediaItem.Builder().setRequestMetadata(MediaItem.RequestMetadata.Builder().setSearchQuery(words).build()).build(),
            )
        }
        // A podcast: its newest episode not yet heard.
        say("Lage der Nation")
        waitFor("lage") { player.currentMediaItem?.mediaId == s.lage.toString() }
        // An episode by its title.
        say("bundesliga")
        waitFor("sport") { player.currentMediaItem?.mediaId == s.sport.toString() }
        // Nothing that fits: the queue goes on.
        runBlocking {
            repo.dequeue(s.sport)
            repo.dequeue(s.lage)
        }
        say("Wetterbericht")
        waitFor("queue") { player.currentMediaItem?.mediaId == s.queued.toString() }
        controller.release()
    }

    @Test
    fun playWithNothingLoadedResumesTheQueue() {
        val s = seed()
        val service = startService()
        val player = service.sessions.single().player
        val controller = await(MediaController.Builder(context, service.sessions.single().token).buildAsync())
        controller.play()
        waitFor("resumed") { player.currentMediaItem?.mediaId == s.queued.toString() }
        assertEquals("where it was left", 1_800_000L, player.currentPosition)
        controller.release()
    }

    @Test
    fun coversAreFetchedOnceMadeSmallAndKept() {
        val big = Bitmap.createBitmap(1400, 1400, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF3949AB.toInt()) }
        val png = Buffer().apply { big.compress(Bitmap.CompressFormat.PNG, 100, outputStream()) }
        server.enqueue(MockResponse().setBody(png))
        server.start()
        val s = seed(imageUrl = server.url("/cover.png").toString())

        val cover = CoverProvider.Cover.Podcast(s.lageFeed)
        val uri = CoverProvider.uri(context, cover)
        assertEquals("content://${context.packageName}.covers/podcast/${s.lageFeed}", uri.toString())
        assertEquals(cover, CoverProvider.parse(uri))

        val file = runBlocking { CoverProvider.file(context, cover) }
        assertNotNull(file)
        val stored = BitmapFactory.decodeFile(file!!.path)
        assertEquals(512, maxOf(stored.width, stored.height))
        // An episode without its own picture shows its podcast's, from the same stored file.
        assertEquals(file, runBlocking { CoverProvider.file(context, CoverProvider.Cover.Episode(s.lage)) })
        assertEquals("fetched once", 1, server.requestCount)
        // Without a picture there is nothing to serve.
        assertNull(runBlocking { CoverProvider.file(context, CoverProvider.Cover.Episode(s.sport)) })
    }
}
