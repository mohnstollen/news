package io.github.mohnstollen.news.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mohnstollen.news.playback.DownloadProgress
import io.github.mohnstollen.news.data.Chapter
import io.github.mohnstollen.news.data.Chapters
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import io.github.mohnstollen.news.playback.Episodes
import io.github.mohnstollen.news.playback.PlayerConnection
import io.github.mohnstollen.news.playback.PlayerState
import io.github.mohnstollen.news.playback.SleepTimer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.util.Locale

/** The activity's connection to the player; null in previews. */
val LocalPlayer = staticCompositionLocalOf<PlayerConnection?> { null }

private val idle = MutableStateFlow(PlayerState())

@Composable
fun playerState(): PlayerState {
    val player = LocalPlayer.current
    val state by (player?.state ?: idle).collectAsStateWithLifecycle()
    return state
}

/** Round play/pause button for an episode in a list or in the reader. */
@Composable
fun EpisodePlayButton(articleId: Long, size: Dp = 36.dp) {
    val player = LocalPlayer.current
    val state = playerState()
    val active = state.isPlaying(articleId)
    FilledTonalIconButton(onClick = { player?.toggle(articleId) }, modifier = Modifier.size(size)) {
        if (state.articleId == articleId && state.buffering) {
            CircularProgressIndicator(Modifier.size(size * 0.5f), strokeWidth = 2.dp)
        } else if (active) {
            Icon(AppIcons.Pause, contentDescription = "Pause", modifier = Modifier.size(size * 0.6f))
        } else {
            Icon(Icons.Default.PlayArrow, contentDescription = "Abspielen", modifier = Modifier.size(size * 0.6f))
        }
    }
}

/**
 * Bar at the bottom of a screen while an episode is loaded. A tap opens the full player.
 * Use it as a Scaffold's bottomBar: it handles the navigation bar inset itself.
 */
