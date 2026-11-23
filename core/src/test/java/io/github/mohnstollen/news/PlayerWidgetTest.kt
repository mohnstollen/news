package io.github.mohnstollen.news

import android.app.Application
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.media3.session.MediaSessionService
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.mohnstollen.news.data.Article
import io.github.mohnstollen.news.data.Feed
import io.github.mohnstollen.news.data.QueueItem
import io.github.mohnstollen.news.playback.NowPlaying
import io.github.mohnstollen.news.playback.PlaybackService
import io.github.mohnstollen.news.ui.container
import io.github.mohnstollen.news.widget.PlayerWidget
import io.github.mohnstollen.news.widget.PlayerWidget.Layout
import io.github.mohnstollen.news.widget.PlayerWidgetModel
import io.github.mohnstollen.news.widget.PlayerWidgetProvider
import io.github.mohnstollen.news.work.Notifier
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = UiTestApp::class, sdk = [34])
class PlayerWidgetTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val repo get() = context.container.repository
    private val widgets = shadowOf(AppWidgetManager.getInstance(context))

    private val services = mutableListOf<PlaybackService>()

    @After
    fun stopServices() {
        services.forEach { it.onDestroy() }
    }

    /** Said when waiting fails. */
    private var state: () -> String = { "" }

    private fun waitFor(what: String, done: () -> Boolean) {
        repeat(1500) {
            shadowOf(Looper.getMainLooper()).idle()
            if (done()) return
            Thread.sleep(10)
        }
        throw AssertionError("timed out waiting for $what ${state()}")
    }

    private fun queueItem(id: Long, minutes: Long?, positionMs: Long = 0) = QueueItem(
        id = id, title = "Folge $id", feedTitle = "Der Podcast", durationSeconds = minutes?.times(60),
        playPosition = positionMs, downloaded = false,
    )

    private fun View.text(id: Int) = findViewById<TextView>(id).text.toString()
    private fun View.shown(id: Int) = findViewById<View>(id).visibility == View.VISIBLE

    @Test
    fun theLayoutFollowsTheSize() {
        // Sizes of a phone in portrait: 2×1, 4×1, 4×2.
        assertEquals(Layout.SMALL, PlayerWidget.layoutFor(130, 102))
        assertEquals(Layout.MEDIUM, PlayerWidget.layoutFor(276, 102))
        assertEquals(Layout.LARGE, PlayerWidget.layoutFor(276, 220))
        // 4×1 in landscape is too low for the row of buttons, and for the cover above the title.
        assertEquals(Layout.COMPACT, PlayerWidget.layoutFor(552, 51))
        assertEquals(Layout.COMPACT, PlayerWidget.layoutFor(0, 0))
        assertEquals("2×2", Layout.SMALL, PlayerWidget.layoutFor(130, 220))
    }

    @Test
    fun theWidgetShowsTheLoadedEpisodeOrElseTheFirstOfTheQueue() {
        val queue = listOf(queueItem(1, 60), queueItem(2, 30, positionMs = 600_000), queueItem(3, null), queueItem(4, 5), queueItem(5, 5))

        val idle = PlayerWidget.model(null, queue, speed = 1f)
        assertEquals(1L, idle.current!!.id)
        assertFalse(idle.loaded)
        assertFalse(idle.playing)
        assertEquals(3_600_000L, idle.remainingMs)
        assertEquals("three next ones, in queue order", listOf(2L, 3L, 4L), idle.upNext.map { it.id })
        assertEquals("at the chosen speed", 1_800_000L, PlayerWidget.model(null, queue, speed = 2f).remainingMs)

        // Episode 2 plays at double speed: 20 minutes of it are left, 10 minutes of time.
        val now = NowPlaying(2, "Folge 2", "Der Podcast", null, true, 600_000, 1_800_000, 2f, atElapsedMs = 1_000_000)
        val playing = PlayerWidget.model(now, queue, speed = 1f)
        assertEquals(2L, playing.current!!.id)
        assertTrue(playing.loaded && playing.playing)
        assertEquals(1_600_000L, playing.endsAtElapsedMs)
        assertNull(playing.remainingMs)
        assertEquals("the rest of the queue, as next plays it", listOf(1L, 3L, 4L), playing.upNext.map { it.id })

        val paused = PlayerWidget.model(now.copy(playing = false), queue, speed = 1f)
        assertEquals(600_000L, paused.remainingMs)
        assertNull(paused.endsAtElapsedMs)

        // A position read a little later while playing gives the same widget: nothing to redraw.
        val later = now.copy(positionMs = 600_200, atElapsedMs = 1_000_100)
        assertEquals(playing, PlayerWidget.model(later, queue, speed = 1f))

        assertNull(PlayerWidget.model(null, emptyList(), speed = 1f).current)
    }

    @Test
    fun eachLayoutShowsWhatFits() {
        val queue = listOf(queueItem(1, 60), queueItem(2, 30, positionMs = 600_000))
        val paused = PlayerWidget.model(null, queue, speed = 1f)

        val large = PlayerWidget.views(context, paused, Layout.LARGE, null).apply(context, FrameLayout(context))
        assertEquals("Folge 1", large.text(R.id.pw_title))
        assertEquals("noch 1:00:00 · Der Podcast", large.text(R.id.pw_detail))
        assertFalse(large.shown(R.id.pw_detail_clock))
        assertEquals("Abspielen", large.findViewById<View>(R.id.pw_play).contentDescription)
        assertTrue(large.shown(R.id.pw_up_next))
        assertEquals("Folge 2", large.text(R.id.pw_next_title_1))
        assertEquals("noch 20 Min", large.text(R.id.pw_next_meta_1))
        assertFalse(large.shown(R.id.pw_next_2))

        val now = NowPlaying(1, "Folge 1", "Der Podcast", null, true, 0, 3_600_000, 1f, android.os.SystemClock.elapsedRealtime())
        val playing = PlayerWidget.model(now, queue, speed = 1f)
        val medium = PlayerWidget.views(context, playing, Layout.MEDIUM, null).apply(context, FrameLayout(context))
        assertTrue("counts down by itself", medium.shown(R.id.pw_detail_clock))
        assertFalse(medium.shown(R.id.pw_detail))
        assertEquals("Pause", medium.findViewById<View>(R.id.pw_play).contentDescription)
        assertTrue(medium.shown(R.id.pw_forward) && medium.shown(R.id.pw_back) && medium.shown(R.id.pw_next))

        for (layout in listOf(Layout.SMALL, Layout.COMPACT)) {
            val small = PlayerWidget.views(context, playing, layout, null).apply(context, FrameLayout(context))
            assertEquals("Folge 1", small.text(R.id.pw_title))
            assertTrue(small.shown(R.id.pw_play))
        }

        val empty = PlayerWidget.views(context, PlayerWidgetModel(current = null), Layout.LARGE, null)
            .apply(context, FrameLayout(context))
        assertEquals("Warteschlange leer", empty.text(R.id.pw_title))
        assertFalse(empty.shown(R.id.pw_play))
        assertFalse(empty.shown(R.id.pw_up_next))
    }

    @Test
    fun tapsOpenThePlayerOrTheEpisode() {
        val queue = listOf(queueItem(1, 60), queueItem(2, 30))
        fun opened(model: PlayerWidgetModel, view: Int): Intent {
            val root = PlayerWidget.views(context, model, Layout.LARGE, null).apply(context, FrameLayout(context))
            root.findViewById<View>(view).performClick()
            return shadowOf(context as Application).nextStartedActivity
        }
        val notLoaded = opened(PlayerWidget.model(null, queue, 1f), R.id.pw_cover)
        assertEquals(1L, notLoaded.getLongExtra(Notifier.EXTRA_ARTICLE_ID, -1))
        val now = NowPlaying(1, "Folge 1", "Der Podcast", null, false, 0, 0, 1f, 0)
        val loaded = opened(PlayerWidget.model(now, queue, 1f), R.id.pw_text)
        assertTrue(loaded.getBooleanExtra(PlayerWidget.EXTRA_OPEN_PLAYER, false))
        val row = opened(PlayerWidget.model(now, queue, 1f), R.id.pw_next_1)
        assertEquals(2L, row.getLongExtra(Notifier.EXTRA_ARTICLE_ID, -1))
    }

    @Test
    fun theButtonsPlayPauseSeekAndSkipThroughTheService() {
        val (a, b) = runBlocking {
            val feedId = repo.feedDao.insert(Feed(url = "https://example.org/feed", title = "Der Podcast"))
            fun episode(guid: String) = Article(
                feedId = feedId, guid = guid, title = "Folge $guid", pubDate = 0, fetchedAt = 0,
                enclosureUrl = "https://cdn.example.org/$guid.wav", enclosureType = "audio/wav",
                downloadPath = silenceFile(context, "$guid.wav", seconds = 100.0).path,
            )
            val ids = repo.articleDao.insertAll(listOf(episode("a"), episode("b")))
            ids.forEach { repo.enqueue(it, handOn = false) }
            ids[0] to ids[1]
        }
        val service = Robolectric.buildService(PlaybackService::class.java).create().get().also { services += it }
        val player = service.sessions.single().player
        // The widget's controller binds to the service as on a device.
        shadowOf(context as Application).setComponentNameAndServiceForBindService(
            ComponentName(context, PlaybackService::class.java),
            service.onBind(Intent(MediaSessionService.SERVICE_INTERFACE)),
        )

        state = {
            "item=${player.currentMediaItem?.mediaId} state=${player.playbackState} pwr=${player.playWhenReady} " +
                "pos=${player.currentPosition} err=${player.playerError} queue=${runBlocking { repo.queueDao.ids() }} a=$a b=$b " +
                "now=${context.container.playerWidget.nowPlaying.value}"
        }
        val id = widgets.createWidget(PlayerWidgetProvider::class.java, R.layout.player_widget_compact)
        fun widget() = widgets.getViewFor(id)
        fun tap(button: Int) = assertTrue(widget().findViewById<View>(button).performClick())
        waitFor("drawn") { widget().text(R.id.pw_title) == "Folge a" }

        tap(R.id.pw_play)
        // The test clock stands still, so the player may stay in "buffering": what counts here is
        // that it was told to play (and how the widget shows that).
        waitFor("a plays") { player.playWhenReady && player.currentMediaItem?.mediaId == a.toString() }
        waitFor("the widget shows pause") { widget().findViewById<View>(R.id.pw_play).contentDescription == "Pause" }

        // The medium and large layouts send these.
        send(PlayerWidget.ACTION_FORWARD)
        waitFor("30 s on") { player.currentPosition >= 30_000L }

        send(PlayerWidget.ACTION_NEXT)
        waitFor("b plays") { player.currentMediaItem?.mediaId == b.toString() && player.playWhenReady }
        waitFor("the widget shows b") { widget().text(R.id.pw_title) == "Folge b" }
        assertEquals("a keeps its place", listOf(a, b), runBlocking { repo.queueDao.ids() })

        tap(R.id.pw_play)
        waitFor("paused") { !player.playWhenReady }
        waitFor("the widget shows play") { widget().findViewById<View>(R.id.pw_play).contentDescription == "Abspielen" }
    }

    /** As a tap on one of the widget's buttons. */
    private fun send(action: String) = context.sendBroadcast(Intent(context, PlayerWidgetProvider::class.java).setAction(action))
}

