package io.github.mohnstollen.news

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.mohnstollen.news.data.ANY
import io.github.mohnstollen.news.data.ARCHIVED
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

    /** Fake Login Flow v2: whether the server offers it, and how often a poll is answered "not yet". */
    private var loginFlowAvailable = true
    private val pollsBeforeGrant = java.util.concurrent.atomic.AtomicInteger(0)
    private val pollRequests = java.util.concurrent.atomic.AtomicInteger()
    private val loginRequests = java.util.concurrent.CopyOnWriteArrayList<String>()

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
                if (path == "/index.php/login/v2" && request.method == "POST") {
                    loginRequests += "${request.getHeader("User-Agent")} ${request.getHeader("Authorization")}"
                    if (!loginFlowAvailable) return MockResponse().setResponseCode(404)
                    return MockResponse().setHeader("Content-Type", "application/json").setBody(
                        "{\"poll\":{\"token\":\"tok-123\",\"endpoint\":\"${url("/login/v2/poll")}\"}," +
                            "\"login\":\"${url("/login/v2/flow/abc")}\"}",
                    )
                }
                if (path == "/login/v2/poll") {
                    pollRequests.incrementAndGet()
                    if (request.method != "POST" || request.body.readUtf8() != "token=tok-123") {
                        return MockResponse().setResponseCode(400)
                    }
                    if (pollsBeforeGrant.getAndDecrement() > 0) return MockResponse().setResponseCode(404)
                    return MockResponse().setHeader("Content-Type", "application/json").setBody(
                        "{\"server\":\"${url("/").trimEnd('/')}\",\"loginName\":\"erika@example.org\"," +
                            "\"appPassword\":\"app-passwort\"}",
                    )
                }
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
    fun podcastEpisodesKeepTheirMediaAndPlaybackState() = runBlocking {
        val d = device()
        fun podcast(withAudio: Boolean) = buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
            append("<rss version=\"2.0\" xmlns:itunes=\"http://www.itunes.com/dtds/podcast-1.0.dtd\"><channel>")
            append("<title>Podcast</title><link>${url("/")}</link>")
            if (withAudio) append("<itunes:image href=\"${url("/cover.jpg")}\"/>")
            append("<item><guid>ep-1</guid><title>Folge 1</title>")
            if (withAudio) {
                append("<itunes:duration>30:00</itunes:duration>")
                append("<enclosure url=\"${url("/ep1.mp3")}\" type=\"audio/mpeg\" length=\"2048\"/>")
            }
            append("</item></channel></rss>")
        }
        // Stored as by an older version of the app, which did not keep the audio file.
        serve("/podcast.xml", podcast(withAudio = false))
        val feed = d.repo.addFeed(url("/podcast.xml"), null)
        val id = d.repo.articleDao.observeList(null, false).first().single().id
        assertNull(d.repo.articleDao.byId(id)!!.enclosureUrl)
        d.repo.articleDao.setPlayPosition(id, 61_000)

        serve("/podcast.xml", podcast(withAudio = true))
        assertEquals(0, d.repo.refresh(feed.id).newArticles)
        val episode = d.repo.articleDao.byId(id)!!
        assertEquals(url("/ep1.mp3"), episode.enclosureUrl)
        assertEquals("audio/mpeg", episode.enclosureType)
        assertEquals(2048L, episode.enclosureLength)
        assertEquals(1800L, episode.durationSeconds)
        assertEquals("the position survives the update", 61_000L, episode.playPosition)
        assertEquals(url("/cover.jpg"), d.repo.feedDao.byId(feed.id)!!.imageUrl)

        d.repo.articleDao.observeList(null, false).first().single().let {
            assertTrue("the list knows it is an episode", it.hasAudio)
            assertEquals(1800L, it.durationSeconds)
            assertEquals(61_000L, it.playPosition)
        }

        // Played to the end: counts as read, the read time of an already read episode stays.
        d.repo.setPlayed(id, true)
        d.repo.articleDao.byId(id)!!.let {
            assertTrue(it.playedAt > 0)
            assertTrue(it.read)
            assertEquals("played to the end starts over", 0L, it.playPosition)
        }
        d.repo.articleDao.setRead(listOf(id), true, 1234)
        d.repo.setPlayed(id, false)
        d.repo.setPlayed(id, true)
        assertEquals(1234L, d.repo.articleDao.byId(id)!!.readAt)
        d.repo.setPlayed(id, false)
        d.repo.articleDao.byId(id)!!.let {
            assertEquals(0L, it.playedAt)
            assertTrue("not played does not mean unread", it.read)
        }

        // A downloaded episode is not cleaned up while its file exists.
        d.repo.articleDao.setDownloadPath(id, "/files/episodes/1.mp3")
        d.repo.setRead(listOf(id), true)
        assertEquals(0, d.repo.articleDao.deleteOld(feed.id, Long.MAX_VALUE, listOf("andere")))
        d.repo.articleDao.setDownloadPath(id, null)
        assertEquals(1, d.repo.articleDao.deleteOld(feed.id, Long.MAX_VALUE, listOf("andere")))
    }

    @Test
    fun archivedArticlesLeaveTheListsButStayFindable() = runBlocking {
        val d = device()
        serve("/feed.xml", rss(Triple("a", "Interessant", recent), Triple("b", "Langweilig", recent.minusSeconds(60))))
        val feed = d.repo.addFeed(url("/feed.xml"), null)
        val ids = d.repo.articleDao.observeList(null, false).first().associate { it.title to it.id }
        val boring = ids.getValue("Langweilig")
        d.repo.setStarred(ids.getValue("Interessant"), true)

        d.repo.setArchived(listOf(boring), true)
        assertEquals(listOf("Interessant"), d.repo.articleDao.observeList(null, false).first().map { it.title })
        assertEquals(listOf("Interessant"), d.repo.articleDao.observeList(feed.id, false).first().map { it.title })
        val archive = d.repo.articleDao.observeList(null, false, ARCHIVED).first()
        assertEquals(listOf("Langweilig"), archive.map { it.title })
        assertTrue(archive.single().archived)
        assertTrue("archiving counts as read", archive.single().read)
        assertEquals(1, d.repo.articleDao.observeUnreadCount().first())
        assertEquals("the search still finds it", listOf(boring),
            d.repo.articleDao.observeSearch(SearchQuery.toMatch("langweilig")!!, null, false).first().map { it.id })

        // An update does not bring it back.
        serve("/feed.xml", rss(Triple("a", "Interessant", recent), Triple("b", "Langweilig", recent.minusSeconds(60))))
        d.repo.refresh(feed.id)
        assertEquals(1, d.repo.articleDao.observeList(null, false).first().size)

        // Starred ones stay in the starred list even when archived.
        d.repo.setArchived(listOf(ids.getValue("Interessant")), true)
        assertEquals(listOf("Interessant"), d.repo.articleDao.observeList(null, true, ANY).first().map { it.title })
        assertTrue(d.repo.articleDao.observeList(null, false).first().isEmpty())

        d.repo.setArchived(listOf(boring), false)
        val back = d.repo.articleDao.observeList(null, false).first().single()
        assertEquals("Langweilig", back.title)
        assertTrue("unarchiving does not make it unread", back.read)
    }

    @Test
    fun newEpisodesOfFeedsWithAutoDownloadAreHandedOn() = runBlocking {
        val d = device()
        val handed = mutableListOf<Long>()
        d.repo.onNewEpisodes = { handed += it }
        serve("/feed.xml", rss(Triple("1", "Folge 1", recent)))
        val feed = d.repo.addFeed(url("/feed.xml"), null)
        d.repo.updateFeed(feed.id, feed.title, feed.url, null, false, autoDownload = true)
        assertTrue("the back catalogue is not downloaded", handed.isEmpty())
        assertTrue(d.repo.feedDao.byId(feed.id)!!.autoDownload)

        serve("/feed.xml", rss(Triple("2", "Folge 2", Instant.now()), Triple("1", "Folge 1", recent)))
        d.repo.refresh(feed.id)
        val newId = d.repo.articleDao.observeList(null, false).first().first { it.title == "Folge 2" }.id
        assertEquals(listOf(newId), handed)

        val deleted = mutableListOf<String>()
        d.repo.onFeedDeleting = { deleted += it }
        d.repo.articleDao.setDownloadPath(newId, "/files/episodes/$newId.mp3")
        d.repo.deleteFeed(feed.id)
        assertEquals(listOf("/files/episodes/$newId.mp3"), deleted)
    }

    @Test
    fun queueKeepsItsOrderAndArchivingTakesEpisodesOut() = runBlocking {
        val d = device()
        serve("/feed.xml", rss(Triple("1", "Eins", recent), Triple("2", "Zwei", recent), Triple("3", "Drei", recent)))
        d.repo.addFeed(url("/feed.xml"), null)
        val ids = d.repo.articleDao.observeList(null, false).first().associate { it.title to it.id }
        val (eins, zwei, drei) = listOf(ids.getValue("Eins"), ids.getValue("Zwei"), ids.getValue("Drei"))

        d.repo.enqueue(eins)
        d.repo.enqueue(zwei)
        d.repo.enqueue(drei, next = true)
        assertEquals(listOf(drei, eins, zwei), d.repo.queueDao.ids())

        d.repo.moveInQueue(zwei, up = true)
        assertEquals(listOf(drei, zwei, eins), d.repo.queueDao.ids())
        d.repo.moveInQueue(drei, up = true)
        assertEquals("the first cannot move up", listOf(drei, zwei, eins), d.repo.queueDao.ids())
        d.repo.enqueue(drei)
        assertEquals("queued again: moved to the end", listOf(zwei, eins, drei), d.repo.queueDao.ids())

        assertEquals(zwei, d.repo.takeNextInQueue())
        assertEquals(listOf(eins, drei), d.repo.queueDao.observe().first().map { it.id })
        d.repo.setArchived(listOf(eins), true)
        assertEquals(listOf(drei), d.repo.queueDao.ids())
        d.repo.dequeue(drei)
        assertNull(d.repo.takeNextInQueue())
    }

    @Test
    fun listeningTimeAddsUpPerDayAndOutlivesTheFeed() = runBlocking {
        val d = device()
        serve("/a.xml", rss(Triple("1", "A1", recent), Triple("2", "A2", recent), title = "Podcast A"))
        serve("/b.xml", rss(Triple("1", "B1", recent), title = "Podcast B"))
        val a = d.repo.addFeed(url("/a.xml"), null)
        d.repo.addFeed(url("/b.xml"), null)
        val ids = d.repo.articleDao.observeList(null, false).first().associate { it.title to it.id }

        d.repo.logListening(ids.getValue("A1"), 30 * 60_000L, 45 * 60_000L)
        d.repo.logListening(ids.getValue("A1"), 10 * 60_000L, 15 * 60_000L)
        d.repo.logListening(ids.getValue("A1"), 0, 0, finished = true)
        d.repo.logListening(ids.getValue("A2"), 20 * 60_000L, 20 * 60_000L)
        d.repo.logListening(ids.getValue("B1"), 50 * 60_000L, 50 * 60_000L)

        val today = java.time.LocalDate.now().toEpochDay()
        val totals = d.repo.listenDao.observeTotals(today).first()
        assertEquals(110 * 60_000L, totals.listenedMs)
        assertEquals(130 * 60_000L, totals.contentMs)
        assertEquals(1, totals.finished)
        assertEquals(3, totals.episodes)

        val top = d.repo.listenDao.observeTopFeeds(0, 5).first()
        assertEquals(listOf("Podcast A", "Podcast B"), top.map { it.feedTitle })
        assertEquals(60 * 60_000L, top.first().listenedMs)
        assertEquals(2, top.first().episodes)
        assertEquals(listOf(today), d.repo.listenDao.observeDays(today - 6).first().map { it.day })
        assertEquals(0L, d.repo.listenDao.observeTotals(today + 1).first().listenedMs)

        // Unsubscribing keeps the history.
        d.repo.deleteFeed(a.id)
        assertEquals("Podcast A", d.repo.listenDao.observeTopFeeds(0, 5).first().first().feedTitle)
    }

    @Test
    fun subscribingWithAutoDownloadFetchesTheNewestEpisode() = runBlocking {
        val d = device()
        val handed = mutableListOf<Long>()
        d.repo.onNewEpisodes = { handed += it }
        val format = DateTimeFormatter.RFC_1123_DATE_TIME.withLocale(Locale.ENGLISH)
        fun item(guid: String, age: Long, audio: Boolean) =
            "<item><guid>$guid</guid><title>Folge $guid</title>" +
                "<pubDate>${format.format(Instant.now().minusSeconds(age).atOffset(ZoneOffset.UTC))}</pubDate>" +
                (if (audio) "<enclosure url=\"${url("/$guid.mp3")}\" type=\"audio/mpeg\"/>" else "") + "</item>"
        serve(
            "/podcast.xml",
            "<rss version=\"2.0\"><channel><title>Podcast</title>" +
                item("text", 10, audio = false) + item("neu", 100, audio = true) + item("alt", 9000, audio = true) +
                "</channel></rss>",
        )
        val feed = d.repo.addFeed(url("/podcast.xml"), null, autoDownload = true)
        assertTrue(d.repo.feedDao.byId(feed.id)!!.autoDownload)
        assertEquals(listOf(d.repo.articleDao.byGuid(feed.id, "neu")!!.id), handed)
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

    private fun loginFlow(d: Device, timeoutMs: Long = 60_000) =
        io.github.mohnstollen.news.data.NcLoginFlow(http, settings, d.sync, d.repo, pollIntervalMs = 10, timeoutMs = timeoutMs)

    private suspend fun io.github.mohnstollen.news.data.NcLoginFlow.awaitEnd() =
        kotlinx.coroutines.withTimeout(10_000) {
            state.first {
                it is io.github.mohnstollen.news.data.NcLoginFlow.State.Done ||
                    it is io.github.mohnstollen.news.data.NcLoginFlow.State.Failed
            }
        }

    @Test
    fun loginFlowSignsInWithANewAppPasswordAndSyncs() = runBlocking {
        val d = device()
        settings.update { it.copy(ncServer = "", ncUser = "") }
        ocsUserId = "erika-id"
        pollsBeforeGrant.set(2)
        val flow = loginFlow(d)

        // Also from an address that points into WebDAV.
        val page = flow.begin(url("/remote.php/dav/files/erika/"), " Lesen/News ")
        assertEquals(url("/login/v2/flow/abc"), page)
        assertEquals(io.github.mohnstollen.news.data.NcLoginFlow.State.Waiting(page), flow.state.value)
        assertEquals("named after the app, without credentials", listOf("News (Android) null"), loginRequests)

        val end = flow.awaitEnd() as io.github.mohnstollen.news.data.NcLoginFlow.State.Done
        assertEquals("Synchronisiert.", end.message)
        assertEquals("erika@example.org", end.credentials.user)
        assertEquals(3, pollRequests.get())
        assertEquals(url("/").trimEnd('/'), settings.current.ncServer)
        assertEquals("erika@example.org", settings.current.ncUser)
        assertEquals("Lesen/News", settings.current.ncFolder)
        assertEquals("app-passwort", settings.ncPassword)
        assertTrue(dav.keys.toString(), "/remote.php/dav/files/erika-id/Lesen/News/news-sync.json" in dav.keys)

        flow.acknowledge()
        assertEquals(io.github.mohnstollen.news.data.NcLoginFlow.State.Idle, flow.state.value)
    }

    @Test
    fun loginFlowExplainsProblemsAndCanBeCancelled() = runBlocking {
        val d = device()
        val flow = loginFlow(d)

        fun message(block: suspend () -> Unit): String = try {
            runBlocking { block() }
            fail("expected a SyncException")
            ""
        } catch (e: SyncException) {
            e.message!!
        }
        assertTrue(message { flow.begin(" ", "News") }.contains("Adresse"))
        loginFlowAvailable = false
        assertTrue(message { flow.begin(url("/"), "News") }.contains("keine Nextcloud"))
        assertEquals(io.github.mohnstollen.news.data.NcLoginFlow.State.Idle, flow.state.value)

        // Cancelled while waiting: no more polling, nothing saved.
        loginFlowAvailable = true
        pollsBeforeGrant.set(1_000_000)
        flow.begin(url("/"), "News")
        kotlinx.coroutines.withTimeout(5_000) { while (pollRequests.get() < 2) kotlinx.coroutines.delay(5) }
        flow.cancel()
        assertEquals(io.github.mohnstollen.news.data.NcLoginFlow.State.Idle, flow.state.value)
        val polled = pollRequests.get()
        kotlinx.coroutines.delay(200)
        assertTrue(pollRequests.get() <= polled + 1)
        assertEquals("", settings.ncPassword)

        // Never granted: gives up after the time limit.
        val impatient = loginFlow(d, timeoutMs = 100)
        impatient.begin(url("/"), "News")
        val end = impatient.awaitEnd() as io.github.mohnstollen.news.data.NcLoginFlow.State.Failed
        assertTrue(end.message.contains("abgelaufen"))
    }

    @Test
    fun loginFlowStartAddress() {
        fun init(server: String) = io.github.mohnstollen.news.data.NcLoginFlow.initUrl(server).toString()
        assertEquals("https://cloud.example.org/index.php/login/v2", init("cloud.example.org/"))
        assertEquals("https://example.org/nextcloud/index.php/login/v2", init("https://example.org/nextcloud/index.php"))
        assertEquals("https://example.org/nc/index.php/login/v2", init("https://example.org/nc/remote.php/dav/files/erika"))
        assertEquals("http://192.168.1.5:8080/index.php/login/v2", init("http://192.168.1.5:8080"))
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
