package io.github.mohnstollen.news

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
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
        compose.onNodeWithContentDescription("Mehr").performClick()
        compose.onNodeWithText("Volltext laden").assertIsDisplayed()
        compose.onNodeWithText("Als ungelesen markieren").performClick()
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