/** For looking at the widget: its sizes as one picture in the build folder. */
@RunWith(AndroidJUnit4::class)
@Config(application = UiTestApp::class, sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
class PlayerWidgetLookTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun drawTheSizes() {
        val queue = listOf(
            QueueItem(1, "Die Lage der Nation – Ausgabe 412: Haushalt, Rente und das Ende der Ampel", "Lage der Nation", 5400, 0, true),
            QueueItem(2, "Was die Bahn jetzt ändern muss", "Der Podcast", 2400, 900_000, false),
            QueueItem(3, "Sport am Sonntag", "Sportschau", 3100, 0, false),
            QueueItem(4, "Wochenrückblick", "Der Podcast", 1500, 0, false),
        )
        val now = NowPlaying(1, queue[0].title, "Lage der Nation", null, false, 1_200_000, 5_400_000, 1f, 0)
        val model = PlayerWidget.model(now, queue, 1f)
        // A cover: a plain coloured square with rounded corners.
        val density = context.resources.displayMetrics.density
        val coverPx = (52 * density).toInt()
        val cover = android.graphics.Bitmap.createBitmap(coverPx, coverPx, android.graphics.Bitmap.Config.ARGB_8888).apply {
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF3949AB.toInt() }
            android.graphics.Canvas(this).drawRoundRect(0f, 0f, coverPx.toFloat(), coverPx.toFloat(), 8 * density, 8 * density, paint)
        }
        // Sizes of a phone in portrait: 2×1, a low 4×1, 4×1, 4×2.
        val sizes = listOf(
            Layout.SMALL to (130 to 102), Layout.COMPACT to (276 to 60), Layout.MEDIUM to (276 to 102), Layout.LARGE to (276 to 220),
        )
        val gap = (16 * density).toInt()
        val width = (276 * density).toInt() + 2 * gap
        val height = sizes.sumOf { (it.second.second * density).toInt() + gap } + gap
        val picture = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(picture)
        canvas.drawColor(0xFF6D7F8C.toInt())
        var top = gap
        for ((layout, size) in sizes) {
            val w = (size.first * density).toInt()
            val h = (size.second * density).toInt()
            val view = PlayerWidget.views(context, model, layout, cover).apply(context, FrameLayout(context))
            view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, w, h)
            canvas.save()
            canvas.translate(gap.toFloat(), top.toFloat())
            view.draw(canvas)
            canvas.restore()
            top += h + gap
        }
        val dir = java.io.File("build/screenshots").apply { mkdirs() }
        java.io.FileOutputStream(java.io.File(dir, "podfang-widget.png")).use {
            picture.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
