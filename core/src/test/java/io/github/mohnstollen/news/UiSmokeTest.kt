package io.github.mohnstollen.news

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.testing.WorkManagerTestInitHelper
import io.github.mohnstollen.news.data.Article
import io.github.mohnstollen.news.data.Feed
import io.github.mohnstollen.news.ui.MainActivity
import io.github.mohnstollen.news.ui.container
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The real application; only WorkManager is replaced by its test double. */
/** Everything switched on (podcasts included) with the wording of News, as most tests expect. */
open class UiTestApp : NewsApp() {
    override val features: AppFeatures = AppFeatures.NEWS.copy(podcasts = true)

    override fun onCreate() {
        WorkManagerTestInitHelper.initializeTestWorkManager(this, Configuration.Builder().build())
        FakeAndroidKeyStore.install()
        super.onCreate()
    }
}

/** As the News app ships: no podcast parts. */
class ReaderTestApp : UiTestApp() {
    override val features = AppFeatures.NEWS
}

/** As the Podfang app ships. */
class PodfangTestApp : UiTestApp() {
    override val features = AppFeatures.PODFANG
}

/**
 * Drives the screens with the real application class and database. One test per class, each in
 * its own JVM (see forkEvery in the build file): two Compose activities in one Robolectric
 * process interfere with each other.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = UiTestApp::class, sdk = [34])
// Real text measurement; the legacy mode lays text out one pixel per character.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
abstract class UiTestBase {
    protected val context: Context = ApplicationProvider.getApplicationContext()
    protected val repo get() = context.container.repository

    /**
     * Robolectric answers bindService with a connection that has no component name, which the
     * player connection cannot handle. By default the playback service is unreachable.
     */
    protected open fun setUpPlaybackService() {
        val name = android.content.ComponentName(context, io.github.mohnstollen.news.playback.PlaybackService::class.java)
        org.robolectric.Shadows.shadowOf(context as android.app.Application).declareComponentUnbindable(name)
    }

    private val seed = object : ExternalResource() {
        override fun before() = runBlocking {
            setUpPlaybackService()
            val now = System.currentTimeMillis()
            val feedId = repo.feedDao.insert(Feed(url = "https://example.org/feed", title = "Beispielfeed", updatedAt = now))
            repo.articleDao.insertAll(
                (1..30).map {
                    Article(
                        feedId = feedId, guid = "g$it", title = "Artikel Nummer $it", link = "https://example.org/$it",
                        summary = "Teaser $it", content = "<p>Inhalt $it</p>", pubDate = now - it * 60_000L, fetchedAt = now,
                    )
                },
            )
            Unit
        }
    }
    protected val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(seed).around(compose)

    protected fun article(n: Int) = runBlocking {
        repo.articleDao.observeList(null, false).first().first { it.title == "Artikel Nummer $n" }
    }

    /** Scrolls the open drawer to an entry and taps it; the drawer is longer than the test screen. */
    protected fun openFromDrawer(text: String) {
        compose.onNode(hasScrollToNodeAction() and hasAnyDescendant(hasText(context.container.features.allItems)))
            .performScrollToNode(hasText(text))
        compose.onNodeWithText(text).performClick()
    }

    protected fun waitFor(text: String) = compose.waitUntil(5_000) {
        compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
    }

    protected fun unreadCount() = runBlocking { repo.articleDao.observeUnreadCount().first() }
}

class ScrollMarksReadTest : UiTestBase() {
    @Test(timeout = 120000)
    fun scrollingPastArticlesMarksThemRead() {
        waitFor("Artikel Nummer 1")
        assertEquals(30, unreadCount())
        compose.onRoot().performTouchInput { swipeUp(startY = bottom * 0.8f, endY = bottom * 0.2f, durationMillis = 400) }
        compose.waitForIdle()
        compose.waitUntil(5_000) { article(1).read }
        val unreadAfterScroll = unreadCount()
        assertTrue("articles further down stay unread, but $unreadAfterScroll are", unreadAfterScroll in 15..28)
        assertTrue(!article(30).read)

        // Scrolling back up marks nothing.
        compose.onRoot().performTouchInput { swipeDown(startY = bottom * 0.4f, endY = bottom * 0.6f, durationMillis = 400) }
        compose.waitForIdle()
        assertEquals(unreadAfterScroll, unreadCount())

        // With the option off, scrolling marks nothing either.
        context.container.settings.update { it.copy(markReadOnScroll = false) }
        compose.waitForIdle()
        compose.onRoot().performTouchInput { swipeUp(startY = bottom * 0.8f, endY = bottom * 0.2f, durationMillis = 400) }
        compose.waitForIdle()
        assertEquals(unreadAfterScroll, unreadCount())
    }
}

