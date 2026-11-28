package io.github.mohnstollen.news

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.media3.common.MediaMetadata
import io.github.mohnstollen.news.data.AppDatabase
import io.github.mohnstollen.news.data.Article
import io.github.mohnstollen.news.data.Feed
import io.github.mohnstollen.news.playback.Episodes
import io.github.mohnstollen.news.ui.speedLabel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(application = TestApp::class, sdk = [34])
class EpisodesTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private val feed = Feed(id = 3, url = "https://example.org/feed", title = "Der Podcast", imageUrl = "https://example.org/cover.jpg")
    private val episode = Article(
        id = 7, feedId = 3, guid = "ep", title = "Folge 7", pubDate = 0, fetchedAt = 0,
        enclosureUrl = "https://cdn.example.org/ep7.mp3", enclosureType = "audio/mpeg", durationSeconds = 1800,
    )

    @Test
    fun mediaItemCarriesAddressAndMetadata() {
        val item = Episodes.mediaItem(episode, feed)!!
        assertEquals("7", item.mediaId)
        assertEquals(Uri.parse("https://cdn.example.org/ep7.mp3"), item.localConfiguration!!.uri)
        assertEquals("audio/mpeg", item.localConfiguration!!.mimeType)
        assertEquals("Folge 7", item.mediaMetadata.title)
        assertEquals("Der Podcast", item.mediaMetadata.artist)
        assertEquals("falls back to the feed's cover", Uri.parse("https://example.org/cover.jpg"), item.mediaMetadata.artworkUri)
        assertEquals(MediaMetadata.MEDIA_TYPE_PODCAST_EPISODE, item.mediaMetadata.mediaType)

        val own = Episodes.mediaItem(episode.copy(imageUrl = "https://example.org/ep7.jpg"), feed)!!
        assertEquals(Uri.parse("https://example.org/ep7.jpg"), own.mediaMetadata.artworkUri)

        assertNull("plain articles cannot be played", Episodes.mediaItem(episode.copy(enclosureUrl = null), feed))
    }

    @Test
    fun downloadedFileIsPreferredWhileItExists() {
        val file = File(context.filesDir, "ep7.mp3").apply { writeText("x") }
        val local = Episodes.mediaItem(episode.copy(downloadPath = file.path), feed)!!
        assertEquals(Uri.fromFile(file), local.localConfiguration!!.uri)

        file.delete()
        val gone = Episodes.mediaItem(episode.copy(downloadPath = file.path), feed)!!
        assertEquals(Uri.parse(episode.enclosureUrl), gone.localConfiguration!!.uri)
    }

    @Test
    fun resumesUnlessFinishedOrAtTheEnd() {
        assertEquals(0L, Episodes.startPosition(episode))
        assertEquals(600_000L, Episodes.startPosition(episode.copy(playPosition = 600_000)))
        assertEquals("unknown length: resume anyway", 600_000L, Episodes.startPosition(episode.copy(playPosition = 600_000, durationSeconds = null)))
        assertEquals("a few seconds before the end", 0L, Episodes.startPosition(episode.copy(playPosition = 1_795_000)))
        assertEquals("played to the end", 0L, Episodes.startPosition(episode.copy(playPosition = 600_000, playedAt = 1)))
        // A podcast's intro is skipped when starting from the beginning, not when resuming.
        assertEquals(30_000L, Episodes.startPosition(episode, skipIntroMs = 30_000))
        assertEquals(600_000L, Episodes.startPosition(episode.copy(playPosition = 600_000), skipIntroMs = 30_000))
        assertEquals("heard: from the start again, past the intro", 30_000L, Episodes.startPosition(episode.copy(playedAt = 1), skipIntroMs = 30_000))
        assertEquals("unknown length", 30_000L, Episodes.startPosition(episode.copy(durationSeconds = null), skipIntroMs = 30_000))
        assertEquals("too short for it", 0L, Episodes.startPosition(episode.copy(durationSeconds = 50), skipIntroMs = 30_000))
        // With a 60-second end buffer, a position in the outro starts over.
        assertEquals(0L, Episodes.startPosition(episode.copy(playPosition = 1_750_000), endBufferMs = 60_000))
        assertEquals(1_700_000L, Episodes.startPosition(episode.copy(playPosition = 1_700_000), endBufferMs = 60_000))
    }

    @Test
    fun timeLeftInAList() {
        val (left, unknown) = Episodes.remaining(listOf(1800L to 600_000L, 3600L to 0L, null to 0L, 600L to 700_000L))
        assertEquals("20 + 60 + 0 minutes", 80 * 60_000L, left)
        assertEquals(1, unknown)
        assertEquals("1:20 Std", Episodes.total(left))
        assertEquals("45 Min", Episodes.total(45 * 60_000L))
        assertEquals("12:34 Std", Episodes.total((12 * 60 + 34) * 60_000L))
    }

    @Test
    fun endBuffer() {
        val hour = 3_600_000L
        assertFalse("off", Episodes.inEndBuffer(hour - 1_000, hour, 0))
        assertTrue(Episodes.inEndBuffer(hour - 30_000, hour, 30_000))
        assertTrue(Episodes.inEndBuffer(hour - 1, hour, 30_000))
        assertFalse(Episodes.inEndBuffer(hour - 31_000, hour, 30_000))
        assertFalse("at the end it is over anyway", Episodes.inEndBuffer(hour, hour, 30_000))
        assertFalse("short clips play to the end", Episodes.inEndBuffer(70_000, 80_000, 30_000))
    }

    @Test
    fun labelsAndTimes() {
        assertEquals("30 Min", Episodes.label(1800, 0, 0))
        assertEquals("1 Std 5 Min", Episodes.label(3900, 0, 0))
        assertEquals("2 Std", Episodes.label(7200, 0, 0))
        assertEquals("1 Min", Episodes.label(20, 0, 0))
        assertEquals("noch 20 Min", Episodes.label(1800, 600_000, 0))
        assertEquals("Begonnen", Episodes.label(null, 5_000, 0))
        assertEquals("Gehört", Episodes.label(1800, 0, 99))
        assertNull(Episodes.label(null, 0, 0))

        assertEquals("0:00", Episodes.clock(0))
        assertEquals("4:05", Episodes.clock(245_999))
        assertEquals("1:02:03", Episodes.clock(3_723_000))
        assertEquals("1×", speedLabel(1f))
        assertEquals("1,5×", speedLabel(1.5f))
    }

    @Test
    fun resolveLooksUpTheStoredEpisode() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        try {
            val feedId = db.feedDao().insert(feed.copy(id = 0))
            val id = db.articleDao().insertAll(listOf(episode.copy(id = 0, feedId = feedId, playPosition = 90_000))).single()
            val plain = db.articleDao().insertAll(listOf(episode.copy(id = 0, feedId = feedId, guid = "text", enclosureUrl = null))).single()

            val (item, start) = Episodes.resolve(id.toString(), db.articleDao(), db.feedDao())!!
            assertEquals(id.toString(), item.mediaId)
            assertEquals(90_000L, start)
            assertNull(Episodes.resolve(plain.toString(), db.articleDao(), db.feedDao()))
            assertNull(Episodes.resolve("999", db.articleDao(), db.feedDao()))
            assertNull(Episodes.resolve("kein-id", db.articleDao(), db.feedDao()))
        } finally {
            db.close()
        }
    }

    @Test
    fun chapterFilesAndHelpers() {
        val json = """{"version":"1.2.0","chapters":[{"startTime":95.5,"title":"Teil 2"},{"startTime":0,"title":"Intro"},{"title":"ohne Zeit"}]}"""
        val chapters = io.github.mohnstollen.news.data.Chapters.parseJson(json)
        assertEquals(listOf(0L, 95_500L), chapters.map { it.startMs })
        val stored = io.github.mohnstollen.news.data.Chapters.encode(chapters)
        assertEquals(chapters, io.github.mohnstollen.news.data.Chapters.decode(stored))
        assertTrue(io.github.mohnstollen.news.data.Chapters.decode(null).isEmpty())
        assertTrue(io.github.mohnstollen.news.data.Chapters.decode("kaputt").isEmpty())
        assertEquals(-1, io.github.mohnstollen.news.data.Chapters.current(listOf(io.github.mohnstollen.news.data.Chapter(5_000, "a")), 1_000))
        assertEquals(1, io.github.mohnstollen.news.data.Chapters.current(chapters, 100_000))
        assertEquals(0, io.github.mohnstollen.news.data.Chapters.current(chapters, 95_499))
    }
}
