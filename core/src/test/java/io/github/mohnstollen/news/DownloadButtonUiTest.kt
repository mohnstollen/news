package io.github.mohnstollen.news

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.mohnstollen.news.playback.DownloadProgress
import io.github.mohnstollen.news.ui.EpisodeDownloadButton
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
}