class QuickFilterUiTest : UiTestBase() {
    private fun gone(text: String) = compose.waitUntil(5_000) {
        compose.onAllNodesWithText(text).fetchSemanticsNodes().isEmpty()
    }

    /** The folder name also appears as a heading in the drawer; this finds the chip or menu entry. */
    private fun clickable(text: String) = compose.onNode(hasText(text) and hasClickAction())

    @Test(timeout = 120000)
    fun chipsNarrowTheListAndFoldersAreOfferedWhenAddingAFeed() {
        waitFor("Artikel Nummer 1")
        // Without folders there is nothing to choose from.
        assertTrue(compose.onAllNodesWithText("Ordner").fetchSemanticsNodes().isEmpty())

        val now = System.currentTimeMillis()
        runBlocking {
            val feedId = repo.feedDao.insert(
                Feed(url = "https://example.org/technik", title = "Technikfeed", category = "Technik", updatedAt = now),
            )
            fun article(guid: String, title: String, age: Long, read: Boolean) = Article(
                feedId = feedId, guid = guid, title = title, pubDate = now - age, fetchedAt = now, read = read,
                readAt = if (read) now else 0,
            )
            repo.articleDao.insertAll(
                listOf(
                    article("t1", "Technik frisch", 30 * 60_000L, read = false),
                    article("t2", "Technik gelesen", 40 * 60_000L, read = true),
                    article("t3", "Technik von gestern", 26 * 3_600_000L, read = false),
                ),
            )
        }

        // Folder.
        waitFor("Ordner")
        compose.onNodeWithText("Ordner").performClick()
        clickable("Technik").performClick()
        gone("Artikel Nummer 1")
        compose.onNodeWithText("Technik frisch").assertIsDisplayed()
        compose.onNodeWithText("Technik gelesen").assertIsDisplayed()
        compose.onNodeWithText("Technik von gestern").assertIsDisplayed()

        // Unread only, within that folder.
        compose.onNodeWithText("Ungelesen").performClick()
        gone("Technik gelesen")
        compose.onNodeWithText("Technik von gestern").assertIsDisplayed()

        // Only the last three hours.
        compose.onNodeWithText("Neu (3 Std.)").performClick()
        gone("Technik von gestern")
        compose.onNodeWithText("Technik frisch").assertIsDisplayed()

        // "Mark all read" is limited to what the filters show.
        compose.onNodeWithContentDescription("Alle als gelesen markieren").performClick()
        compose.onNodeWithText("Markieren").performClick()
        compose.waitUntil(5_000) { unreadCount() == 31 }
        waitFor("Keine Artikel für diese Filter.")

        // Back to everything.
        compose.onNodeWithText("Neu (3 Std.)").performClick()
        compose.onNodeWithText("Ungelesen").performClick()
        clickable("Technik").performClick()
        compose.onNodeWithText("Alle Ordner").performClick()
        waitFor("Artikel Nummer 1")
        assertEquals(31, unreadCount())

        // The folder is offered when adding a feed.
        compose.onNodeWithContentDescription("Feeds anzeigen").performClick()
        openFromDrawer("Feeds verwalten")
        waitFor("https://example.org/technik")
        compose.onNodeWithText("Feed hinzufügen", useUnmergedTree = true).performClick()
        waitFor("Auswählen oder neuen Namen eingeben")
        compose.onNodeWithText("Ordner (optional)").performClick()
        waitFor("Kein Ordner")
        clickable("Technik").performClick()
        gone("Kein Ordner")
        compose.onNode(hasText("Technik") and hasSetTextAction()).assertIsDisplayed()
    }
}

class HideReadUiTest : UiTestBase() {
    private fun hideAfter(minutes: Int) {
        context.container.settings.update { it.copy(hideReadAfterMinutes = minutes) }
        compose.waitForIdle()
    }

    @Test(timeout = 120000)
    fun readArticlesLeaveTheListAsSetInTheSettings() {
        waitFor("Artikel Nummer 1")
        assertEquals("a day by default", 24 * 60, context.container.settings.current.hideReadAfterMinutes)

        // Read just now: still listed with the default.
        compose.onNodeWithText("Artikel Nummer 1").performClick()
        compose.waitUntil(5_000) { article(1).read }
        assertTrue(article(1).readAt > 0)
        compose.onNodeWithContentDescription("Zurück").performClick()
        waitFor("Artikel Nummer 1")

        // "At once": gone from the list, the unread ones stay.
        hideAfter(0)
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Artikel Nummer 1").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("Artikel Nummer 2").assertIsDisplayed()

        // The search still finds it.
        compose.onNodeWithContentDescription("Suchen").performClick()
        compose.onNodeWithText("Alle Artikel durchsuchen").performTextInput("nummer 1")
        waitFor("Artikel Nummer 1")
        compose.onNodeWithContentDescription("Suche schließen").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Artikel Nummer 1").fetchSemanticsNodes().isEmpty() }

        // Marking it unread brings it back; "never" shows read articles again.
        runBlocking { repo.setRead(listOf(article(1).id), false) }
        waitFor("Artikel Nummer 1")
        assertEquals(0L, article(1).readAt)
        runBlocking { repo.setRead(listOf(article(1).id), true) }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Artikel Nummer 1").fetchSemanticsNodes().isEmpty() }
        hideAfter(io.github.mohnstollen.news.data.ReadVisibility.NEVER)
        waitFor("Artikel Nummer 1")

        // The setting is on the settings screen.
        compose.onNodeWithContentDescription("Feeds anzeigen").performClick()
        openFromDrawer("Einstellungen")
        waitFor("Gelesene Artikel ausblenden")
    }
}

