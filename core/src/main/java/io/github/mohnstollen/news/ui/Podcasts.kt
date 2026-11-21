package io.github.mohnstollen.news.ui

import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mohnstollen.news.data.ArticleListItem
import io.github.mohnstollen.news.data.NOT_ARCHIVED
import io.github.mohnstollen.news.data.Rhythm
import io.github.mohnstollen.news.playback.DownloadProgress
import io.github.mohnstollen.news.playback.Episodes
import kotlinx.coroutines.launch

/** The subscribed podcasts as a grid of covers. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PodcastsScreen(
    onOpenPodcast: (Long) -> Unit,
    onOpenArticle: (Long) -> Unit,
    onManage: () -> Unit,
    onDiscover: () -> Unit,
) {
    val repo = LocalContext.current.container.repository
    val feeds by remember { repo.feedDao.observeWithUnread() }.collectAsStateWithLifecycle(null)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Podcasts") },
                actions = {
                    IconButton(onClick = onDiscover) { Icon(AppIcons.Explore, contentDescription = "Podcasts entdecken") }
                    IconButton(onClick = onManage) {
                        Icon(Icons.Default.Edit, contentDescription = "Podcasts verwalten, hinzufügen, importieren")
                    }
                },
            )
        },
        bottomBar = { MiniPlayer(onOpenArticle = onOpenArticle) },
    ) { padding ->
        val list = feeds ?: return@Scaffold
        if (list.isEmpty()) {
            Column(
                Modifier.padding(padding).fillMaxSize().padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Noch keine Podcasts abonniert.", textAlign = TextAlign.Center)
                Spacer(Modifier.height(16.dp))
                Button(onClick = onDiscover) { Text("Podcasts entdecken") }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onManage) { Text("Adresse eingeben oder OPML importieren") }
            }
            return@Scaffold
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 104.dp),
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(list.sortedBy { it.feed.title.lowercase() }, key = { it.feed.id }) { entry ->
                Column(Modifier.clickable(onClickLabel = "Übersicht öffnen") { onOpenPodcast(entry.feed.id) }) {
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        Cover(entry.feed.imageUrl, entry.feed.title, maxWidth)
                        if (entry.unread > 0) {
                            Box(
                                Modifier.align(Alignment.TopEnd).padding(4.dp),
                            ) {
                                androidx.compose.material3.Badge { Text(if (entry.unread > 99) "99+" else "${entry.unread}") }
                            }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        entry.feed.title,
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * One podcast: cover, how often it publishes, all its episodes with play, download and queue,
 * and its settings and unsubscribing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PodcastScreen(feedId: Long, onBack: () -> Unit, onOpenArticle: (Long) -> Unit) {
    val context = LocalContext.current
    val container = context.container
    val repo = container.repository
    val feed by remember(feedId) { repo.feedDao.observeById(feedId) }.collectAsStateWithLifecycle(null)
    val episodes by remember(feedId) { repo.articleDao.observeList(feedId, false, NOT_ARCHIVED) }
        .collectAsStateWithLifecycle(null)
    val progress by remember { container.downloads.progress }.collectAsStateWithLifecycle(emptyMap())
    val scope = rememberCoroutineScope()
    var editOpen by rememberSaveable { mutableStateOf(false) }
    var deleteOpen by rememberSaveable { mutableStateOf(false) }
    val f = feed

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(f?.title.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück") }
                },
                actions = {
                    IconButton(onClick = { editOpen = true }, enabled = f != null) {
                        Icon(Icons.Default.Settings, contentDescription = "Einstellungen des Podcasts")
                    }
                    IconButton(onClick = { deleteOpen = true }, enabled = f != null) {
                        Icon(Icons.Default.Delete, contentDescription = "Podcast abbestellen")
                    }
                },
            )
        },
        bottomBar = { MiniPlayer(onOpenArticle = onOpenArticle) },
    ) { padding ->
        val list = episodes ?: return@Scaffold
        val now = remember(list) { System.currentTimeMillis() }
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            item {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Cover(f?.imageUrl, f?.title.orEmpty(), 96.dp)
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text(f?.title.orEmpty(), style = MaterialTheme.typography.titleLarge, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        val facts = listOfNotNull(
                            Rhythm.describe(list.map { it.pubDate })?.let { "Erscheint $it" },
                            if (list.size == 1) "1 Folge" else "${list.size} Folgen",
                            "lädt neue Folgen".takeIf { f?.autoDownload == true },
                        )
                        Text(facts.joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            }
            items(list, key = { it.id }) { item ->
                PodcastEpisodeRow(item, now, progress[item.id], onOpen = { onOpenArticle(item.id) })
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            }
        }
    }

    if (editOpen && f != null) {
        val folders = remember { mutableStateOf(emptyList<String>()) }
        androidx.compose.runtime.LaunchedEffect(Unit) {
            folders.value = repo.feedDao.all().mapNotNull { it.category }.distinct().sorted()
        }
        EditFeedDialog(
            feed = f,
            folders = folders.value,
            onSave = { title, url, category, fullText, notify, autoDownload ->
                repo.updateFeed(f.id, title, url, category, fullText, notify, autoDownload)
            },
            onSaved = { editOpen = false },
            onDismiss = { editOpen = false },
        )
    }
    if (deleteOpen && f != null) {
        AlertDialog(
            onDismissRequest = { deleteOpen = false },
            title = { Text("Podcast abbestellen?") },
            text = {
                Text(
                    "„${f.title}“ und alle zugehörigen Folgen werden entfernt, auch gemerkte, heruntergeladene " +
                        "und Notizen. Die Statistik bleibt.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    deleteOpen = false
                    scope.launch {
                        repo.deleteFeed(f.id)
                        onBack()
                    }
                }) { Text("Abbestellen") }
            },
            dismissButton = { TextButton(onClick = { deleteOpen = false }) { Text("Abbrechen") } },
        )
    }
}

/** An episode in a podcast's overview: date and length, then play, download and queue. */
@Composable
private fun PodcastEpisodeRow(item: ArticleListItem, now: Long, download: DownloadProgress?, onOpen: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                listOfNotNull(
                    DateUtils.getRelativeTimeSpanString(item.pubDate, now, DateUtils.DAY_IN_MILLIS).toString(),
                    Episodes.label(item.durationSeconds, item.playPosition, item.playedAt),
                ).joinToString(" · "),
                style = MaterialTheme.typography.labelMedium,
                color = colors.onSurfaceVariant,
            )
            Text(
                item.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = if (item.read) FontWeight.Normal else FontWeight.SemiBold,
                color = if (item.read) colors.onSurfaceVariant else colors.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (item.hasAudio) {
            EpisodePlayButton(item.id)
            DownloadButton(item.id, item.downloaded, download)
            QueueButton(item.id)
        }
    }
}
