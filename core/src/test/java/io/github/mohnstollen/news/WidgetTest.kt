package io.github.mohnstollen.news

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkInfo
import androidx.work.WorkManager
import io.github.mohnstollen.news.data.Article
import io.github.mohnstollen.news.data.Feed
import io.github.mohnstollen.news.ui.container
import io.github.mohnstollen.news.widget.NewsWidget
import io.github.mohnstollen.news.widget.NewsWidgetProvider
import io.github.mohnstollen.news.widget.WidgetConfigActivity
import io.github.mohnstollen.news.widget.WidgetRows
import io.github.mohnstollen.news.widget.WidgetSource
import io.github.mohnstollen.news.widget.WidgetSources
import io.github.mohnstollen.news.work.Notifier
import io.github.mohnstollen.news.work.RefreshWorker
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
@Config(application = UiTestApp::class, sdk = [34])
class WidgetTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val repo get() = context.container.repository
    private val settings get() = context.container.settings
    private val widgets = shadowOf(AppWidgetManager.getInstance(context))

    private fun seed(now: Long) = runBlocking {
        val feedId = repo.feedDao.insert(Feed(url = "https://example.org/feed", title = "Beispielfeed", updatedAt = now))
        repo.feedDao.fetchSucceeded(feedId, now, null, null, null)
        repo.articleDao.insertAll(
            listOf(
                Article(feedId = feedId, guid = "a", title = "Älter", pubDate = now - 3_600_000, fetchedAt = now),
                Article(feedId = feedId, guid = "b", title = "Neuester", pubDate = now - 60_000, fetchedAt = now),
                Article(feedId = feedId, guid = "c", title = "Schon gelesen", pubDate = now, fetchedAt = now, read = true),
            ),
        )
    }

    /** State of the periodic job, or null if there is none (or it was cancelled). */
    private fun job(name: String): WorkInfo? =
        WorkManager.getInstance(context).getWorkInfosForUniqueWork(name).get()
            .firstOrNull { it.state != WorkInfo.State.CANCELLED }

    private fun text(root: android.view.View, id: Int) = root.findViewById<TextView>(id).text.toString()

    @Test
    fun widgetShowsUnreadArticlesNewestFirst() {
        val now = System.currentTimeMillis()
        seed(now)
        val id = widgets.createWidget(NewsWidgetProvider::class.java, R.layout.widget)
        // Placing the widget draws it in the background; let that finish before drawing again here.
        val deadline = System.currentTimeMillis() + 10_000
        while (text(widgets.getViewFor(id), R.id.widget_status).isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
        }
        runBlocking { NewsWidget.updateAll(context) }

        val status = text(widgets.getViewFor(id), R.id.widget_status)
        assertTrue(status, status.startsWith("2 ungelesen · Stand "))

        val rows = WidgetRows(context, id).apply { onDataSetChanged() }
        assertEquals(2, rows.count)
        val first = rows.getViewAt(0)!!.apply(context, FrameLayout(context))
        assertEquals("Neuester", text(first, R.id.widget_item_title))
        assertTrue(text(first, R.id.widget_item_meta).startsWith("Beispielfeed · "))
        assertEquals("Älter", text(rows.getViewAt(1)!!.apply(context, FrameLayout(context)), R.id.widget_item_title))
        assertEquals(rows.getItemId(0), runBlocking { repo.articleDao.newestUnread(1).single().id })

        // Reading everything empties the list.
        runBlocking {
            repo.articleDao.markAllRead(null, false, System.currentTimeMillis())
            NewsWidget.updateAll(context)
        }
        rows.onDataSetChanged()
        assertEquals(0, rows.count)
        assertTrue(text(widgets.getViewFor(id), R.id.widget_status).startsWith("Alles gelesen"))
    }

    /** Places a widget and waits until the drawing that placing it starts in the background is done. */
    private fun place(): Int {
        val id = widgets.createWidget(NewsWidgetProvider::class.java, R.layout.widget)
        val deadline = System.currentTimeMillis() + 10_000
        while (text(widgets.getViewFor(id), R.id.widget_status).isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
        }
        return id
    }

    private fun rowTitles(id: Int) = WidgetRows(context, id).run {
        onDataSetChanged()
        (0 until count).map { text(getViewAt(it)!!.apply(context, FrameLayout(context)), R.id.widget_item_title) }
    }

    @Test
    fun eachWidgetShowsAllFeedsAFolderOrOneFeed() {
        val now = System.currentTimeMillis()
        val ids = runBlocking {
            fun feed(url: String, title: String, category: String?) =
                Feed(url = url, title = title, category = category, updatedAt = now)
            val technik1 = repo.feedDao.insert(feed("https://a.example/feed", "Heise", "Technik"))
            val technik2 = repo.feedDao.insert(feed("https://b.example/feed", "Golem", "Technik"))
            val lokal = repo.feedDao.insert(feed("https://c.example/feed", "Lokalzeitung", null))
            fun article(feedId: Long, title: String, ageMinutes: Long, read: Boolean = false) = Article(
                feedId = feedId, guid = title, title = title, pubDate = now - ageMinutes * 60_000, fetchedAt = now, read = read,
            )
            repo.articleDao.insertAll(
                listOf(
                    article(technik1, "Neuer Prozessor", 1),
                    article(technik2, "Neues Telefon", 2),
                    article(technik2, "Schon gelesen", 3, read = true),
                    article(lokal, "Stadtfest", 4),
                ),
            )
            Triple(technik1, technik2, lokal)
        }

        val all = place()
        val folder = place()
        val single = place()
        WidgetSources.set(context, folder, WidgetSource.Folder("Technik"))
        WidgetSources.set(context, single, WidgetSource.Feed(ids.third))
        runBlocking { NewsWidget.updateAll(context) }

        // Placed before there was a choice, or never configured: all feeds.
        assertEquals(WidgetSource.All, WidgetSources.get(context, all))
        assertEquals("News", text(widgets.getViewFor(all), R.id.widget_title))
        assertTrue(text(widgets.getViewFor(all), R.id.widget_status).startsWith("3 ungelesen"))
        assertEquals(listOf("Neuer Prozessor", "Neues Telefon", "Stadtfest"), rowTitles(all))

        assertEquals("Technik", text(widgets.getViewFor(folder), R.id.widget_title))
        assertTrue(text(widgets.getViewFor(folder), R.id.widget_status).startsWith("2 ungelesen"))
        assertEquals(listOf("Neuer Prozessor", "Neues Telefon"), rowTitles(folder))

        assertEquals("Lokalzeitung", text(widgets.getViewFor(single), R.id.widget_title))
        assertTrue(text(widgets.getViewFor(single), R.id.widget_status).startsWith("1 ungelesen"))
        assertEquals(listOf("Stadtfest"), rowTitles(single))

        // The heading opens the app on the same folder; the settings button opens the choice.
        val app = shadowOf(context as android.app.Application)
        widgets.getViewFor(folder).findViewById<android.view.View>(R.id.widget_header).performClick()
        assertEquals("folder:Technik", app.nextStartedActivity.getStringExtra(NewsWidget.EXTRA_SOURCE))
        widgets.getViewFor(single).findViewById<android.view.View>(R.id.widget_configure).performClick()
        val configure = app.nextStartedActivity
        assertEquals(WidgetConfigActivity::class.java.name, configure.component!!.className)
        assertEquals(single, configure.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1))

        // The chosen feed is unsubscribed: the widget says so instead of showing something else.
        runBlocking {
            repo.deleteFeed(ids.third)
            NewsWidget.updateAll(context)
        }
        assertTrue(text(widgets.getViewFor(single), R.id.widget_status).startsWith("Diesen Feed gibt es nicht mehr"))
        assertEquals(emptyList<String>(), rowTitles(single))

        // A removed widget forgets its choice.
        NewsWidgetProvider().onDeleted(context, intArrayOf(folder))
        assertEquals(WidgetSource.All, WidgetSources.get(context, folder))
    }

    @Test
    fun widgetHasItsOwnUpdateIntervalWhilePlaced() {
        // The app start scheduled the normal background update, but nothing for a widget.
        assertEquals(TimeUnit.MINUTES.toMillis(60), job(RefreshWorker.WORK_NAME)!!.periodicityInfo!!.repeatIntervalMillis)
        assertEquals(null, job(RefreshWorker.WIDGET_WORK_NAME))
        assertFalse(NewsWidget.isPlaced(context))

        widgets.createWidget(NewsWidgetProvider::class.java, R.layout.widget)
        NewsWidgetProvider().onEnabled(context)
        assertEquals(
            TimeUnit.MINUTES.toMillis(60),
            job(RefreshWorker.WIDGET_WORK_NAME)!!.periodicityInfo!!.repeatIntervalMillis,
        )

        // A new interval from the settings replaces the schedule; the normal update keeps its own.
        settings.update { it.copy(widgetIntervalMinutes = 20) }
        RefreshWorker.schedule(context, settings.current)
        assertEquals(
            TimeUnit.MINUTES.toMillis(20),
            job(RefreshWorker.WIDGET_WORK_NAME)!!.periodicityInfo!!.repeatIntervalMillis,
        )
        assertEquals(TimeUnit.MINUTES.toMillis(60), job(RefreshWorker.WORK_NAME)!!.periodicityInfo!!.repeatIntervalMillis)

        // It keeps running with automatic updates switched off.
        settings.update { it.copy(autoUpdate = false) }
        RefreshWorker.schedule(context, settings.current)
        assertEquals(null, job(RefreshWorker.WORK_NAME))
        assertEquals(WorkInfo.State.ENQUEUED, job(RefreshWorker.WIDGET_WORK_NAME)!!.state)
    }

    @Test
    fun refreshButtonStartsOneRefresh() {
        NewsWidgetProvider().onReceive(context, Intent(NewsWidgetProvider.ACTION_REFRESH))
        NewsWidgetProvider().onReceive(context, Intent(NewsWidgetProvider.ACTION_REFRESH))
        val jobs = WorkManager.getInstance(context).getWorkInfosForUniqueWork("refresh-now").get()
        assertEquals(1, jobs.size)
        assertEquals(WorkInfo.State.ENQUEUED, jobs.single().state)
    }

    @Test
    fun statusLine() {
        assertEquals("Alles gelesen", NewsWidget.statusText(context, 0, 0))
        assertEquals("7 ungelesen", NewsWidget.statusText(context, 7, 0))
        assertTrue(NewsWidget.statusText(context, 7, System.currentTimeMillis()).startsWith("7 ungelesen · Stand "))
        assertEquals(Notifier.EXTRA_ARTICLE_ID, "articleId")
    }
}