class QrLoginUiTest : UiTestBase() {
    @Test(timeout = 120000)
    fun pastedLoginCodeFillsInAndSyncs() {
        val server = okhttp3.mockwebserver.MockWebServer()
        val requests = java.util.concurrent.CopyOnWriteArrayList<String>()
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): okhttp3.mockwebserver.MockResponse {
                val auth = request.getHeader("Authorization")
                requests += "${request.method} ${request.requestUrl!!.encodedPath} ${auth == okhttp3.Credentials.basic("erika@example.org", "AbCdE-fGhIj")}"
                if (auth != okhttp3.Credentials.basic("erika@example.org", "AbCdE-fGhIj")) {
                    return okhttp3.mockwebserver.MockResponse().setResponseCode(401)
                }
                return when {
                    request.requestUrl!!.encodedPath == "/ocs/v2.php/cloud/user" ->
                        okhttp3.mockwebserver.MockResponse().setBody("{\"ocs\":{\"data\":{\"id\":\"erika\"}}}")
                    request.method == "GET" -> okhttp3.mockwebserver.MockResponse().setResponseCode(404)
                    else -> okhttp3.mockwebserver.MockResponse().setResponseCode(201)
                }
            }
        }
        server.start()
        try {
            waitFor("Artikel Nummer 1")
            compose.onNodeWithContentDescription("Feeds anzeigen").performClick()
            openFromDrawer("Einstellungen")
            waitFor("Mit QR-Code anmelden")

            val address = java.net.URLEncoder.encode(server.url("/").toString().trimEnd('/'), "UTF-8")
            compose.onNodeWithText("Server-Adresse").performScrollTo()
                .performTextInput("nc://login/user:erika%40example.org&password:AbCdE-fGhIj&server:$address")

            waitFor("Synchronisiert.")
            val settings = context.container.settings
            assertEquals(server.url("/").toString().trimEnd('/'), settings.current.ncServer)
            assertEquals("erika@example.org", settings.current.ncUser)
            assertEquals("AbCdE-fGhIj", settings.ncPassword)
            // The fields show the data, not the pasted code.
            compose.onNodeWithText("erika@example.org").assertExists()
            assertTrue(requests.toString(), "PUT /remote.php/dav/files/erika/News/news-sync.json true" in requests)
            assertTrue(requests.toString(), requests.all { it.endsWith("true") })
        } finally {
            server.shutdown()
        }
    }
}

