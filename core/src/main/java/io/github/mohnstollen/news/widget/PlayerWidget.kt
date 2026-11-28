package io.github.mohnstollen.news.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.format.DateUtils
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews
import androidx.core.graphics.drawable.toBitmap
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import coil.transform.RoundedCornersTransformation
import io.github.mohnstollen.news.R
import io.github.mohnstollen.news.data.QueueItem
import io.github.mohnstollen.news.data.Repository
import io.github.mohnstollen.news.data.SettingsStore
import io.github.mohnstollen.news.playback.Episodes
import io.github.mohnstollen.news.playback.NowPlaying
import io.github.mohnstollen.news.playback.PlaybackService
import io.github.mohnstollen.news.playback.RemotePlayback
import io.github.mohnstollen.news.ui.MainActivity
import io.github.mohnstollen.news.ui.container
import io.github.mohnstollen.news.work.Notifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToLong

/**
 * Podfang's home screen widget: the episode that is playing (or plays next), its buttons and the
 * next episodes of the queue. The buttons send the same commands as the lock screen.
 */
class PlayerWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = redraw(context)

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) =
        redraw(context)

    override fun onEnabled(context: Context) = context.container.playerWidget.placedChanged()

    override fun onDisabled(context: Context) = context.container.playerWidget.placedChanged()

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action !in PlayerWidget.ACTIONS) return super.onReceive(context, intent)
        val app = context.applicationContext
        // Keeps the process alive until the command is given; null when not called from a broadcast.
        val pending: PendingResult? = goAsync()
        // A media controller belongs to the thread it is built on, which needs a looper: the main one.
        CoroutineScope(Dispatchers.Main.immediate).launch {
            try {
                withTimeoutOrNull(COMMAND_TIMEOUT_MS) { PlayerWidget.perform(app, action!!) }
            } finally {
                pending?.finish()
            }
        }
    }

    private fun redraw(context: Context) {
        val updater = context.container.playerWidget
        updater.placedChanged()
        val pending: PendingResult? = goAsync()
        updater.scope.launch {
            try {
                updater.redraw()
            } finally {
                pending?.finish()
            }
        }
    }

    private companion object {
        /** A broadcast may take about ten seconds. */
        const val COMMAND_TIMEOUT_MS = 8_000L
    }
}

/** One episode as the widget shows it. */
data class WidgetEpisode(
    val id: Long,
    val title: String,
    val podcast: String,
    val cover: String?,
    val durationSeconds: Long?,
    /** The saved position; 0 for the loaded episode, whose times are in [PlayerWidgetModel]. */
    val positionMs: Long,
)

/** Everything the widget shows; equal models draw the same widget. */
data class PlayerWidgetModel(
    /** Playing, paused, or first in the queue; null when there is nothing to play. */
    val current: WidgetEpisode?,
    /** [current] is loaded in the player, so the seek buttons work and a tap opens the player. */
    val loaded: Boolean = false,
    val playing: Boolean = false,
    /** While playing: [android.os.SystemClock.elapsedRealtime] at which the episode will end. */
    val endsAtElapsedMs: Long? = null,
    /** Otherwise: time left at the current speed; null while the length is unknown. */
    val remainingMs: Long? = null,
    val upNext: List<WidgetEpisode> = emptyList(),
)

object PlayerWidget {
    const val ACTION_PLAY_PAUSE = "io.github.mohnstollen.news.widget.PLAY_PAUSE"
    const val ACTION_BACK = "io.github.mohnstollen.news.widget.BACK"
    const val ACTION_FORWARD = "io.github.mohnstollen.news.widget.FORWARD"
    const val ACTION_NEXT = "io.github.mohnstollen.news.widget.NEXT"
    val ACTIONS = setOf(ACTION_PLAY_PAUSE, ACTION_BACK, ACTION_FORWARD, ACTION_NEXT)

    /** Opens the full player when the app starts from the widget. */
    const val EXTRA_OPEN_PLAYER = "openPlayer"

    const val UP_NEXT = 3

    enum class Layout(val res: Int, val size: SizeF) {
        /** Too low for more: episode and play/pause. */
        COMPACT(R.layout.player_widget_compact, SizeF(110f, 40f)),
        /** From 2×1: cover and play/pause, below them the episode. */
        SMALL(R.layout.player_widget_small, SizeF(110f, 80f)),
        /** From 4×1: below cover and episode the buttons -10 s, play/pause, +30 s, next. */
        MEDIUM(R.layout.player_widget_medium, SizeF(250f, 90f)),
        /** From 4×2: also the next episodes of the queue. */
        LARGE(R.layout.player_widget_large, SizeF(250f, 180f)),
    }

