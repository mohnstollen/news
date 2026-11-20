package io.github.mohnstollen.news

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.swipeDown
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
class UiTestApp : NewsApp() {
    override fun onCreate() {
        WorkManagerTestInitHelper.initializeTestWorkManager(this, Configuration.Builder().build())
        FakeAndroidKeyStore.install()
        super.onCreate()
    }
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

    private val seed = object : ExternalResource() {
        override fun before() = runBlocking {
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
        compose.onNodeWithText("Feeds verwalten").performClick()
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
        compose.onNodeWithText("Einstellungen").performClick()
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
            compose.onNodeWithText("Einstellungen").performClick()
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
            compose.onNodeWithText("Einstellungen").performClick()
            waitFor("Mit Nextcloud anmelden")
            compose.onNodeWithText("Server-Adresse").performScrollTo().performTextInput(server.url("/").toString())
            compose.onNodeWithText("Mit Nextcloud anmelden").performScrollTo().performClick()

            // The login page opens; the app waits.
            waitFor("Zugriff gewähren")
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
        waitFor("Feeds verwalten")
        compose.onNodeWithText("Feeds verwalten").performClick()
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
        waitFor("Einstellungen")
        compose.onNodeWithText("Einstellungen").performClick()
        waitFor("Intervall in Minuten")
        compose.onNodeWithText("Nextcloud-Synchronisation").assertExists()
        assertEquals(60, context.container.settings.current.intervalMinutes)
        assertTrue(context.container.settings.current.autoUpdate)
    }
}

private fun androidx.compose.ui.test.junit4.ComposeTestRule.onAllNodesWithContentDescriptionCompat(label: String) =
    onAllNodes(androidx.compose.ui.test.hasContentDescription(label))