class LoginFlowUiTest : UiTestBase() {
    @Test(timeout = 120000)
    fun signInOnTheNextcloudPageThenSync() {
        val server = okhttp3.mockwebserver.MockWebServer()
        val credentials = okhttp3.Credentials.basic("erika@example.org", "AbCdE-fGhIj")
        val granted = java.util.concurrent.atomic.AtomicBoolean(false)
        val davPaths = java.util.concurrent.CopyOnWriteArrayList<String>()
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): okhttp3.mockwebserver.MockResponse {
                val path = request.requestUrl!!.encodedPath
                val ok = okhttp3.mockwebserver.MockResponse()
                return when {
                    path == "/index.php/login/v2" -> ok.setBody(
                        "{\"poll\":{\"token\":\"t\",\"endpoint\":\"${server.url("/login/v2/poll")}\"}," +
                            "\"login\":\"${server.url("/login/v2/flow/xyz")}\"}",
                    )
                    path == "/login/v2/poll" ->
                        if (!granted.get()) ok.setResponseCode(404)
                        else ok.setBody(
                            "{\"server\":\"${server.url("/").toString().trimEnd('/')}\"," +
                                "\"loginName\":\"erika@example.org\",\"appPassword\":\"AbCdE-fGhIj\"}",
                        )
                    request.getHeader("Authorization") != credentials -> ok.setResponseCode(401)
                    path == "/ocs/v2.php/cloud/user" -> ok.setBody("{\"ocs\":{\"data\":{\"id\":\"erika\"}}}")
                    request.method == "GET" -> ok.setResponseCode(404)
                    else -> ok.setResponseCode(201).also { davPaths += "${request.method} $path" }
                }
            }
        }
        server.start()
        try {
            waitFor("Artikel Nummer 1")
            compose.onNodeWithContentDescription("Feeds anzeigen").performClick()
            openFromDrawer("Einstellungen")
            waitFor("Mit Nextcloud anmelden")
            compose.onNodeWithText("Server-Adresse").performScrollTo().performTextInput(server.url("/").toString())
            compose.onNodeWithText("Mit Nextcloud anmelden").performScrollTo().performClick()

            // The login page opens; the app waits.
            waitFor("Zugriff gewähren")
            // The page is opened right after the text appears; wait for it rather than look at once.
            compose.waitUntil(5_000) { org.robolectric.Shadows.shadowOf(compose.activity).peekNextStartedActivity() != null }
            val opened = org.robolectric.Shadows.shadowOf(compose.activity).nextStartedActivity
            assertEquals(android.content.Intent.ACTION_VIEW, opened.action)
            assertEquals(server.url("/login/v2/flow/xyz").toString(), opened.dataString)
            compose.onNodeWithText("Seite erneut öffnen").assertExists()

            // The user grants access in the browser.
            granted.set(true)
            compose.waitUntil(15_000) {
                compose.onAllNodesWithText("Synchronisiert.").fetchSemanticsNodes().isNotEmpty()
            }
            val settings = context.container.settings
            assertEquals("erika@example.org", settings.current.ncUser)
            assertEquals("AbCdE-fGhIj", settings.ncPassword)
            compose.onNodeWithText("erika@example.org").assertExists()
            assertTrue(davPaths.toString(), "PUT /remote.php/dav/files/erika/News/news-sync.json" in davPaths)
            assertTrue(compose.onAllNodesWithText("Zugriff gewähren", substring = true).fetchSemanticsNodes().isEmpty())
        } finally {
            server.shutdown()
        }
    }
}

class SearchUiTest : UiTestBase() {
    @Test(timeout = 120000)
    fun searchFiltersTheListAndClosingRestoresIt() {
        waitFor("Artikel Nummer 1")
        compose.onNodeWithContentDescription("Suchen").performClick()
        compose.onNodeWithText("Alle Artikel durchsuchen").performTextInput("nummer 17")
        waitFor("Artikel Nummer 17")
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Artikel Nummer 1").fetchSemanticsNodes().isEmpty() }

        // Scrolling through hits marks nothing; opening one works as usual.
        assertEquals(30, unreadCount())
        compose.onNodeWithText("Artikel Nummer 17").performClick()
        compose.waitUntil(5_000) { article(17).read }
        compose.onNodeWithContentDescription("Zurück").performClick()
        waitFor("Artikel Nummer 17")

        compose.onNodeWithContentDescription("Eingabe löschen").performClick()
        compose.onNodeWithText("Alle Artikel durchsuchen").performTextInput("gibtesnicht")
        waitFor("Keine Treffer für „gibtesnicht“.")

        compose.onNodeWithContentDescription("Suche schließen").performClick()
        waitFor("Artikel Nummer 1")
        compose.onNodeWithContentDescription("Suchen").assertIsDisplayed()
    }
}

class UiSmokeTest : UiTestBase() {
    @Test(timeout = 120000)
    fun listStarReaderNoteAndScreens() {
        waitFor("Artikel Nummer 1")
        // Once as the screen title, once in the (closed) drawer.
        assertEquals(2, compose.onAllNodesWithText("Alle Artikel").fetchSemanticsNodes().size)

        // Star from the list.
        compose.onAllNodesWithContentDescriptionCompat("Merken")[0].performClick()
        compose.waitUntil(5_000) { article(1).starred }

        // Long press toggles read.
        compose.onNodeWithText("Artikel Nummer 2").performTouchInput { longClick() }
        compose.waitUntil(5_000) { article(2).read }

        // Opening an article marks it read; add a note there.
        compose.onNodeWithText("Artikel Nummer 3").performClick()
        compose.waitUntil(5_000) { article(3).read }
        compose.onNodeWithContentDescription("Notiz hinzufügen").performClick()
        compose.onNodeWithText("Notiz zu diesem Artikel").performTextInput("Meine Notiz")
        compose.onNodeWithText("Speichern").performClick()
        compose.waitUntil(5_000) { article(3).hasNote }
        compose.onNodeWithContentDescription("Teilen").assertIsDisplayed()
        compose.onNodeWithContentDescription("Volltext laden").assertIsDisplayed()
        compose.onNodeWithContentDescription("Als ungelesen markieren").performClick()
        compose.waitUntil(5_000) { !article(3).read }
        waitFor("Alle Artikel")

        // Mark everything read, with confirmation.
        compose.onNodeWithContentDescription("Alle als gelesen markieren").performClick()
        compose.onNodeWithText("Markieren").performClick()
        compose.waitUntil(5_000) { unreadCount() == 0 }

        // Drawer: single feed, starred list, then the other screens.
        compose.onNodeWithContentDescription("Feeds anzeigen").performClick()
        compose.onNodeWithText("Gemerkt").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Artikel Nummer 2").fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithText("Artikel Nummer 1").assertIsDisplayed()

        compose.onNodeWithContentDescription("Feeds anzeigen").performClick()
        waitFor("Gemerkt")
        openFromDrawer("Feeds verwalten")
        waitFor("https://example.org/feed")
        compose.onNodeWithText("Feed hinzufügen", useUnmergedTree = true).performClick()
        waitFor("Adresse des Feeds oder der Website")
        compose.onNodeWithText("Abbrechen").performClick()
        compose.onNodeWithText("https://example.org/feed").performClick()
        waitFor("Feed bearbeiten")
        compose.onNodeWithText("Abbrechen").performClick()
        compose.onNodeWithContentDescription("Zurück").performClick()

        waitFor("Gemerkt")
        compose.onNodeWithContentDescription("Feeds anzeigen").performClick()
        waitFor("Gemerkt")
        openFromDrawer("Einstellungen")
        waitFor("Intervall in Minuten")
        compose.onNodeWithText("Nextcloud-Synchronisation").assertExists()
        assertEquals(60, context.container.settings.current.intervalMinutes)
        assertTrue(context.container.settings.current.autoUpdate)
    }
}

