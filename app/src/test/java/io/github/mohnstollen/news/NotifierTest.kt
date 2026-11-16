package io.github.mohnstollen.news

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.mohnstollen.news.data.ArticleListItem
import io.github.mohnstollen.news.data.SearchQuery
import io.github.mohnstollen.news.ui.MainActivity
import io.github.mohnstollen.news.work.Notifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = TestApp::class, sdk = [34])
class NotifierTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun item(id: Long, title: String, feed: String = "Feed A") =
        ArticleListItem(id, 1, feed, title, "Teaser $id", 0, read = false, starred = false, hasNote = false)

    private fun grant() = shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

    private fun text(n: Notification, key: String) = n.extras.getCharSequence(key)?.toString()

    @Test
    fun nothingIsShownWithoutPermission() {
        assertFalse(Notifier.canNotify(context))
        assertFalse(Notifier.showNewArticles(context, listOf(item(1, "Eins"))))
        assertEquals(0, shadowOf(manager).allNotifications.size)
    }

    @Test
    fun oneArticleIsShownByTitleAndOpensThatArticle() {
        grant()
        assertTrue(Notifier.showNewArticles(context, listOf(item(42, "Die Schlagzeile"))))
        val n = shadowOf(manager).allNotifications.single()
        assertEquals("Die Schlagzeile", text(n, Notification.EXTRA_TITLE))
        assertEquals("Feed A", text(n, Notification.EXTRA_TEXT))
        val intent = shadowOf(n.contentIntent).savedIntent
        assertEquals(MainActivity::class.java.name, intent.component!!.className)
        assertEquals(42L, intent.getLongExtra(Notifier.EXTRA_ARTICLE_ID, -1))
    }

    @Test
    fun severalArticlesAreSummarisedAndReplaceTheEarlierNotification() {
        grant()
        Notifier.showNewArticles(context, listOf(item(1, "Eins")))
        val articles = (1..7L).map { item(it, "Artikel $it", if (it % 2 == 0L) "Feed B" else "Feed A") }
        assertTrue(Notifier.showNewArticles(context, articles))
        val n = shadowOf(manager).allNotifications.single()
        assertEquals("7 neue Artikel", text(n, Notification.EXTRA_TITLE))
        assertEquals("Feed A, Feed B", text(n, Notification.EXTRA_TEXT))
        assertEquals(5, n.extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)!!.size)
        assertEquals("und 2 weitere", text(n, Notification.EXTRA_SUMMARY_TEXT))
        assertEquals("opens the list", 0L, shadowOf(n.contentIntent).savedIntent.getLongExtra(Notifier.EXTRA_ARTICLE_ID, -1))

        Notifier.cancel(context)
        assertEquals(0, shadowOf(manager).allNotifications.size)
    }

    @Test
    fun searchInputBecomesAPrefixQuery() {
        assertEquals("ärger* um* 5g*", SearchQuery.toMatch("  Ärger um \"5G\"! "))
        assertEquals("or* not* near*", SearchQuery.toMatch("OR NOT NEAR"))
        assertEquals("c*", SearchQuery.toMatch("c++ -*"))
        assertNull(SearchQuery.toMatch(" *\"- "))
        assertNull(SearchQuery.toMatch(""))
    }
}
