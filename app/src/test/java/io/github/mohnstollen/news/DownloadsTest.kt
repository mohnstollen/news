package io.github.mohnstollen.news

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import io.github.mohnstollen.news.data.Article
import io.github.mohnstollen.news.data.Feed
import io.github.mohnstollen.news.playback.DownloadWorker
import io.github.mohnstollen.news.ui.container
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(AndroidJUnit4::class)
@Config(application = UiTestApp::class, sdk = [34])
class DownloadsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val repo get() = context.container.repository
    private val downloads get() = context.container.downloads
    private val server = MockWebServer()

    /** The "audio file": 200 kB of counting bytes. */
    private val audio = ByteArray(200_000) { (it % 251).toByte() }
    private val ranges = CopyOnWriteArrayList<String?>()
    private var feedId = 0L

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path != "/ep.mp3") return MockResponse().setResponseCode(404)
                val range = request.getHeader("Range")
                ranges += range
                val from = range?.removePrefix("bytes=")?.removeSuffix("-")?.toInt()
                return if (from != null) {
                    MockResponse().setResponseCode(206)
                        .setHeader("Content-Range", "bytes $from-${audio.size - 1}/${audio.size}")
                        .setBody(Buffer().write(audio, from, audio.size - from))
                } else {
                    MockResponse().setBody(Buffer().write(audio))
                }
            }
        }
        server.start()
        feedId = runBlocking { repo.feedDao.insert(Feed(url = "https://example.org/feed", title = "Podcast")) }
    }

    @After
    fun tearDown() = server.shutdown()

    private fun episode(guid: String, path: String = "/ep.mp3", archived: Boolean = false) = runBlocking {
        repo.articleDao.insertAll(
            listOf(
                Article(
                    feedId = feedId, guid = guid, title = "Folge $guid", pubDate = 0, fetchedAt = 0,
                    enclosureUrl = server.url(path).toString(), enclosureType = "audio/mpeg",
                    archivedAt = if (archived) 1 else 0,
                ),
            ),
        ).single()
    }

    private fun runWorker(id: Long): ListenableWorker.Result = runBlocking {
        TestListenableWorkerBuilder<DownloadWorker>(context)
            .setInputData(workDataOf(DownloadWorker.KEY_ARTICLE to id))
            .build()
            .doWork()
    }

    private fun article(id: Long) = runBlocking { repo.articleDao.byId(id)!! }

    @Test
    fun downloadsTheFileAndPlaybackUsesIt() {
        val id = episode("1")
        assertEquals(ListenableWorker.Result.success(), runWorker(id))
        val file = File(article(id).downloadPath!!)
        assertTrue(file.readBytes().contentEquals(audio))
        assertEquals("$id.mp3", file.name)
        assertEquals(listOf<String?>(null), ranges)
        assertFalse("no partial file left", File(file.path + ".part").exists())

        // Downloading again is a no-op.
        assertEquals(ListenableWorker.Result.success(), runWorker(id))
        assertEquals(1, ranges.size)

        // Deleting removes the file; playback streams again.
        runBlocking { downloads.delete(listOf(id)) }
        assertFalse(file.exists())
        assertNull(article(id).downloadPath)
    }

    @Test
    fun anInterruptedDownloadContinuesWhereItStopped() {
        val id = episode("2")
        val target = downloads.fileFor(id, server.url("/ep.mp3").toString(), "audio/mpeg")
        File(target.path + ".part").writeBytes(audio.copyOfRange(0, 80_000))

        assertEquals(ListenableWorker.Result.success(), runWorker(id))
        assertEquals(listOf<String?>("bytes=80000-"), ranges)
        assertTrue(File(article(id).downloadPath!!).readBytes().contentEquals(audio))
    }

    @Test
    fun missingFilesFailAndArchivedEpisodesAreSkipped() {
        val missing = episode("3", path = "/gibt-es-nicht.mp3")
        val result = runWorker(missing)
        assertTrue(result is ListenableWorker.Result.Failure)
        assertEquals("HTTP 404", result.outputData.getString(DownloadWorker.KEY_ERROR))
        assertNull(article(missing).downloadPath)

        val archived = episode("4", archived = true)
        assertEquals(ListenableWorker.Result.success(), runWorker(archived))
        assertNull(article(archived).downloadPath)
        assertTrue(ranges.isEmpty())
    }

    @Test
    fun playedEpisodesAreDeletedIfSetAndCleanUpRemovesStrayFiles() {
        val id = episode("5")
        runWorker(id)
        val file = File(article(id).downloadPath!!)

        context.container.settings.update { it.copy(deletePlayedDownloads = false) }
        runBlocking { downloads.onPlayed(id) }
        assertTrue("kept when switched off", file.exists())
        context.container.settings.update { it.copy(deletePlayedDownloads = true) }
        runBlocking { downloads.onPlayed(id) }
        assertFalse(file.exists())
        assertNull(article(id).downloadPath)

        // A file nobody points to goes; a path whose file is gone is forgotten.
        val stray = File(downloads.dir, "999.mp3").apply { writeText("alt") }
        val other = episode("6")
        runWorker(other)
        File(article(other).downloadPath!!).delete()
        runBlocking { downloads.cleanUp() }
        assertFalse(stray.exists())
        assertNull(article(other).downloadPath)

        assertEquals(0 to 0L, runBlocking { downloads.usage() })
    }

    @Test
    fun fileNamesKeepTheExtension() {
        assertEquals("7.m4a", downloads.fileFor(7, "https://cdn.example.org/a/folge.m4a?x=1", null).name)
        assertEquals("7.mp3", downloads.fileFor(7, "https://cdn.example.org/stream", "audio/mpeg").name)
        assertEquals("7.ogg", downloads.fileFor(7, "https://cdn.example.org/stream", "audio/ogg").name)
        assertEquals("7.mp4", downloads.fileFor(7, "https://cdn.example.org/play?id=3", "video/mp4").name)
    }
}
