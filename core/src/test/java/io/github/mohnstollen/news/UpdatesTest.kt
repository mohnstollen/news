package io.github.mohnstollen.news

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.mohnstollen.news.data.UpdateChecker
import io.github.mohnstollen.news.ui.container
import io.github.mohnstollen.news.work.AppUpdates
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The update check against a stand-in for GitHub's release list. */
@RunWith(AndroidJUnit4::class)
@Config(application = PodfangTestApp::class, sdk = [34])
class UpdatesTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val server = MockWebServer()

    private fun release(tag: String, draft: Boolean = false, prerelease: Boolean = false, apk: Boolean = true) = """
        {"tag_name":"$tag","html_url":"https://github.com/mohnstollen/news/releases/tag/$tag","draft":$draft,
         "prerelease":$prerelease,"assets":[${if (apk) """{"name":"x.apk","browser_download_url":"https://dl.example/$tag.apk"}""" else ""}]}
    """.trimIndent()

    private val releases = listOf(
        release("news-v1.9"),
        release("podfang-v1.1"),
        release("podfang-v1.10"),
        release("podfang-v1.9", apk = false),
        release("podfang-v2.0", draft = true),
        release("podfang-v1.11", prerelease = true),
    ).joinToString(",", "[", "]")

    @Before
    fun setUp() {
        server.start()
        context.container.updates.releasesUrl = server.url("/releases").toString()
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun versionsCompareByNumber() {
        assertTrue(UpdateChecker.isNewer("1.10", "1.9"))
        assertTrue(UpdateChecker.isNewer("1.1.1", "1.1"))
        assertFalse(UpdateChecker.isNewer("1.1", "1.1.0"))
        assertFalse(UpdateChecker.isNewer("1.0", "1.1"))
    }

    @Test
    fun theNewestPublishedVersionOfThisAppIsFound() {
        server.enqueue(MockResponse().setBody(releases))
        val latest = runBlocking { context.container.updates.latest("podfang") }!!
        assertEquals("not the draft, not the pre-release, not News'", "1.10", latest.version)
        assertEquals("https://dl.example/podfang-v1.10.apk", latest.downloadUrl)
        assertEquals("/releases", server.takeRequest().path)
    }

    @Test
    fun theDailyCheckAnnouncesANewVersionOnce() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        server.enqueue(MockResponse().setBody(releases))
        runBlocking { AppUpdates.checkInBackground(context) }
        val n = shadowOf(manager).allNotifications.single()
        assertEquals("Podfang 1.10 ist verfügbar", n.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals("https://dl.example/podfang-v1.10.apk", shadowOf(n.contentIntent).savedIntent.dataString)
        assertEquals("1.10", AppUpdates.available(context)?.version)

        // Within a day: GitHub is not asked again.
        runBlocking { AppUpdates.checkInBackground(context) }
        assertEquals(1, server.requestCount)

        // A day later, the same version: not announced again.
        manager.cancelAll()
        context.container.settings.update { it.copy(lastUpdateCheck = 0) }
        server.enqueue(MockResponse().setBody(releases))
        runBlocking { AppUpdates.checkInBackground(context) }
        assertEquals(2, server.requestCount)
        assertTrue(shadowOf(manager).allNotifications.isEmpty())

        // Switched off: nothing is asked.
        context.container.settings.update { it.copy(lastUpdateCheck = 0, updateCheck = false) }
        runBlocking { AppUpdates.checkInBackground(context) }
        assertEquals(2, server.requestCount)
    }

    @Test
    fun withoutANewerVersionNothingIsOffered() {
        server.enqueue(MockResponse().setBody("""[${release("news-v9.0")}]"""))
        assertNull(runBlocking { AppUpdates.check(context) })
        assertNull(AppUpdates.available(context))
        server.enqueue(MockResponse().setResponseCode(403))
        assertTrue(runCatching { runBlocking { AppUpdates.check(context) } }.isFailure)
    }
}
