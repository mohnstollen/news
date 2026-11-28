package io.github.mohnstollen.news

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.mohnstollen.news.playback.DownloadProgress
import io.github.mohnstollen.news.ui.EpisodeDownloadButton
import io.github.mohnstollen.news.ui.container
import io.github.mohnstollen.news.ui.downloadStatus
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The download button says why a download waits; waiting for Wi-Fi, it can go ahead now. */
@RunWith(AndroidJUnit4::class)
@Config(application = UiTestApp::class, sdk = [34])
class DownloadButtonUiTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun waitingForWifiOffersMobileDataOrCancelling() {
        val calls = mutableListOf<String>()
        compose.setContent {
            EpisodeDownloadButton(
                downloaded = false,
                download = DownloadProgress(running = false, waiting = DownloadProgress.Waiting.WIFI),
                onDownload = { calls += "download" },
                onCancel = { calls += "cancel" },
                onDelete = { calls += "delete" },
                onDownloadNow = { calls += "now" },
            )
        }
        compose.onNodeWithContentDescription("Wartet auf WLAN").performClick()
        compose.onNodeWithText("Jetzt über Mobilfunk laden").performClick()
        compose.onNodeWithContentDescription("Wartet auf WLAN").performClick()
        compose.onNodeWithText("Download abbrechen").performClick()
        assertEquals(listOf("now", "cancel"), calls)
    }

    @Test
    fun waitingForTheDownloadBeforeIsSaidAndCancels() {
        var cancelled = false
        compose.setContent {
            EpisodeDownloadButton(
                downloaded = false,
                download = DownloadProgress(running = false, waiting = DownloadProgress.Waiting.TURN),
                onDownload = {},
                onCancel = { cancelled = true },
                onDelete = {},
            )
        }
        compose.onNodeWithContentDescription("Wartet auf den vorigen Download", useUnmergedTree = true).assertExists()
        compose.onNodeWithContentDescription("Download abbrechen", useUnmergedTree = true).performClick()
        assertEquals(true, cancelled)
    }

    @Test
    fun theQueueSaysWhatADownloadWaitsFor() {
        fun status(waiting: DownloadProgress.Waiting?, bytes: Long = 0, total: Long = 0) =
            downloadStatus(DownloadProgress(running = waiting == null, bytes = bytes, total = total, waiting = waiting))
        assertEquals("wartet auf WLAN", status(DownloadProgress.Waiting.WIFI))
        assertEquals("wartet auf Netz", status(DownloadProgress.Waiting.NETWORK))
        assertEquals("wartet auf den Download davor", status(DownloadProgress.Waiting.TURN))
        assertEquals("lädt 25 %", status(null, bytes = 25, total = 100))
        assertEquals("lädt", status(null))
    }

    @Test
    fun blockedBackgroundDataIsNamed() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val downloads = context.container.downloads
        // The test environment reports no free space unless told otherwise.
        org.robolectric.shadows.ShadowStatFs.registerStats(downloads.dir, 1_000_000, 1_000_000, 1_000_000)
        assertNull(downloads.blocker())
        val connectivity = context.getSystemService(android.net.ConnectivityManager::class.java)
        org.robolectric.Shadows.shadowOf(connectivity)
            .setRestrictBackgroundStatus(android.net.ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED)
        val blocker = downloads.blocker()
        assertTrue(blocker, blocker!!.startsWith("Android sperrt mobile Daten im Hintergrund"))
    }

    @Test
    fun aNearlyFullStorageIsNamed() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val downloads = context.container.downloads
        org.robolectric.shadows.ShadowStatFs.registerStats(downloads.dir, 1_000_000, 10, 10)
        assertEquals("Der Speicher ist fast voll; Android startet Downloads erst, wenn wieder Platz ist.", downloads.blocker())
    }
}