private fun androidx.compose.ui.test.junit4.ComposeTestRule.onAllNodesWithContentDescriptionCompat(label: String) =
    onAllNodes(androidx.compose.ui.test.hasContentDescription(label))

class EpisodeUiTest : UiTestBase() {
    private val server = okhttp3.mockwebserver.MockWebServer()
    private lateinit var service: io.github.mohnstollen.news.playback.PlaybackService

    /** Like waitUntil, but gives the player's threads real time between checks. */
    private fun eventually(what: String, millis: Long = 10_000, done: () -> Boolean) {
        val end = System.currentTimeMillis() + millis
        while (true) {
            compose.waitForIdle()
            if (done()) return
            // The player's own threads schedule by the clock, which Robolectric only moves on request.
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(100))
            if (System.currentTimeMillis() > end) throw AssertionError("timed out waiting for $what")
            Thread.sleep(50)
        }
    }

    /** The real service, reached through its binder as on a device. */
    override fun setUpPlaybackService() {
        val name = android.content.ComponentName(context, io.github.mohnstollen.news.playback.PlaybackService::class.java)
        service = org.robolectric.Robolectric.buildService(io.github.mohnstollen.news.playback.PlaybackService::class.java)
            .create().get()
        val binder = service.onBind(
            android.content.Intent(androidx.media3.session.MediaSessionService.SERVICE_INTERFACE).setComponent(name),
        )
        org.robolectric.Shadows.shadowOf(context as android.app.Application).setComponentNameAndServiceForBindService(name, binder)
    }

    @Test(timeout = 120000)
    fun episodesPlayFromTheListAndCanBeMarkedPlayed() {
        // The audio address answers 404, so the player has to report a problem.
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest) =
                okhttp3.mockwebserver.MockResponse().setResponseCode(404)
        }
        server.start()
        val id = runBlocking {
            val feedId = repo.feedDao.all().first().id
            repo.articleDao.insertAll(
                listOf(
                    Article(
                        feedId = feedId, guid = "ep1", title = "Folge Eins", summary = "Shownotes",
                        content = "<p>Shownotes</p>", pubDate = System.currentTimeMillis(), fetchedAt = 0,
                        enclosureUrl = server.url("/ep1.mp3").toString(), enclosureType = "audio/mpeg",
                        durationSeconds = 1800,
                    ),
                ),
            ).single()
        }
        waitFor("Folge Eins")
        compose.onNodeWithText("30 Min").assertIsDisplayed()

        try {
            compose.onAllNodesWithContentDescription("Abspielen")[0].performClick()
            eventually("episode loaded") { service.sessions.single().player.currentMediaItem?.mediaId == id.toString() }
            // The mini player shows the episode, then the failed download.
            eventually("mini player") { compose.onAllNodesWithText("Folge Eins").fetchSemanticsNodes().size == 2 }
            eventually("error", 20_000) {
                compose.onAllNodesWithText("Die Audiodatei ist nicht (mehr) abrufbar").fetchSemanticsNodes().isNotEmpty()
            }

            // Full player: speed is remembered, "Beenden" unloads the episode.
            compose.onNodeWithContentDescription("10 Sekunden zurück").assertIsDisplayed()
            compose.onNode(hasClickAction() and hasText("Die Audiodatei ist nicht (mehr) abrufbar"), useUnmergedTree = false)
                .performClick()
            waitFor("Geschwindigkeit")
            compose.onNodeWithText("1,5×").performScrollTo().performClick()
            eventually("speed") { context.container.settings.current.playbackSpeed == 1.5f }
            compose.onNodeWithText("Beenden").performScrollTo().performClick()
            eventually("mini player gone") { compose.onAllNodesWithText("Folge Eins").fetchSemanticsNodes().size == 1 }

            // In the reader: mark as played, which also counts as read.
            compose.onNodeWithText("Folge Eins").performClick()
            waitFor("Abspielen")
            // Speed right in the episode panel: shows the current one, a tap takes the next.
            compose.onNodeWithText("1,5×").performClick()
            eventually("next speed") { context.container.settings.current.playbackSpeed == 1.8f }
            compose.onNodeWithContentDescription("Herunterladen").assertIsDisplayed()
            compose.onNodeWithContentDescription("Als gehört markieren").performClick()
            compose.waitUntil(5_000) { runBlocking { repo.articleDao.byId(id)!!.playedAt } > 0 }
            assertTrue(runBlocking { repo.articleDao.byId(id)!!.read })
            compose.onNodeWithContentDescription("Als nicht gehört markieren").assertIsDisplayed()
            compose.onNodeWithContentDescription("Zurück").performClick()
            waitFor("Gehört")
        } finally {
            service.onDestroy()
            server.shutdown()
        }
    }
}