    /** The largest layout that fits [widthDp] × [heightDp], as Android 12 and later choose it. */
    fun layoutFor(widthDp: Int, heightDp: Int): Layout =
        Layout.entries.filter { widthDp >= it.size.width && heightDp >= it.size.height }
            .maxByOrNull { it.size.width * it.size.height } ?: Layout.COMPACT

    fun ids(context: Context): IntArray = AppWidgetManager.getInstance(context)
        .getAppWidgetIds(ComponentName(context, PlayerWidgetProvider::class.java))

    fun isPlaced(context: Context): Boolean = ids(context).isNotEmpty()

    /**
     * What the widget shows: the loaded episode, or else the first of the queue. The next ones are
     * the rest of the queue in its order, as "next" plays them.
     */
    fun model(now: NowPlaying?, queue: List<QueueItem>, speed: Float): PlayerWidgetModel {
        fun QueueItem.episode() = WidgetEpisode(id, title, feedTitle, imageUrl ?: feedImageUrl, durationSeconds, playPosition)
        val queued = now?.let { n -> queue.firstOrNull { it.id == n.articleId } }
        val current = when {
            now != null -> WidgetEpisode(
                id = now.articleId,
                title = now.title.ifBlank { queued?.title.orEmpty() },
                podcast = now.podcast.ifBlank { queued?.feedTitle.orEmpty() },
                cover = now.artwork ?: queued?.let { it.imageUrl ?: it.feedImageUrl },
                durationSeconds = now.durationMs.takeIf { it > 0 }?.div(1000) ?: queued?.durationSeconds,
                // It moves all the time; what the widget shows of it is in the times below.
                positionMs = 0,
            )
            else -> queue.firstOrNull()?.episode() ?: return PlayerWidgetModel(current = null)
        }
        val upNext = queue.filter { it.id != current.id }.take(UP_NEXT).map { it.episode() }
        val durationMs = now?.durationMs?.takeIf { it > 0 } ?: current.durationSeconds?.takeIf { it > 0 }?.times(1000)
        val rate = (now?.speed ?: speed).takeIf { it > 0f } ?: 1f
        val position = now?.positionMs ?: current.positionMs
        val left = durationMs?.let { ((it - position).coerceAtLeast(0) / rate).toLong() }
        val playing = now?.playing == true
        return PlayerWidgetModel(
            current = current,
            loaded = now != null,
            playing = playing,
            // Rounded, so that player events that do not move playback do not redraw the widget.
            endsAtElapsedMs = if (playing && left != null) roundToSecond(now!!.atElapsedMs + left) else null,
            remainingMs = if (!playing && left != null) roundToSecond(left) else null,
            upNext = upNext,
        )
    }

    private fun roundToSecond(ms: Long) = (ms / 1000.0).roundToLong() * 1000

