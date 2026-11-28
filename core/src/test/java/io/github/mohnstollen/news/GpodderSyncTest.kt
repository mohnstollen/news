package io.github.mohnstollen.news

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.mohnstollen.news.data.Article
import io.github.mohnstollen.news.data.Feed
import io.github.mohnstollen.news.data.GpodderAction
import io.github.mohnstollen.news.data.GpodderClient
import io.github.mohnstollen.news.data.GpodderSync
import io.github.mohnstollen.news.ui.container
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Podfang with gPodder Sync, against a stand-in for the Nextcloud app (and for WebDAV, which the
 * sync uses as well): subscriptions and listening positions both ways.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = UiTestApp::class, sdk = [34])
class GpodderSyncTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val repo get() = context.container.repository
    private val settings get() = context.container.settings
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    /** The server's side: changes in the order they arrived, each with its time. */
    private inner class FakeNextcloud(var gpodderInstalled: Boolean = true) : Dispatcher() {
        var clock = 100L
        val subscriptionChanges = mutableListOf<Triple<Long, String, Boolean>>() // time, url, subscribed
        val actions = mutableListOf<Pair<Long, GpodderAction>>()
        val files = mutableMapOf<String, String>()

        fun subscribe(url: String, subscribed: Boolean = true) = subscriptionChanges.add(Triple(++clock, url, subscribed))
        fun act(action: GpodderAction) = actions.add(++clock to action)
        fun subscribed() = subscriptionChanges.groupBy { it.second }.filter { (_, c) -> c.last().third }.keys

        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.requestUrl!!.encodedPath
            val since = request.requestUrl!!.queryParameter("since")?.toLong() ?: 0
            if (path.startsWith("/index.php/apps/gpoddersync/")) {
                if (!gpodderInstalled) return MockResponse().setResponseCode(404)
                val body = request.body.readUtf8()
                return when (path.removePrefix("/index.php/apps/gpoddersync/")) {
                    "subscriptions" -> {
                        val latest = subscriptionChanges.filter { it.first > since }.groupBy { it.second }.mapValues { it.value.last().third }
                        val add = latest.filterValues { it }.keys.joinToString(",") { "\"$it\"" }
                        val remove = latest.filterValues { !it }.keys.joinToString(",") { "\"$it\"" }
                        MockResponse().setBody("""{"add":[$add],"remove":[$remove],"timestamp":$clock}""")
                    }
                    "subscription_change/create" -> {
                        val change = Json.parseToJsonElement(body) as kotlinx.serialization.json.JsonObject
                        fun urls(key: String) = (change[key] as kotlinx.serialization.json.JsonArray).map {
                            (it as kotlinx.serialization.json.JsonPrimitive).content
                        }
                        urls("add").forEach { subscribe(it) }
                        urls("remove").forEach { subscribe(it, false) }
                        MockResponse().setBody("""{"timestamp":$clock}""")
                    }
                    "episode_action" -> {
                        val list = actions.filter { it.first > since }.map { it.second }
                        val encoded = json.encodeToString(ListSerializer(GpodderAction.serializer()), list)
                        MockResponse().setBody("""{"actions":$encoded,"timestamp":$clock}""")
                    }
                    "episode_action/create" -> {
                        json.decodeFromString(ListSerializer(GpodderAction.serializer()), body).forEach(::act)
                        MockResponse().setBody("""{"timestamp":$clock}""")
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
            // WebDAV and the user id lookup, for the rest of the sync.
            return when (request.method) {
                "MKCOL" -> MockResponse().setResponseCode(201)
                "PUT" -> MockResponse().setResponseCode(201).also { files[path] = request.body.readUtf8() }
                "GET" -> files[path]?.let { MockResponse().setBody(it) } ?: MockResponse().setResponseCode(404)
                else -> MockResponse().setResponseCode(404)
            }
        }
    }

    private val server = MockWebServer()
    private val cloud = FakeNextcloud()

    @Before
    fun setUp() {
        FakeAndroidKeyStore.install()
        server.dispatcher = cloud
        server.start()
        settings.ncPassword = "app-passwort"
        settings.update { it.copy(ncServer = server.url("/").toString(), ncUser = "anna", ncFolder = "Podfang") }
    }

    @After
    fun tearDown() = server.shutdown()

    private fun feed(url: String, title: String) = runBlocking { repo.feedDao.insert(Feed(url = url, title = title)) }

    private fun episode(feedId: Long, guid: String) = runBlocking {
        repo.articleDao.insertAll(
            listOf(
                Article(
                    feedId = feedId, guid = guid, title = guid, pubDate = 0, fetchedAt = 0,
                    enclosureUrl = "https://cdn.example/$guid.mp3", enclosureType = "audio/mpeg", durationSeconds = 3600,
                ),
            ),
        ).single()
    }

    private fun sync() = runBlocking { context.container.sync.sync() }
    private fun urls() = runBlocking { repo.feedDao.all().map { it.url }.toSet() }

    @Test
    fun subscriptionsGoBothWays() {
        feed("https://a.example/feed", "A")
        feed("https://b.example/feed", "B")
        // Subscribed in AntennaPod; and an old removal there of what this phone still has.
        cloud.subscribe("https://c.example/feed")
        cloud.subscribe("https://b.example/feed", false)

        assertEquals("one new podcast", 1, sync())
        assertEquals(setOf("https://a.example/feed", "https://b.example/feed", "https://c.example/feed"), urls())
        assertEquals("the phone's podcasts went up", urls(), cloud.subscribed())
        assertNull(settings.current.gpodderStatus)

        // Elsewhere: A unsubscribed, D subscribed. Here: B unsubscribed, E subscribed.
        cloud.subscribe("https://a.example/feed", false)
        cloud.subscribe("https://d.example/feed")
        runBlocking { repo.deleteFeed(repo.feedDao.byUrl("https://b.example/feed")!!.id) }
        feed("https://e.example/feed", "E")

        sync()
        val expected = setOf("https://c.example/feed", "https://d.example/feed", "https://e.example/feed")
        assertEquals(expected, urls())
        assertEquals(expected, cloud.subscribed())
    }

    @Test
    fun listeningPositionsGoBothWays() {
        val feedId = feed("https://a.example/feed", "A")
        val started = episode(feedId, "started")
        val finished = episode(feedId, "finished")
        val newerHere = episode(feedId, "newer-here")
        val heardHere = episode(feedId, "heard-here")
        runBlocking { repo.enqueue(finished, handOn = false) }
        sync()

        // Heard on another device: one half way through (found by its file), one to the end.
        cloud.act(GpodderAction("https://a.example/feed", "https://cdn.example/started.mp3", null, "PLAY", "2026-11-20T10:00:00", 0, 600, 3600))
        cloud.act(GpodderAction("https://a.example/feed", "https://cdn.example/finished.mp3", "finished", "play", "2026-11-20T10:00:00", 0, 3595, 3600))
        // An older position from elsewhere does not beat a change made here later.
        cloud.act(GpodderAction("https://a.example/feed", "https://cdn.example/newer-here.mp3", "newer-here", "play", "2020-01-01T00:00:00", 0, 10, 3600))
        runBlocking {
            repo.savePlayPosition(newerHere, 1_200_000)
            repo.savePlayPosition(heardHere, 120_000)
        }

        sync()
        runBlocking {
            assertEquals(600_000L, repo.articleDao.byId(started)!!.playPosition)
            assertTrue("heard to the end elsewhere", repo.articleDao.byId(finished)!!.playedAt > 0)
            assertEquals("and so out of the queue", emptyList<Long>(), repo.queueDao.ids())
            assertEquals(1_200_000L, repo.articleDao.byId(newerHere)!!.playPosition)
            assertTrue("all sent", repo.playActionDao.pending().isEmpty())
        }
        // What happened here went up, as AntennaPod writes it.
        val sent = cloud.actions.map { it.second }.filter { it.guid == "heard-here" }.single()
        assertEquals("https://a.example/feed", sent.podcast)
        assertEquals("https://cdn.example/heard-here.mp3", sent.episode)
        assertEquals("play", sent.action)
        assertEquals(120L, sent.position)
        assertEquals(3600L, sent.total)
        assertTrue(sent.timestamp!!.matches(Regex("""\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d""")))

        // Marked as heard here: sent as having reached the end.
        runBlocking { repo.setPlayed(started, true) }
        sync()
        assertEquals(3600L, cloud.actions.last().second.position)
        // Its own actions coming back change nothing.
        sync()
        runBlocking { assertTrue(repo.articleDao.byId(started)!!.playedAt > 0) }
    }

    @Test
    fun withoutTheAppOnTheServerTheRestOfTheSyncStillWorks() {
        cloud.gpodderInstalled = false
        feed("https://a.example/feed", "A")
        sync()
        assertEquals("gPodder Sync ist auf dem Nextcloud-Server nicht installiert", settings.current.gpodderStatus)
        assertNull(settings.current.lastSyncError)
        assertTrue("WebDAV part done", cloud.files.keys.any { it.endsWith("news-sync.json") })

        // Switched off: not asked any more.
        settings.update { it.copy(gpodderSync = false, gpodderStatus = null) }
        sync()
        assertNull(settings.current.gpodderStatus)
    }

    @Test
    fun theAppIsFoundBelowTheServerAddress() {
        assertEquals("https://cloud.example/index.php/apps/gpoddersync", GpodderClient.baseUrl("cloud.example").toString())
        assertEquals(
            "https://cloud.example/nc/index.php/apps/gpoddersync",
            GpodderClient.baseUrl("https://cloud.example/nc/remote.php/dav/files/anna/").toString(),
        )
        assertEquals(0L, GpodderSync.parseTime("kaputt"))
        val ms = GpodderSync.parseTime("2026-11-20T10:00:00")
        assertEquals("2026-11-20T10:00:00", GpodderSync.formatTime(ms))
        assertEquals(ms, GpodderSync.parseTime("2026-11-20T10:00:00Z"))
    }
}