class ArchiveUiTest : UiTestBase() {
    private fun archivedAt(n: Int) = runBlocking {
        repo.articleDao.byGuid(repo.feedDao.all().first().id, "g$n")!!.archivedAt
    }

    @Test(timeout = 120000)
    fun swipingArchivesWithUndoAndTheArchiveListsThem() {
        waitFor("Artikel Nummer 1")
        compose.onNodeWithText("Artikel Nummer 1").performTouchInput { swipeLeft() }
        compose.waitUntil(5_000) { archivedAt(1) > 0 }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Artikel Nummer 1").fetchSemanticsNodes().isEmpty() }

        // Undo brings it back.
        compose.onNodeWithText("Rückgängig").performClick()
        compose.waitUntil(5_000) { archivedAt(1) == 0L }
        waitFor("Artikel Nummer 1")
        // Back in place, not left swiped away.
        compose.onNodeWithText("Artikel Nummer 1").assertIsDisplayed()

        compose.onNodeWithText("Artikel Nummer 2").performTouchInput { swipeLeft() }
        compose.waitUntil(5_000) { archivedAt(2) > 0 }

        // The archive in the drawer has it; swiping there brings it back.
        compose.onNodeWithContentDescription("Feeds anzeigen").performClick()
        compose.onNodeWithText("Archiv").performClick()
        waitFor("Artikel Nummer 2")
        // Let the drawer finish closing, or the swipe would go to it.
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()
        assertEquals(1, compose.onAllNodesWithText("Artikel Nummer", substring = true).fetchSemanticsNodes().size)
        compose.onNodeWithText("Artikel Nummer 2").performTouchInput { swipeLeft() }
        compose.waitUntil(5_000) { archivedAt(2) == 0L }
        waitFor("Das Archiv ist leer.")

        // From the reader: archiving goes back to the list.
        compose.onNodeWithContentDescription("Feeds anzeigen").performClick()
        compose.onNodeWithText("Alle Artikel").performClick()
        compose.onNodeWithText("Artikel Nummer 3").performClick()
        compose.onNodeWithContentDescription("Archivieren").performClick()
        compose.waitUntil(5_000) { archivedAt(3) > 0 }
        waitFor("Artikel Nummer 4")
        assertTrue(compose.onAllNodesWithText("Artikel Nummer 3").fetchSemanticsNodes().isEmpty())
    }
}