    /** The widget for [layout]; [cover] is the loaded cover image, if any. */
    fun views(context: Context, model: PlayerWidgetModel, layout: Layout, cover: Bitmap?): RemoteViews {
        val views = RemoteViews(context.packageName, layout.res)
        val current = model.current
        val buttons = intArrayOf(R.id.pw_play, R.id.pw_back, R.id.pw_forward, R.id.pw_next)

        val open = when {
            current == null -> openApp(context, "app")
            model.loaded -> openApp(context, "player") { putExtra(EXTRA_OPEN_PLAYER, true) }
            else -> openEpisode(context, current.id)
        }
        views.setOnClickPendingIntent(R.id.pw_cover, open)
        views.setOnClickPendingIntent(R.id.pw_text, open)

        if (cover != null) views.setImageViewBitmap(R.id.pw_cover, cover)
        else views.setImageViewResource(R.id.pw_cover, R.drawable.player_widget_cover_placeholder)

        if (current == null) {
            views.setTextViewText(R.id.pw_title, "Warteschlange leer")
            views.setTextViewText(R.id.pw_detail, "Folgen unter „Neu“ einreihen")
            views.setViewVisibility(R.id.pw_detail, View.VISIBLE)
            views.setViewVisibility(R.id.pw_detail_clock, View.GONE)
            for (id in buttons) views.setViewVisibility(id, View.GONE)
            views.setViewVisibility(R.id.pw_up_next, View.GONE)
            return views
        }

        views.setTextViewText(R.id.pw_title, current.title)
        // "noch 23:41 · Podcast": counting down by itself while playing, fixed when paused.
        val podcast = current.podcast.replace("%", "%%")
        val ends = model.endsAtElapsedMs
        if (ends != null) {
            views.setChronometer(R.id.pw_detail_clock, ends, "noch %s · $podcast", true)
            views.setChronometerCountDown(R.id.pw_detail_clock, true)
            views.setViewVisibility(R.id.pw_detail_clock, View.VISIBLE)
            views.setViewVisibility(R.id.pw_detail, View.GONE)
        } else {
            val detail = model.remainingMs?.let { "noch ${DateUtils.formatElapsedTime(it / 1000)} · ${current.podcast}" }
                ?: current.podcast
            views.setTextViewText(R.id.pw_detail, detail)
            views.setViewVisibility(R.id.pw_detail, View.VISIBLE)
            views.setViewVisibility(R.id.pw_detail_clock, View.GONE)
        }

        for (id in buttons) views.setViewVisibility(id, View.VISIBLE)
        views.setImageViewResource(R.id.pw_play, if (model.playing) R.drawable.ic_player_widget_pause else R.drawable.ic_player_widget_play)
        views.setContentDescription(
            R.id.pw_play,
            context.getString(if (model.playing) R.string.player_widget_pause else R.string.player_widget_play),
        )
        views.setOnClickPendingIntent(R.id.pw_play, command(context, ACTION_PLAY_PAUSE))
        views.setOnClickPendingIntent(R.id.pw_back, command(context, ACTION_BACK))
        views.setOnClickPendingIntent(R.id.pw_forward, command(context, ACTION_FORWARD))
        views.setOnClickPendingIntent(R.id.pw_next, command(context, ACTION_NEXT))
        // Seeking needs a loaded episode; until then the buttons are only hinted at.
        val seekAlpha = if (model.loaded) 255 else 90
        views.setInt(R.id.pw_back, "setImageAlpha", seekAlpha)
        views.setInt(R.id.pw_forward, "setImageAlpha", seekAlpha)
        views.setInt(R.id.pw_next, "setImageAlpha", if (model.upNext.isNotEmpty()) 255 else 90)

        views.setViewVisibility(R.id.pw_up_next, if (model.upNext.isEmpty()) View.GONE else View.VISIBLE)
        val rows = listOf(
            Triple(R.id.pw_next_1, R.id.pw_next_title_1, R.id.pw_next_meta_1),
            Triple(R.id.pw_next_2, R.id.pw_next_title_2, R.id.pw_next_meta_2),
            Triple(R.id.pw_next_3, R.id.pw_next_title_3, R.id.pw_next_meta_3),
        )
        rows.forEachIndexed { i, (row, title, meta) ->
            val episode = model.upNext.getOrNull(i)
            if (episode == null) {
                views.setViewVisibility(row, View.GONE)
                return@forEachIndexed
            }
            views.setViewVisibility(row, View.VISIBLE)
            views.setTextViewText(title, episode.title)
            views.setTextViewText(meta, Episodes.label(episode.durationSeconds, episode.positionMs, 0).orEmpty())
            views.setOnClickPendingIntent(row, openEpisode(context, episode.id))
        }
        return views
    }