@Composable
fun MiniPlayer(onOpenArticle: ((Long) -> Unit)?) {
    val player = LocalPlayer.current ?: return
    val state = playerState()
    val id = state.articleId ?: return
    var sheetOpen by rememberSaveable { mutableStateOf(false) }

    Surface(tonalElevation = 3.dp, shadowElevation = 3.dp) {
        Column(Modifier.windowInsetsPadding(WindowInsets.navigationBars)) {
            LinearProgressIndicator(
                progress = { if (state.durationMs > 0) (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f) else 0f },
                modifier = Modifier.fillMaxWidth().height(2.dp),
                drawStopIndicator = {},
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            ) {
                Cover(state.artwork, state.subtitle.ifBlank { state.title }, 44.dp)
                Spacer(Modifier.width(12.dp))
                Column(
                    Modifier.weight(1f)
                        .clickable(onClickLabel = "Player öffnen") { sheetOpen = true }
                        .padding(vertical = 4.dp),
                ) {
                    Text(state.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val timer by player.sleepTimer.mode.collectAsStateWithLifecycle()
                    val timerText = when (timer) {
                        is SleepTimer.Mode.Until -> "Timer " + Episodes.clock(player.sleepTimer.remainingMs() ?: 0)
                        SleepTimer.Mode.EndOfEpisode -> "Timer: Folgenende"
                        null -> ""
                    }
                    Text(
                        text = state.error ?: listOf(state.subtitle, progressText(state), timerText).filter { it.isNotBlank() }
                            .joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (state.error != null) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                SeekButton(back = true, onClick = player::seekBack)
                EpisodePlayButton(id, size = 44.dp)
                SeekButton(back = false, onClick = player::seekForward)
            }
        }
    }

    if (sheetOpen) {
        PlayerSheet(
            state = state,
            player = player,
            onOpenArticle = onOpenArticle?.let { open -> { sheetOpen = false; open(id) } },
            onDismiss = { sheetOpen = false },
        )
    }
}

private fun progressText(state: PlayerState): String =
    if (state.durationMs > 0) Episodes.clock(state.positionMs) + " / " + Episodes.clock(state.durationMs)
    else Episodes.clock(state.positionMs)

@Composable
private fun SeekButton(back: Boolean, onClick: () -> Unit, size: Dp = 40.dp) {
    val seconds = if (back) 10 else 30
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(size).semantics {
            contentDescription = if (back) "10 Sekunden zurück" else "30 Sekunden vor"
        },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(if (back) AppIcons.Replay else AppIcons.Forward, contentDescription = null, modifier = Modifier.size(size * 0.7f))
            Text("$seconds", fontSize = (size.value * 0.2f).sp, modifier = Modifier.offset(y = size * 0.06f))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun PlayerSheet(
    state: PlayerState,
    player: PlayerConnection,
    onOpenArticle: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    // While the thumb is dragged, show where it is instead of where playback is.
    var dragging by remember { mutableStateOf<Float?>(null) }
    val chapters = rememberChapters(state.articleId)
    val currentChapter = Chapters.current(chapters, state.positionMs)
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp),
        ) {
            Box(Modifier.fillMaxWidth().padding(bottom = 16.dp), contentAlignment = Alignment.Center) {
                Cover(state.artwork, state.subtitle.ifBlank { state.title }, 240.dp)
            }
            Text(state.title, style = MaterialTheme.typography.titleLarge, maxLines = 3, overflow = TextOverflow.Ellipsis)
            if (state.subtitle.isNotBlank()) {
                Text(state.subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            chapters.getOrNull(currentChapter)?.let {
                Text(
                    "Kapitel ${currentChapter + 1}/${chapters.size}: ${it.title}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            state.error?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(16.dp))

            val duration = state.durationMs
            val shown = dragging ?: if (duration > 0) state.positionMs.toFloat() / duration else 0f
            Slider(
                value = shown.coerceIn(0f, 1f),
                onValueChange = { dragging = it },
                onValueChangeFinished = {
                    dragging?.let { player.seekTo((it * duration).toLong()) }
                    dragging = null
                },
                enabled = duration > 0,
                modifier = Modifier.semantics { contentDescription = "Position" },
            )
            Row(Modifier.fillMaxWidth()) {
                val position = if (duration > 0) (shown * duration).toLong() else state.positionMs
                Text(Episodes.clock(position), style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.weight(1f))
                if (duration > 0) {
                    Text("−" + Episodes.clock(duration - position), style = MaterialTheme.typography.labelMedium)
                }
            }

            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            ) {
                SeekButton(back = true, onClick = player::seekBack, size = 56.dp)
                Spacer(Modifier.width(24.dp))
                val id = state.articleId
                FilledIconButton(
                    onClick = { if (id != null) player.toggle(id) },
                    modifier = Modifier.size(72.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(),
                ) {
                    if (id != null && state.isPlaying(id)) {
                        Icon(AppIcons.Pause, contentDescription = "Pause", modifier = Modifier.size(40.dp))
                    } else {
                        Icon(Icons.Default.PlayArrow, contentDescription = "Abspielen", modifier = Modifier.size(40.dp))
                    }
                }
                Spacer(Modifier.width(24.dp))
                SeekButton(back = false, onClick = player::seekForward, size = 56.dp)
            }

            Text("Geschwindigkeit", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PlayerConnection.SPEEDS.forEach { speed ->
                    FilterChip(
                        selected = kotlin.math.abs(state.speed - speed) < 0.01f,
                        onClick = { player.setSpeed(speed) },
                        label = { Text(speedLabel(speed)) },
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            if (chapters.isNotEmpty()) {
                ChapterList(chapters, currentChapter, state.positionMs, player)
                Spacer(Modifier.height(8.dp))
            }
            SleepTimerChips(player)

            val queue by player.queue.collectAsStateWithLifecycle(emptyList())
            queue.firstOrNull()?.let { next ->
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Als Nächstes", style = MaterialTheme.typography.labelLarge)
                        Text(
                            next.title + if (queue.size > 1) " (+${queue.size - 1})" else "",
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    TextButton(onClick = player::playNext) { Text("Jetzt abspielen") }
                }
            }

            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth()) {
                if (onOpenArticle != null) TextButton(onClick = onOpenArticle) { Text("Zur Folge") }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { player.stop(); onDismiss() }) {
                    Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Beenden")
                }
            }
        }
    }
}

/** Chapters of the episode, from the database; they can arrive while it plays. */
@Composable
private fun rememberChapters(articleId: Long?): List<Chapter> {
    if (articleId == null) return emptyList()
    val repo = androidx.compose.ui.platform.LocalContext.current.container.repository
    val article by remember(articleId) { repo.articleDao.observe(articleId) }.collectAsStateWithLifecycle(null)
    val json = article?.chapters
    return remember(json) { Chapters.decode(json) }
}

@Composable
private fun ChapterList(chapters: List<Chapter>, current: Int, positionMs: Long, player: PlayerConnection) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Kapitel", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
        // Back goes to the start of the chapter; a few seconds in, to the one before.
        TextButton(
            onClick = {
                val start = chapters.getOrNull(current)?.startMs ?: 0
                val target = if (positionMs - start > 3_000 || current <= 0) start else chapters[current - 1].startMs
                player.seekTo(target)
            },
        ) { Text("Vorheriges") }
        TextButton(
            onClick = { chapters.getOrNull(current + 1)?.let { player.seekTo(it.startMs) } },
            enabled = current + 1 < chapters.size,
        ) { Text("Nächstes") }
    }
    chapters.forEachIndexed { i, chapter ->
        val playing = i == current
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
                .clickable(onClickLabel = "Zu diesem Kapitel springen") { player.seekTo(chapter.startMs) }
                .padding(vertical = 6.dp),
        ) {
            Text(
                Episodes.clock(chapter.startMs),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(64.dp),
            )
            Text(
                chapter.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (playing) androidx.compose.ui.text.font.FontWeight.SemiBold else null,
                color = if (playing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SleepTimerChips(player: PlayerConnection) {
    val timer = player.sleepTimer
    val mode by timer.mode.collectAsStateWithLifecycle()
    val current = mode
    Text(
        when (current) {
            is SleepTimer.Mode.Until -> "Sleep-Timer: noch " + Episodes.clock(timer.remainingMs() ?: 0)
            SleepTimer.Mode.EndOfEpisode -> "Sleep-Timer: am Ende der Folge"
            null -> "Sleep-Timer"
        },
        style = MaterialTheme.typography.labelLarge,
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SleepTimer.CHOICES.forEach { minutes ->
            FilterChip(
                selected = (current as? SleepTimer.Mode.Until)?.minutes == minutes,
                onClick = { timer.start(minutes) },
                label = { Text("$minutes Min") },
            )
        }
        FilterChip(
            selected = current == SleepTimer.Mode.EndOfEpisode,
            onClick = timer::endOfEpisode,
            label = { Text("Folgenende") },
        )
        if (current != null) FilterChip(selected = false, onClick = timer::cancel, label = { Text("Aus") })
    }
}

fun speedLabel(speed: Float): String =
    String.format(Locale.GERMAN, if (speed % 1f == 0f) "%.0f×" else "%.1f×", speed)

/** For the article list: the episode's length or progress, and whether it is downloaded. */
@Composable
fun EpisodeStatus(
    articleId: Long,
    durationSeconds: Long?,
    playPosition: Long,
    playedAt: Long,
    downloaded: Boolean,
    download: DownloadProgress?,
    modifier: Modifier = Modifier,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
        EpisodePlayButton(articleId)
        val label = Episodes.label(durationSeconds, playPosition, playedAt)
        if (label != null) {
            Spacer(Modifier.width(8.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(8.dp))
        when {
            download != null -> DownloadProgressIcon(download, Modifier.size(16.dp))
            downloaded -> Icon(
                AppIcons.DownloadDone,
                contentDescription = "Heruntergeladen",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@Composable
private fun DownloadProgressIcon(download: DownloadProgress, modifier: Modifier) {
    val fraction = download.fraction
    val description = when {
        !download.running -> "Download wartet"
        fraction != null -> "Wird geladen, ${(fraction * 100).toInt()} %"
        else -> "Wird geladen"
    }
    Box(modifier.semantics { contentDescription = description }) {
        if (download.running && fraction != null) {
            CircularProgressIndicator(progress = { fraction }, strokeWidth = 2.dp, modifier = Modifier.matchParentSize())
        } else {
            CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.matchParentSize())
        }
    }
}

/** Download, cancel or delete the episode's file. */
@Composable
fun EpisodeDownloadButton(
    downloaded: Boolean,
    download: DownloadProgress?,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
) {
    when {
        download != null -> IconButton(onClick = onCancel) {
            Box(contentAlignment = Alignment.Center) {
                DownloadProgressIcon(download, Modifier.size(28.dp))
                Icon(Icons.Default.Close, contentDescription = "Download abbrechen", modifier = Modifier.size(14.dp))
            }
        }
        downloaded -> IconButton(onClick = onDelete) {
            Icon(AppIcons.DownloadDone, contentDescription = "Download löschen", tint = MaterialTheme.colorScheme.primary)
        }
        else -> IconButton(onClick = onDownload) {
            Icon(AppIcons.Download, contentDescription = "Herunterladen", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Adds the episode to the queue, or takes it out. */
@Composable
fun QueueButton(articleId: Long) {
    val repo = androidx.compose.ui.platform.LocalContext.current.container.repository
    // null until the database answered: a tap before that would do the opposite of what it shows.
    val ids by remember { repo.queueDao.observeIds() }.collectAsStateWithLifecycle(null)
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val queued = articleId in (ids ?: return)
    // The episode that is playing is not queued; adding it would play it twice.
    if (playerState().articleId == articleId && !queued) return
    IconButton(onClick = { scope.launch { if (queued) repo.dequeue(articleId) else repo.enqueue(articleId) } }) {
        Icon(
            if (queued) AppIcons.QueueRemove else AppIcons.QueueAdd,
            contentDescription = if (queued) "Aus der Warteschlange nehmen" else "Zur Warteschlange hinzufügen",
            tint = if (queued) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Shows the speed; a tap switches to the next one. */
@Composable
fun SpeedButton() {
    val player = LocalPlayer.current ?: return
    val speed = playerState().speed
    TextButton(
        onClick = {
            val speeds = PlayerConnection.SPEEDS
            val next = speeds.firstOrNull { it > speed + 0.01f } ?: speeds.first()
            player.setSpeed(next)
        },
        modifier = Modifier.semantics { contentDescription = "Geschwindigkeit ${speedLabel(speed)}, ändern" },
    ) { Text(speedLabel(speed)) }
}
