package io.github.mohnstollen.news.desktop

import io.github.mohnstollen.news.data.FeedException
import io.github.mohnstollen.news.data.SearchQuery
import io.github.mohnstollen.news.data.SettingsStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap

/** The shared data layer (Room on the JVM with the bundled SQLite) as the desktop app uses it. */
class DesktopDataTest {
    private val server = MockWebServer()
    private val pages = ConcurrentHashMap<String, String>()
    private val dav = ConcurrentHashMap<String, String>()
    private val tmp: File = Files.createTempDirectory("news-test").toFile()
    private val containers = mutableListOf<AppContainer>()

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path!!
                if (path.startsWith("/remote.php/")) {
                    return when (request.method) {
                        "MKCOL" -> MockResponse().setResponseCode(201)
                        "PUT" -> { dav[path] = request.body.readUtf8(); MockResponse().setResponseCode(201) }
                        "GET" -> dav[path]?.let { MockResponse().setBody(it) } ?: MockResponse().setResponseCode(404)
                        else -> MockResponse().setResponseCode(405)
                    }
                }
                return pages[path]?.let { MockResponse().setBody(it).setHeader("Content-Type", "application/rss+xml") }
                    ?: MockResponse().setResponseCode(404)
            }
        }
        server.start()
    }

    @After
    fun tearDown() {
        containers.forEach { it.db.close() }
        server.shutdown()
        tmp.deleteRecursively()
    }

    private fun device(name: String): AppContainer {
        // Keep the test password out of the real keyring.
        val settings = SettingsStore(File(tmp, "$name/settings.properties"), useKeyring = false)
        return AppContainer(dbFile = File(tmp, "$name/news.db"), settings = settings).also { containers += it }
    }

    private fun rss(vararg items: Pair<String, String>) = """
        <?xml version="1.0" encoding="UTF-8"?>
        <rss version="2.0"><channel><title>Testfeed</title><link>https://example.org/</link>
        ${items.joinToString("") { (guid, title) ->
            "<item><guid>$guid</guid><title>$title</title><link>https://example.org/$guid</link>" +
                "<description>&lt;p&gt;Text über $title&lt;/p&gt;</description>" +
                "<pubDate>Mon, 02 Nov 2026 10:00:00 GMT</pubDate></item>"
        }}
        </channel></rss>
    """.trimIndent()

    @Test
    fun addRefreshSearchAndReadState() = runBlocking {
        pages["/feed"] = rss("a" to "Erster Artikel", "b" to "Zweiter Beitrag")
        val c = device("one")
        val repo = c.repository
        val feed = repo.addFeed(server.url("/feed").toString(), "Technik")
        assertEquals("Testfeed", feed.title)
        assertEquals(2, repo.articleDao.observeUnreadCount().first())

        try {
            repo.addFeed(server.url("/feed").toString(), null)
            fail("duplicate accepted")
        } catch (e: FeedException) {
            assertTrue(e.message!!.contains("bereits abonniert"))
        }

        // Full-text search over the FTS4 index, in the bundled SQLite.
        val hits = repo.articleDao.observeSearch(SearchQuery.toMatch("beitr")!!, null, false).first()
        assertEquals(listOf("Zweiter Beitrag"), hits.map { it.title })

        pages["/feed"] = rss("a" to "Erster Artikel", "b" to "Zweiter Beitrag", "c" to "Dritter")
        val result = repo.refreshAll()
        assertEquals(1, result.newArticles)
        assertEquals(0, result.failedFeeds)

        val all = repo.articleDao.observeList(null, false).first()
        repo.setRead(all.map { it.id }, true)
        assertEquals(0, repo.articleDao.observeUnreadCount().first())
        assertTrue(repo.articleDao.observeList(null, false).first().all { it.read && it.readAt > 0 })

        repo.deleteFeed(feed.id)
        assertEquals(0, repo.articleDao.observeList(null, false).first().size)
        assertEquals(1, repo.feedDao.tombstones().size)
    }

    @Test
    fun syncBetweenTwoDevices() = runBlocking {
        pages["/feed"] = rss("a" to "Erster Artikel")
        val one = device("one")
        val two = device("two")
        for (d in listOf(one, two)) {
            d.settings.update { it.copy(ncServer = server.url("/").toString(), ncUser = "anna", ncFolder = "News") }
            d.settings.ncPassword = "geheim"
        }
        assertEquals("geheim", one.settings.ncPassword)

        one.repository.addFeed(server.url("/feed").toString(), null)
        val article = one.repository.articleDao.observeList(null, false).first().single()
        one.repository.setStarred(article.id, true)
        one.repository.setNote(article.id, "Später lesen")
        one.sync.sync()
        assertNotNull(dav["/remote.php/dav/files/anna/News/news-sync.json"])
        assertTrue(dav["/remote.php/dav/files/anna/News/feeds.opml"]!!.contains("Testfeed"))

        assertEquals(1, two.sync.sync())
        val synced = two.repository.articleDao.observeList(null, true).first().single()
        assertEquals("Erster Artikel", synced.title)
        assertTrue(synced.hasNote)
        assertNull(two.settings.current.lastSyncError)
        assertTrue(two.settings.current.lastSync > 0)

        // The second device fills in the text on its first refresh.
        two.repository.refreshAll()
        val full = two.repository.articleDao.byId(synced.id)!!
        assertNotNull(full.content)
        assertFalse(full.content!!.isBlank())
    }

    @Test
    fun settingsSurviveRestart() {
        val file = File(tmp, "cfg/settings.properties")
        SettingsStore(file).update { it.copy(intervalMinutes = 30, ncFolder = "Feeds", lastSyncError = "kaputt") }
        val again = SettingsStore(file).current
        assertEquals(30, again.intervalMinutes)
        assertEquals("Feeds", again.ncFolder)
        assertEquals("kaputt", again.lastSyncError)
    }
}
