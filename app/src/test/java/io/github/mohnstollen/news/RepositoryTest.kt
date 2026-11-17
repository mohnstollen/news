package io.github.mohnstollen.news

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.mohnstollen.news.data.AppDatabase
import io.github.mohnstollen.news.data.FeedException
import io.github.mohnstollen.news.data.FeedFetcher
import io.github.mohnstollen.news.data.FullTextExtractor
import io.github.mohnstollen.news.data.Repository
import io.github.mohnstollen.news.data.SearchQuery
import io.github.mohnstollen.news.data.SettingsStore
import io.github.mohnstollen.news.data.SyncException
import io.github.mohnstollen.news.data.SyncManager
import io.github.mohnstollen.news.data.newHttpClient
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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** Plain application, so the tests do not start the real app's background scheduling. */
class TestApp : android.app.Application() {
    override fun onCreate() {
        FakeAndroidKeyStore.install()
        super.onCreate()
    }
}

@RunWith(AndroidJUnit4::class)
@Config(application = TestApp::class, sdk = [34])
class RepositoryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val server = MockWebServer()
    private val http = newHttpClient()

    /** What the fake web server and the fake Nextcloud currently hold. */
    private val pages = ConcurrentHashMap<String, MockResponse>()
    private val dav = ConcurrentHashMap<String, String>()
    private val davFolders = ConcurrentHashMap.newKeySet<String>()
    private var davStatus = 0
    /** User id the fake Nextcloud reports; null: no such endpoint (404). */
    private var ocsUserId: String? = null
    private val ocsRequests = java.util.concurrent.atomic.AtomicInteger()

    private lateinit var settings: SettingsStore
    private val databases = mutableListOf<AppDatabase>()

    private class Device(val db: AppDatabase, val repo: Repository, val sync: SyncManager)

    private fun device(): Device {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build().also { databases += it }
        val repo = Repository(db, FeedFetcher(http), FullTextExtractor(http), settings)
        return Device(db, repo, SyncManager(http, settings, repo))
    }

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.requestUrl!!.encodedPath
                if (path == "/ocs/v2.php/cloud/user") {
                    ocsRequests.incrementAndGet()
                    val id = ocsUserId ?: return MockResponse().setResponseCode(404)
                    if (request.getHeader("OCS-APIRequest") != "true") return MockResponse().setResponseCode(400)
                    if (request.getHeader("Authorization") != okhttp3.Credentials.basic("erika@example.org", "app-passwort")) {
                        return MockResponse().setResponseCode(401)
                    }
                    return MockResponse().setHeader("Content-Type", "application/json")
                        .setBody("{\"ocs\":{\"meta\":{\"status\":\"ok\"},\"data\":{\"id\":\"$id\",\"display-name\":\"Erika\"}}}")
                }
                if (!path.startsWith("/remote.php/")) {
                    val page = pages[path] ?: return MockResponse().setResponseCode(404)
                    val etag = page.headers["ETag"]
                    return if (etag != null && request.getHeader("If-None-Match") == etag) {
                        MockResponse().setResponseCode(304)
                    } else page
                }
                if (davStatus != 0) return MockResponse().setResponseCode(davStatus)
                if (request.getHeader("Authorization")?.startsWith("Basic ") != true) {
                    return MockResponse().setResponseCode(401)
                }
                return when (request.method) {
                    "MKCOL" -> MockResponse().setResponseCode(if (davFolders.add(path)) 201 else 405)
                    "PUT" -> {
                        dav[path] = request.body.readUtf8()
                        MockResponse().setResponseCode(201)
                    }
                    "GET" -> dav[path]?.let { MockResponse().setBody(it) } ?: MockResponse().setResponseCode(404)
                    else -> MockResponse().setResponseCode(405)
                }
            }
        }
        server.start()
        settings = SettingsStore(context)
        settings.update {
            it.copy(ncServer = server.url("/").toString(), ncUser = "erika", ncFolder = "Lesen/News", retentionDays = 30)
        }
    }

    @After
    fun tearDown() {
        databases.forEach { it.close() }
        server.shutdown()
    }

    private fun url(path: String) = server.url(path).toString()

    private fun rss(vararg items: Triple<String, String, Instant>, title: String = "Testfeed") = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?><rss version=\"2.0\"><channel>")
        append("<title>$title</title><link>${url("/")}</link>")
        val format = DateTimeFormatter.RFC_1123_DATE_TIME.withLocale(Locale.ENGLISH)
        for ((guid, itemTitle, date) in items) {
            append("<item><guid isPermaLink=\"false\">$guid</guid><title>$itemTitle</title>")
            append("<link>${url("/article/$guid")}</link>")
            append("<pubDate>${format.format(date.atOffset(ZoneOffset.UTC))}</pubDate>")
            append("<description><![CDATA[<p>Teaser zu $itemTitle</p><script>alert(1)</script>]]></description></item>")
        }
        append("</channel></rss>")
    }

    private fun serve(path: String, body: String, type: String = "application/rss+xml", etag: String? = null) {
        pages[path] = MockResponse().setBody(body).setHeader("Content-Type", type)
            .apply { if (etag != null) setHeader("ETag", etag) }
    }

    private val recent: Instant get() = Instant.now().minusSeconds(3600)

    @Test
    fun addFeedChecksTheAddressAndFindsFeedsLinkedFromAPage() = runBlocking {
        val d = device()
        serve("/feed.xml", rss(Triple("1", "Eins", recent), Triple("2", "Zwei", recent.minusSeconds(60))))
        serve(
            "/blog",
            "<html><head><link rel=\"alternate\" type=\"application/rss+xml\" href=\"/feed.xml\"></head><body>Blog</body></html>",
            "text/html",
        )
        serve("/plain", "<html><body>Keine Feeds hier</body></html>", "text/html")

        val feed = d.repo.addFeed(url("/blog"), " Technik ")
        assertEquals(url("/feed.xml"), feed.url)
        assertEquals("Testfeed", feed.title)
        assertEquals("Technik", feed.category)

        val list = d.repo.articleDao.observeList(null, false).first()
        assertEquals(listOf("Eins", "Zwei"), list.map { it.title })
        assertEquals("Teaser zu Eins", list[0].summary)
        val stored = d.repo.articleDao.byId(list[0].id)!!
        assertFalse("scripts are stripped", stored.content!!.contains("script"))

        for (bad in listOf(url("/feed.xml"), url("/blog"), url("/plain"), url("/missing"), "")) {
            try {
                d.repo.addFeed(bad, null)
                fail("expected a FeedException for '$bad'")
            } catch (expected: FeedException) {
            }
        }
        assertEquals(1, d.repo.feedDao.all().size)
    }

    @Test
    fun refreshKeepsStateAndCleansUpOnlyWhatLeftTheFeed() = runBlocking {
        val d = device()
        val old = Instant.now().minusSeconds(60L * 24 * 3600)
        serve(
            "/feed.xml",
            rss(Triple("new", "Neu", recent), Triple("old-kept", "Alt im Feed", old), Triple("old-gone", "Alt, fällt raus", old), Triple("old-star", "Alt gemerkt", old)),
            etag = "\"v1\"",
        )
        val feed = d.repo.addFeed(url("/feed.xml"), null)
        val ids = d.repo.articleDao.observeList(null, false).first().associate { it.title to it.id }
        d.repo.setRead(ids.values.toList(), true)
        d.repo.setStarred(ids.getValue("Alt gemerkt"), true)

        // Unchanged feed: answered with 304, nothing happens.
        val unchanged = d.repo.refresh(feed.id)
        assertEquals(0, unchanged.newArticles)
        assertEquals(0, unchanged.failedFeeds)
        assertNull(d.repo.feedDao.byId(feed.id)!!.lastError)

        serve("/feed.xml", rss(Triple("newer", "Ganz neu", Instant.now()), Triple("new", "Neu", recent), Triple("old-kept", "Alt im Feed", old)), etag = "\"v2\"")
        val result = d.repo.refreshAll()
        assertEquals(1, result.newArticles)

        val after = d.repo.articleDao.observeList(null, false).first()
        assertEquals(setOf("Ganz neu", "Neu", "Alt im Feed", "Alt gemerkt"), after.map { it.title }.toSet())
        assertEquals("only the new entry is unread", listOf("Ganz neu"), after.filter { !it.read }.map { it.title })
        assertEquals("Ganz neu", after.first().title)

        pages.remove("/feed.xml")
        assertEquals(1, d.repo.refreshAll().failedFeeds)
        assertEquals("HTTP 404", d.repo.feedDao.byId(feed.id)!!.lastError)
    }

    @Test
    fun fullTextIsExtractedFromTheLinkedPage() = runBlocking {
        val d = device()
        val paragraphs = (1..8).joinToString("") {
            "<p>Absatz $it des eigentlichen Artikels, der deutlich mehr Text enthält als der kurze Teaser im Feed. " +
                "Er erklärt den Sachverhalt ausführlich und in ganzen Sätzen.</p>"
        }
        serve(
            "/article/a",
            "<html><head><title>Artikel</title></head><body><nav><a href=\"/\">Start</a><a href=\"/x\">Mehr</a></nav>" +
                "<article><h1>Artikel</h1>$paragraphs<img src=\"/bild.png\"></article>" +
                "<footer>Impressum</footer><script>track()</script></body></html>",
            "text/html; charset=utf-8",
        )
        serve("/feed.xml", rss(Triple("a", "Artikel", recent), Triple("b", "Ohne Seite", recent)))
        val feed = d.repo.addFeed(url("/feed.xml"), null)
        val ids = d.repo.articleDao.observeList(null, false).first().associate { it.title to it.id }

        assertTrue(d.repo.loadFullText(ids.getValue("Artikel")))
        val text = d.repo.articleDao.byId(ids.getValue("Artikel"))!!.fullText!!
        assertTrue(text.contains("Absatz 8 des eigentlichen Artikels"))
        assertFalse(text.contains("Impressum"))
        assertFalse(text.contains("track()"))
        assertTrue("image addresses are absolute", text.contains(url("/bild.png")))

        // Per-feed option: new entries get their full text during the refresh.
        d.repo.updateFeed(feed.id, feed.title, feed.url, null, fetchFullText = true)
        serve("/article/c", "<html><body><article><h1>C</h1>$paragraphs</article></body></html>", "text/html")
        serve("/feed.xml", rss(Triple("c", "Dritter", Instant.now()), Triple("a", "Artikel", recent)))
        assertEquals(1, d.repo.refreshAll().newArticles)
        val third = d.repo.articleDao.observeList(null, false).first().first { it.title == "Dritter" }
        assertNotNull(d.repo.articleDao.byId(third.id)!!.fullText)
    }

    @Test
    fun searchFindsWordsInTitleTextFullTextAndNotes() = runBlocking {
        val d = device()
        serve("/feed.xml", rss(Triple("1", "Ärger um Übergangsfristen", recent), Triple("2", "Wetterbericht", recent.minusSeconds(60))))
        serve("/other.xml", rss(Triple("x", "Wetter am Wochenende", recent.minusSeconds(120)), title = "Anderer"))
        val feed = d.repo.addFeed(url("/feed.xml"), null)
        d.repo.addFeed(url("/other.xml"), null)
        val ids = d.repo.articleDao.observeList(null, false).first().associate { it.title to it.id }

        suspend fun search(text: String, feedId: Long? = null, starred: Boolean = false) =
            d.repo.articleDao.observeSearch(SearchQuery.toMatch(text)!!, feedId, starred).first().map { it.title }

        // Case and umlauts do not matter; words match from their beginning; all words must occur.
        assertEquals(listOf("Ärger um Übergangsfristen"), search("ärger"))
        assertEquals(listOf("Ärger um Übergangsfristen"), search("ÜBERGANG"))
        assertEquals(listOf("Wetterbericht", "Wetter am Wochenende"), search("wetter"))
        assertEquals(listOf("Wetter am Wochenende"), search("wetter wochen"))
        assertEquals(emptyList<String>(), search("fristen"))
        // The teaser text from the feed.
        assertEquals(3, search("teaser").size)
        // Limited to the feed or the starred articles on screen.
        assertEquals(listOf("Wetterbericht"), search("wetter", feedId = feed.id))
        d.repo.setStarred(ids.getValue("Wetter am Wochenende"), true)
        assertEquals(listOf("Wetter am Wochenende"), search("wetter", starred = true))

        // Notes and downloaded full text are indexed as they change; read articles are found too.
        d.repo.setNote(ids.getValue("Wetterbericht"), "Für den Gartenverein")
        d.repo.articleDao.setFullText(ids.getValue("Wetterbericht"), "<p>Ein Tiefdruckgebiet zieht heran.</p>")
        d.repo.setRead(ids.values.toList(), true)
        assertEquals(listOf("Wetterbericht"), search("gartenverein"))
        assertEquals(listOf("Wetterbericht"), search("tiefdruck"))
        d.repo.setNote(ids.getValue("Wetterbericht"), "")
        assertEquals(emptyList<String>(), search("gartenverein"))
        // Deleted feeds leave nothing behind in the index.
        d.repo.deleteFeed(feed.id)
        assertEquals(listOf("Wetter am Wochenende"), search("wetter"))
    }

    @Test
    fun refreshReportsNewArticlesOfFeedsWithNotifications() = runBlocking {
        val d = device()
        serve("/feed.xml", rss(Triple("1", "Eins", recent)))
        serve("/other.xml", rss(Triple("x", "X", recent), title = "Stumm"))
        val loud = d.repo.addFeed(url("/feed.xml"), null)
        val muted = d.repo.addFeed(url("/other.xml"), null)
        assertTrue("on by default", d.repo.feedDao.byId(muted.id)!!.notify)
        d.repo.updateFeed(muted.id, muted.title, muted.url, null, fetchFullText = false, notify = false)
        assertFalse(d.repo.feedDao.byId(muted.id)!!.notify)

        serve("/feed.xml", rss(Triple("2", "Zwei", Instant.now()), Triple("1", "Eins", recent)))
        serve("/other.xml", rss(Triple("y", "Y", Instant.now()), Triple("x", "X", recent), title = "Stumm"))
        val result = d.repo.refreshAll()
        assertEquals(2, result.newArticles)
        assertEquals(listOf("Zwei"), d.repo.articleDao.listItems(result.notifyIds).map { it.title })
        assertEquals(loud.id, d.repo.articleDao.listItems(result.notifyIds).single().feedId)
        assertTrue(d.repo.refreshAll().notifyIds.isEmpty())
    }

    @Test
    fun opmlRoundTripThroughTheRepository() = runBlocking {
        val a = device()
        serve("/feed.xml", rss(Triple("1", "Eins", recent)))
        a.repo.addFeed(url("/feed.xml"), "Ordner")
        val opml = a.repo.exportOpml()

        val b = device()
        assertEquals(1 to 1, b.repo.importOpml(opml.byteInputStream()))
        assertEquals(0 to 1, b.repo.importOpml(opml.byteInputStream()))
        val imported = b.repo.feedDao.all().single()
        assertEquals("Testfeed", imported.title)
        assertEquals("Ordner", imported.category)
        assertEquals(1, b.repo.refreshAll().newArticles)
    }

    @Test
    fun twoDevicesSyncThroughWebDav() = runBlocking {
        val a = device()
        val b = device()
        serve("/feed.xml", rss(Triple("1", "Eins", recent), Triple("2", "Zwei", recent)))
        serve("/other.xml", rss(Triple("x", "X", recent), title = "Anderer"))

        val feedA = a.repo.addFeed(url("/feed.xml"), "Technik")
        val one = a.repo.articleDao.observeList(null, false).first().first { it.title == "Eins" }
        a.repo.setStarred(one.id, true)
        a.repo.setNote(one.id, "Später lesen")
        assertEquals(0, a.sync.sync())
        assertTrue("/remote.php/dav/files/erika/Lesen/News/news-sync.json" in dav.keys)
        assertTrue(dav.getValue("/remote.php/dav/files/erika/Lesen/News/feeds.opml").contains("feed.xml"))

        // B learns the subscription, and the starred article with its note.
        b.repo.addFeed(url("/other.xml"), null)
        assertEquals(1, b.sync.sync())
        assertEquals(setOf("Testfeed", "Anderer"), b.repo.feedDao.all().map { it.title }.toSet())
        assertEquals("Technik", b.repo.feedDao.byUrl(feedA.url)!!.category)
        val starredOnB = b.repo.articleDao.observeList(null, true).first().single()
        assertEquals("Eins", starredOnB.title)
        assertTrue(starredOnB.hasNote)
        b.repo.refreshAll()
        val oneOnB = b.repo.articleDao.byId(starredOnB.id)!!
        assertEquals("Später lesen", oneOnB.note)
        assertNotNull("the text arrives with the next refresh", oneOnB.content)
        assertEquals("the refresh does not duplicate it", 3, b.repo.articleDao.observeList(null, false).first().size)

        // A picks up B's feed. Syncing again changes nothing.
        assertEquals(1, a.sync.sync())
        assertEquals(0, a.sync.sync())
        assertEquals(0, b.sync.sync())
        assertEquals(2, a.repo.feedDao.all().size)

        // B removes the star and edits the note, renames the feed, and unsubscribes the other one.
        Thread.sleep(5)
        b.repo.setStarred(starredOnB.id, false)
        b.repo.setNote(starredOnB.id, "Gelesen")
        b.repo.updateFeed(b.repo.feedDao.byUrl(feedA.url)!!.id, "Umbenannt", feedA.url, "Technik", false)
        b.repo.deleteFeed(b.repo.feedDao.byUrl(url("/other.xml"))!!.id)
        b.sync.sync()
        a.sync.sync()
        assertEquals(listOf("Umbenannt"), a.repo.feedDao.all().map { it.title })
        val oneOnA = a.repo.articleDao.byId(one.id)!!
        assertFalse(oneOnA.starred)
        assertEquals("Gelesen", oneOnA.note)

        // The deleted feed stays deleted on both sides, until someone subscribes again.
        b.sync.sync()
        assertEquals(1, b.repo.feedDao.all().size)
        Thread.sleep(5)
        a.repo.addFeed(url("/other.xml"), null)
        a.sync.sync()
        assertEquals(1, b.sync.sync())
        assertEquals(2, b.repo.feedDao.all().size)
    }

    @Test
    fun syncUsesTheNextcloudUserIdForTheWebDavPath() = runBlocking {
        // Logged in with an e-mail address, as after scanning a login QR code; the files live under the user id.
        val credentials = io.github.mohnstollen.news.data.NcLogin.parse(
            "nc://login/user:erika%40example.org&password:app-passwort&server:" +
                java.net.URLEncoder.encode(url("/").trimEnd('/'), "UTF-8"),
        )!!
        settings.ncPassword = credentials.password
        assertEquals("the password is stored and read back", "app-passwort", settings.ncPassword)
        settings.update { it.copy(ncServer = credentials.server, ncUser = credentials.user, ncFolder = "News") }
        ocsUserId = "erika-id"

        val a = device()
        a.sync.sync()
        assertTrue(dav.keys.toString(), "/remote.php/dav/files/erika-id/News/news-sync.json" in dav.keys)
        assertEquals("erika-id", settings.current.ncDavUser)

        // Remembered: no second lookup.
        a.sync.sync()
        assertEquals(1, ocsRequests.get())

        // A different login is looked up again; without the endpoint, the login name is used.
        ocsUserId = null
        settings.update { it.copy(ncUser = "erika") }
        a.sync.sync()
        assertEquals(2, ocsRequests.get())
        assertTrue("/remote.php/dav/files/erika/News/news-sync.json" in dav.keys)

        // An address that already points into WebDAV is used as it is, without a lookup.
        settings.update { it.copy(ncServer = url("/remote.php/dav/files/direkt/"), ncDavUser = "", ncDavUserFor = "") }
        a.sync.sync()
        assertEquals(2, ocsRequests.get())
        assertTrue("/remote.php/dav/files/direkt/News/news-sync.json" in dav.keys)
    }

    @Test
    fun syncReportsProblemsInPlainWords() = runBlocking {
        val a = device()
        davStatus = 401
        try {
            a.sync.sync()
            fail("expected a SyncException")
        } catch (e: SyncException) {
            assertTrue(e.message!!.contains("App-Passwort"))
        }
        assertNotNull(settings.current.lastSyncError)

        davStatus = 0
        dav["/remote.php/dav/files/erika/Lesen/News/news-sync.json"] = "{ kaputt"
        try {
            a.sync.sync()
            fail("expected a SyncException")
        } catch (e: SyncException) {
            assertTrue(e.message!!.contains("beschädigt"))
        }
        assertEquals("a broken file is not overwritten", "{ kaputt", dav["/remote.php/dav/files/erika/Lesen/News/news-sync.json"])
    }
}
