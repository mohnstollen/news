package io.github.mohnstollen.news

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.mohnstollen.news.data.Article
import io.github.mohnstollen.news.data.Feed
import io.github.mohnstollen.news.ui.MainActivity
import io.github.mohnstollen.news.ui.container
import io.github.mohnstollen.news.widget.NewsWidget
import io.github.mohnstollen.news.widget.WidgetConfigActivity
import io.github.mohnstollen.news.widget.WidgetSource
import io.github.mohnstollen.news.widget.WidgetSources
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Two feeds in the folder "Technik" and one without a folder. */
private fun seedFeeds(context: Context): Long = runBlocking {
    val repo = context.container.repository
    val now = System.currentTimeMillis()
    val heise = repo.feedDao.insert(Feed(url = "https://a.example/feed", title = "Heise", category = "Technik", updatedAt = now))
    val golem = repo.feedDao.insert(Feed(url = "https://b.example/feed", title = "Golem", category = "Technik", updatedAt = now))
    val lokal = repo.feedDao.insert(Feed(url = "https://c.example/feed", title = "Lokalzeitung", updatedAt = now))
    repo.articleDao.insertAll(
        listOf(heise to "Neuer Prozessor", golem to "Neues Telefon", lokal to "Stadtfest").mapIndexed { i, (feed, title) ->
            Article(feedId = feed, guid = title, title = title, pubDate = now - i * 60_000L, fetchedAt = now)
        },
    )
    lokal
}

@RunWith(AndroidJUnit4::class)
@Config(application = UiTestApp::class, sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WidgetConfigTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @get:Rule
    val compose = createEmptyComposeRule()

    private var lokal = 0L

    @Before
    fun seed() {
        lokal = seedFeeds(context)
    }

    private fun launch(widgetId: Int) = ActivityScenario.launchActivityForResult<WidgetConfigActivity>(
        Intent(context, WidgetConfigActivity::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId),
    )

    private fun waitFor(text: String) = compose.waitUntil(5_000) {
        compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }

    @Test
    fun choosingAFolderPlacesTheWidget() {
        val scenario = launch(7)
        waitFor("Ordner")
        compose.onNodeWithText("Alle Feeds").assertExists()
        compose.onNodeWithText("Einzelne Feeds").assertExists()
        compose.onNodeWithText("2 ungelesen").assertExists()
        compose.onNode(hasText("Alle Feeds") and hasClickAction()).assertIsSelected()

        compose.onNode(hasText("Technik") and hasClickAction()).performClick()
        assertEquals(Activity.RESULT_OK, scenario.result.resultCode)
        assertEquals(7, scenario.result.resultData.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1))
        assertEquals(WidgetSource.Folder("Technik"), WidgetSources.get(context, 7))
    }

    @Test
    fun changingTheChoiceShowsTheCurrentOneAndCancellingKeepsIt() {
        WidgetSources.set(context, 3, WidgetSource.Feed(lokal))
        val scenario = launch(3)
        waitFor("Lokalzeitung")
        compose.onNode(hasText("Lokalzeitung") and hasClickAction()).assertIsSelected()
        compose.onNodeWithContentDescription("Abbrechen").performClick()
        assertEquals(Activity.RESULT_CANCELED, scenario.result.resultCode)
        assertEquals(WidgetSource.Feed(lokal), WidgetSources.get(context, 3))
    }

    @Test
    fun withoutAWidgetIdItClosesAtOnce() {
        val scenario = ActivityScenario.launchActivityForResult<WidgetConfigActivity>(
            Intent(context, WidgetConfigActivity::class.java),
        )
        assertEquals(Activity.RESULT_CANCELED, scenario.result.resultCode)
    }
}

@RunWith(AndroidJUnit4::class)
// The widget belongs to News, which has no player to connect to.
@Config(application = ReaderTestApp::class, sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WidgetOpensAppTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @get:Rule
    val compose = createEmptyComposeRule()

    @Test
    fun theHeadingOpensTheAppOnTheWidgetsFolder() {
        seedFeeds(context)
        ActivityScenario.launch<MainActivity>(
            Intent(context, MainActivity::class.java).putExtra(NewsWidget.EXTRA_SOURCE, "folder:Technik"),
        ).use {
            compose.waitUntil(5_000) { compose.onAllNodesWithText("Neuer Prozessor").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Neues Telefon").assertExists()
            compose.waitUntil(5_000) { compose.onAllNodesWithText("Stadtfest").fetchSemanticsNodes().isEmpty() }
            // The folder filter is on, so it can be switched off as usual.
            compose.onNode(hasText("Technik") and hasClickAction()).assertExists()
        }
    }

    @Test
    fun theHeadingOpensTheAppOnTheWidgetsFeed() {
        val lokal = seedFeeds(context)
        ActivityScenario.launch<MainActivity>(
            Intent(context, MainActivity::class.java).putExtra(NewsWidget.EXTRA_SOURCE, "feed:$lokal"),
        ).use {
            compose.waitUntil(5_000) { compose.onAllNodesWithText("Lokalzeitung").fetchSemanticsNodes().isNotEmpty() }
            compose.waitUntil(5_000) { compose.onAllNodesWithText("Neuer Prozessor").fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithText("Stadtfest").assertExists()
        }
    }
}
