package io.github.mohnstollen.news

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.graphics.Bitmap
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.mohnstollen.news.data.Article
import io.github.mohnstollen.news.data.Feed
import io.github.mohnstollen.news.ui.container
import io.github.mohnstollen.news.work.EpisodeActions
import io.github.mohnstollen.news.work.Notifier
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Podfang's "new episodes" notification: cover and buttons to play or queue. */
@RunWith(AndroidJUnit4::class)
@Config(application = UiTestApp::class, sdk = [34])
class EpisodeNotificationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val repo get() = context.container.repository

    private fun episodes(vararg titles: String): List<Long> = runBlocking {
        val feedId = repo.feedDao.insert(Feed(url = "https://pod.example/feed", title = "Der Podcast"))
        repo.articleDao.insertAll(
            titles.mapIndexed { i, title ->
                Article(
                    feedId = feedId, guid = title, title = title, pubDate = 1000L - i, fetchedAt = 0,
                    enclosureUrl = "https://pod.example/$i.mp3", durationSeconds = 600,
                )
            },
        )
    }

    private fun waitFor(what: String, done: () -> Boolean) {
        repeat(500) {
            shadowOf(Looper.getMainLooper()).idle()
            if (done()) return
            Thread.sleep(10)
        }
        throw AssertionError("timed out waiting for $what")
    }

    @Test
    fun oneEpisodeOffersPlayAndQueueWithItsCover() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val ids = episodes("Folge 7")
        val items = runBlocking { repo.articleDao.listItems(ids) }
        val cover = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        assertTrue(Notifier.showNewArticles(context, items, cover))
        val n = shadowOf(manager).allNotifications.single()
        assertNotNull("the cover", n.getLargeIcon())
        assertEquals(listOf("Abspielen", "Einreihen"), n.actions.map { it.title.toString() })
        val play = shadowOf(n.actions[0].actionIntent).savedIntent
        assertEquals(EpisodeActions.ACTION_PLAY, play.action)

        // "Einreihen": into the queue, the notification goes away.
        n.actions[1].actionIntent.send()
        waitFor("queued") { runBlocking { repo.queueDao.ids() } == ids }
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
    }

    @Test
    fun severalEpisodesCanAllBeQueuedOldestFirst() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val ids = episodes("Neueste", "Mittlere", "Älteste")
        val items = runBlocking { repo.articleDao.listItems(ids) }
        assertTrue(Notifier.showNewArticles(context, items))
        val n = shadowOf(manager).allNotifications.single()
        assertEquals("3 neue Artikel", n.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals(listOf("Alle einreihen"), n.actions.map { it.title.toString() })
        n.actions[0].actionIntent.send()
        waitFor("queued") { runBlocking { repo.queueDao.ids() }.size == 3 }
        assertEquals("in the order they came out", ids.reversed(), runBlocking { repo.queueDao.ids() })
    }
}