class QueueAndStatsUiTest : UiTestBase() {
    @Test(timeout = 120000)
    fun episodesGoIntoTheQueueAndTheStatisticsShowTheFavourite() {
        val (one, two) = runBlocking {
            val feedId = repo.feedDao.all().first().id
            val now = System.currentTimeMillis()
            fun episode(n: Int) = Article(
                feedId = feedId, guid = "ep$n", title = "Episode $n", pubDate = now + n, fetchedAt = 0,
                enclosureUrl = "https://example.org/ep$n.mp3", durationSeconds = 600,
            )
            val ids = repo.articleDao.insertAll(listOf(episode(1), episode(2)))
            // Listening history: two podcasts, one clearly ahead.
            val today = java.time.LocalDate.now().toEpochDay()
            repo.listenDao.insert(io.github.mohnstollen.news.data.ListenEntry(today, ids[0], "https://a", "Lieblingspodcast", 3 * 3_600_000L, 4 * 3_600_000L, 2))
            repo.listenDao.insert(io.github.mohnstollen.news.data.ListenEntry(today - 1, ids[1], "https://b", "Anderer Podcast", 1_800_000L, 1_800_000L, 0))
            ids[0] to ids[1]
        }
        waitFor("Episode 2")

        // Swipe right: into the queue. Plain articles cannot be queued.
        compose.onNodeWithText("Episode 2").performTouchInput { swipeRight() }
        compose.waitUntil(5_000) { runBlocking { repo.queueDao.ids() } == listOf(two) }
        waitFor("in der Warteschlange")
        compose.onNodeWithText("Episode 1").performTouchInput { swipeRight() }
        compose.waitUntil(5_000) { runBlocking { repo.queueDao.ids() } == listOf(two, one) }

        // The queue screen: reorder and remove.
        compose.onNodeWithContentDescription("Feeds anzeigen").performClick()
        compose.onNodeWithText("Warteschlange").performClick()
        waitFor("Leeren")
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Nach oben").fetchSemanticsNodes().size == 2 }
        compose.waitForIdle()
        compose.onAllNodesWithContentDescription("Nach oben")[1].performClick()
        compose.waitUntil(10_000) { runBlocking { repo.queueDao.ids() } == listOf(one, two) }
        // The list follows the database a moment later; tap only once it shows the new order.
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("Episode ", substring = true).fetchSemanticsNodes()
                .mapNotNull { node ->
                    // The row's texts start with the cover's letter; the title follows.
                    node.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text)
                        ?.map { it.text }?.firstOrNull { it.startsWith("Episode ") }
                } == listOf("Episode 1", "Episode 2")
        }
        compose.waitForIdle()
        // Swiping left takes it out of the queue.
        compose.onNodeWithText("Episode 1").performTouchInput { swipeLeft() }
        compose.waitUntil(10_000) { runBlocking { repo.queueDao.ids() } == listOf(two) }

        // In the reader, the queue button shows that it is queued and takes it out.
        compose.onNodeWithText("Episode 2").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithContentDescription("Aus der Warteschlange nehmen").fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Aus der Warteschlange nehmen").performClick()
        compose.waitUntil(10_000) { runBlocking { repo.queueDao.ids() }.isEmpty() }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithContentDescription("Zur Warteschlange hinzufügen").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("Zurück").performClick()
        compose.onNodeWithContentDescription("Zurück").performClick()

        // Statistics.
        compose.onNodeWithContentDescription("Feeds anzeigen").performClick()
        openFromDrawer("Statistik")
        waitFor("Dein Lieblingspodcast dieses Jahr")
        assertEquals("card and top list", 2, compose.onAllNodesWithText("Lieblingspodcast").fetchSemanticsNodes().size)
        waitFor("3 Std 30 Min")
        compose.onNodeWithText("Meistgehört dieses Jahr").performScrollTo()
        compose.onNodeWithText("Durch höhere Geschwindigkeit gespart").performScrollTo().assertIsDisplayed()
    }
}

@Config(application = ReaderTestApp::class, sdk = [34])
class ReaderModeUiTest : UiTestBase() {
    @Test(timeout = 120000)
    fun theReaderShowsNoPodcastParts() {
        runBlocking {
            repo.articleDao.insertAll(
                listOf(
                    Article(
                        feedId = repo.feedDao.all().first().id, guid = "ep", title = "Eine Podcastfolge",
                        summary = "Shownotes", content = "<p>Shownotes</p>", pubDate = System.currentTimeMillis(),
                        fetchedAt = 0, enclosureUrl = "https://example.org/ep.mp3", durationSeconds = 1800,
                    ),
                ),
            )
        }
        waitFor("Eine Podcastfolge")
        assertTrue(compose.onAllNodesWithContentDescription("Abspielen").fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodesWithText("30 Min").fetchSemanticsNodes().isEmpty())

        // Opening it shows the text, without player, download or queue buttons.
        compose.onNodeWithText("Eine Podcastfolge").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Archivieren").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(compose.onAllNodesWithText("Abspielen").fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodesWithContentDescription("Herunterladen").fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodesWithContentDescription("Zur Warteschlange hinzufügen").fetchSemanticsNodes().isEmpty())
        compose.onNodeWithContentDescription("Zurück").performClick()

        // The menu has no podcast entries; the settings no podcast section.
        compose.onNodeWithContentDescription("Feeds anzeigen").performClick()
        waitFor("Gemerkt")
        compose.onNodeWithText("News").assertIsDisplayed()
        for (entry in listOf("Warteschlange", "Statistik", "Podcasts entdecken")) {
            assertTrue(entry, compose.onAllNodesWithText(entry).fetchSemanticsNodes().isEmpty())
        }
        openFromDrawer("Einstellungen")
        waitFor("Intervall in Minuten")
        assertTrue(compose.onAllNodesWithText("Abspann überspringen").fetchSemanticsNodes().isEmpty())
        assertEquals("News", context.container.settings.current.ncFolder)
    }
}