    /** All sizes at once where the launcher picks by size itself (Android 12 on); else the fitting one. */
    fun viewsFor(context: Context, model: PlayerWidgetModel, cover: Bitmap?, options: Bundle?): RemoteViews =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            RemoteViews(Layout.entries.associate { it.size to views(context, model, it, cover) })
        } else {
            val width = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH) ?: 0
            val height = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT) ?: 0
            views(context, model, layoutFor(width, height), cover)
        }

    private const val OPEN_FLAGS =
        Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
    private const val IMMUTABLE = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

    /** Distinct data per purpose, so that the PendingIntents do not replace each other. */
    private fun openApp(context: Context, purpose: String, extras: Intent.() -> Unit = {}): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).addFlags(OPEN_FLAGS)
            .setData(Uri.parse("podfang-widget://open/$purpose")).apply(extras)
        return PendingIntent.getActivity(context, 0, intent, IMMUTABLE)
    }

    private fun openEpisode(context: Context, articleId: Long) =
        openApp(context, "episode/$articleId") { putExtra(Notifier.EXTRA_ARTICLE_ID, articleId) }

    private fun command(context: Context, action: String): PendingIntent {
        val intent = Intent(context, PlayerWidgetProvider::class.java).setAction(action)
        return PendingIntent.getBroadcast(context, action.hashCode(), intent, IMMUTABLE)
    }

    /**
     * Carries out a button of the widget through a media controller, as the lock screen would.
     * Without a loaded episode, play starts the first of the queue and next the one after it.
     */
    suspend fun perform(context: Context, action: String) {
        val repo = context.container.repository
        val controller = RemotePlayback.connect(context)
        try {
            val current = controller.currentMediaItem?.mediaId?.toLongOrNull()
            val state = controller.playbackState
            val active = current != null && state != Player.STATE_IDLE && state != Player.STATE_ENDED
            var started = false
            fun play(id: Long) {
                controller.setMediaItem(MediaItem.Builder().setMediaId(id.toString()).build())
                controller.prepare()
                controller.play()
                started = true
            }
            when (action) {
                ACTION_PLAY_PAUSE -> when {
                    active && controller.playWhenReady -> controller.pause()
                    active -> {
                        controller.play()
                        started = true
                    }
                    // Loaded but stopped (after an error, say): load it again where it was.
                    current != null && state == Player.STATE_IDLE -> play(current)
                    else -> repo.nextInQueue(null)?.let(::play)
                }
                // While an episode is still loading it cannot be seeked yet; wait for that a moment.
                ACTION_BACK -> if (active && awaitCommand(controller, Player.COMMAND_SEEK_BACK)) controller.seekBack()
                ACTION_FORWARD -> if (active && awaitCommand(controller, Player.COMMAND_SEEK_FORWARD)) controller.seekForward()
                // Without a loaded episode: the one after the episode the widget shows.
                ACTION_NEXT -> if (active) controller.seekToNext()
                else repo.nextInQueue(current ?: repo.queueDao.ids().firstOrNull())?.let(::play)
            }
            if (started) RemotePlayback.awaitStarted(controller)
        } finally {
            controller.release()
        }
    }

    /** Whether [command] can be given, waiting a moment for it if need be. */
    private suspend fun awaitCommand(controller: MediaController, command: Int): Boolean =
        withTimeoutOrNull(RemotePlayback.START_TIMEOUT_MS) {
            RemotePlayback.awaitUntil(controller) { it.isCommandAvailable(command) }
        } != null
}

/**
 * Redraws the widgets whenever playback or the queue change, while at least one is placed.
 * Started by the app when it has podcasts.
 */
class PlayerWidgetUpdater(
    private val context: Context,
    private val repo: Repository,
    private val settings: SettingsStore,
) {
    /** Set by [PlaybackService]. */
    val nowPlaying = MutableStateFlow<NowPlaying?>(null)
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val placed = MutableStateFlow(false)
    private var lastCover: Pair<String, Bitmap>? = null

    @OptIn(ExperimentalCoroutinesApi::class)
    fun start() {
        placedChanged()
        scope.launch {
            placed.flatMapLatest { shown ->
                if (!shown) emptyFlow()
                else combine(nowPlaying, repo.queueDao.observe()) { now, queue ->
                    PlayerWidget.model(now, queue, settings.current.playbackSpeed)
                }
            }.distinctUntilChanged().collectLatest(::draw)
        }
    }

    /** A widget was placed or the last one removed. */
    fun placedChanged() {
        placed.value = PlayerWidget.isPlaced(context)
    }

    /** Draws from the current state, e.g. when the launcher asks. */
    suspend fun redraw() {
        draw(PlayerWidget.model(nowPlaying.value, repo.queueDao.observe().first(), settings.current.playbackSpeed))
    }

    private suspend fun draw(model: PlayerWidgetModel) {
        val ids = PlayerWidget.ids(context)
        if (ids.isEmpty()) return
        val cover = cover(model.current?.cover)
        val manager = AppWidgetManager.getInstance(context)
        // On the main thread like the provider's own calls, so that drawings do not overlap.
        withContext(Dispatchers.Main) {
            for (id in ids) {
                manager.updateAppWidget(id, PlayerWidget.viewsFor(context, model, cover, manager.getAppWidgetOptions(id)))
            }
        }
    }

    /** The cover, small and with rounded corners; kept for the next drawing. */
    private suspend fun cover(url: String?): Bitmap? {
        if (url == null) return null
        lastCover?.let { (u, bitmap) -> if (u == url) return bitmap }
        val density = context.resources.displayMetrics.density
        val request = ImageRequest.Builder(context)
            .data(url)
            .size((COVER_DP * density).toInt())
            .transformations(RoundedCornersTransformation(8 * density))
            // Widgets get the picture over a binder; hardware bitmaps cannot go there.
            .allowHardware(false)
            .build()
        // Not kept when it failed (offline, say): the next drawing tries again.
        val bitmap = (context.imageLoader.execute(request) as? SuccessResult)?.drawable?.toBitmap() ?: return null
        lastCover = url to bitmap
        return bitmap
    }

    private companion object {
        /** The largest cover in the layouts. */
        const val COVER_DP = 52
    }
}