// A common phone size, so the screenshots show what a phone shows.
@Config(application = PodfangTestApp::class, sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class PodfangModeUiTest : UiTestBase() {
    /** For looking at the layout: written to the build folder. */
    private fun screenshot(name: String) {
        compose.waitForIdle()
        val view = compose.activity.window.decorView
        val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { view.draw(android.graphics.Canvas(bitmap)) }
        val dir = java.io.File("build/screenshots").apply { mkdirs() }
        java.io.FileOutputStream(java.io.File(dir, "$name.png")).use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test(timeout = 120000)
    fun podfangSortsNewEpisodesIntoTheQueueAndShowsPodcasts() {
        val (fresh, loaded) = runBlocking {
            val feedId = repo.feedDao.all().first().id
            val file = java.io.File(context.filesDir, "geladen.mp3").apply { writeText("x") }
            val now = System.currentTimeMillis()
            val ids = repo.articleDao.insertAll(
                listOf(
                    Article(
                        feedId = feedId, guid = "neu", title = "Neue Folge", pubDate = now, fetchedAt = 0,
                        enclosureUrl = "https://example.org/neu.mp3", durationSeconds = 1800,
                    ),
                    Article(
                        feedId = feedId, guid = "dl", title = "Geladene Folge", pubDate = now - 1, fetchedAt = 0,
                        enclosureUrl = "https://example.org/dl.mp3", durationSeconds = 1800, downloadPath = file.path,
                    ),
                ),
            )
            repo.enqueue(ids[1])
            ids[0] to ids[1]
        }
        fun tab(name: String) = compose.onNodeWithContentDescription(name, useUnmergedTree = true).performClick()
        fun queue() = runBlocking { repo.queueDao.ids() }

        // "Neu": what is not sorted yet. The queued episode is not here; there is no side menu.
        waitFor("Neue Folge")
        assertTrue(compose.onAllNodesWithText("Geladene Folge").fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodesWithContentDescription("Podcasts anzeigen").fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodesWithText("Alle Artikel").fetchSemanticsNodes().isEmpty())
        screenshot("podfang-neu")

        // Downloading it puts it into the queue, so it leaves "Neu".
        compose.onNodeWithContentDescription("Herunterladen").performClick()
        compose.waitUntil(5_000) { queue() == listOf(loaded, fresh) }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Neue Folge").fetchSemanticsNodes().isEmpty() }

        // Queue: both, with the time left; swiping right moves one to the end ("later").
        tab("Warteschlange")
        waitFor("2 Folgen · 1:00 Std verbleibend")
        screenshot("podfang-warteschlange")
        compose.onNodeWithText("Geladene Folge").performTouchInput { swipeRight() }
        compose.waitUntil(5_000) { queue() == listOf(fresh, loaded) }

        // Podcasts: the grid, then one podcast's overview.
        tab("Podcasts")
        waitFor("Beispielfeed")
        screenshot("podfang-podcasts")
        compose.onNodeWithText("Beispielfeed").performClick()
        waitFor("32 Folgen")
        compose.onNodeWithContentDescription("Podcast abbestellen").assertIsDisplayed()
        assertTrue("no tabs on a podcast", compose.onAllNodesWithContentDescription("Warteschlange", useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        screenshot("podfang-podcast")

        // From an episode back to its podcast.
        compose.onNodeWithText("Neue Folge").performClick()
        waitFor("Alle Folgen des Podcasts")
        compose.onNodeWithText("Alle Folgen des Podcasts").performClick()
        waitFor("32 Folgen")
        compose.onNodeWithContentDescription("Zurück").performClick()
        compose.onNodeWithContentDescription("Zurück").performClick()
        compose.onNodeWithContentDescription("Zurück").performClick()

        // "Mehr": settings with the new switches, and the starred episodes.
        compose.waitUntil(5_000) {
            compose.onAllNodesWithContentDescription("Mehr", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        tab("Mehr")
        compose.onNodeWithText("Einstellungen").performClick()
        waitFor("Intervall in Minuten")
        compose.onNodeWithText("Folgen der Warteschlange laden").performScrollTo().assertIsDisplayed()
        assertTrue(compose.onAllNodesWithText("Widget").fetchSemanticsNodes().isEmpty())
        assertEquals("own Nextcloud folder", "Podfang", context.container.settings.current.ncFolder)
        assertEquals("Podfang (Android)", context.container.features.clientName)
        compose.onNodeWithContentDescription("Zurück").performClick()
        tab("Mehr")
        compose.onNodeWithText("Gemerkt").performClick()
        waitFor("Noch keine Folgen gemerkt.")
        tab("Neu")
        waitFor("Artikel Nummer 1")
    }
}
