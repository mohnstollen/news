package io.github.mohnstollen.news.ui

import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mohnstollen.news.data.Article
import io.github.mohnstollen.news.playback.Episodes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Downloaded episodes, newest first, with their total length and size; and running downloads. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(onBack: (() -> Unit)?, onOpenArticle: (Long) -> Unit) {
    val context = LocalContext.current
    val container = context.container
    val repo = container.repository
    val items by remember { repo.articleDao.observeDownloaded() }.collectAsStateWithLifecycle(emptyList())
    val progress by remember { container.downloads.progress }.collectAsStateWithLifecycle(emptyMap())
    val scope = rememberCoroutineScope()

    // Titles of running downloads; looked up as they change.
    var running by remember { mutableStateOf<List<Article>>(emptyList()) }
    LaunchedEffect(progress.keys) { running = progress.keys.mapNotNull { repo.articleDao.byId(it) } }
    var bytes by remember { mutableStateOf(0L) }
    LaunchedEffect(items) { bytes = withContext(Dispatchers.IO) { items.sumOf { File(it.downloadPath).length() } } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Downloads") },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück") }
                    }
                },
            )
        },
        bottomBar = { MiniPlayer(onOpenArticle = onOpenArticle) },
    ) { padding ->
        if (items.isEmpty() && running.isEmpty()) {
            Box(Modifier.padding(padding).fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    "Keine Folgen heruntergeladen.\nIn einer Folge lädt der Pfeil sie herunter; automatisch geht es pro " +
                        "Podcast unter „Podcasts verwalten“.",
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Scaffold
        }
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            if (items.isNotEmpty()) {
                item {
                    val (left, unknown) = Episodes.remaining(items.map { it.durationSeconds to it.playPosition })
                    val total = items.sumOf { (it.durationSeconds ?: 0) * 1000 }
                    val parts = listOfNotNull(
                        if (items.size == 1) "1 Folge" else "${items.size} Folgen",
                        Episodes.total(total).takeIf { total > 0 },
                        "${Episodes.total(left)} ungehört".takeIf { left in 1 until total },
                        Formatter.formatShortFileSize(context, bytes).takeIf { bytes > 0 },
                        "$unknown ohne Längenangabe".takeIf { unknown > 0 },
                    )
                    Text(
                        parts.joinToString(" · "),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                }
            }
            items(running, key = { "r" + it.id }) { article ->
                val p = progress[article.id]
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Text(article.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        when {
                            p == null || !p.running -> "Wartet auf Netz"
                            p.fraction != null -> "Wird geladen, ${(p.fraction!! * 100).toInt()} %"
                            else -> "Wird geladen"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val fraction = p?.fraction
                    if (fraction != null) {
                        LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                    } else {
                        LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            }
            items(items, key = { it.id }) { item ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().clickable { onOpenArticle(item.id) }
                        .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                ) {
                    EpisodePlayButton(item.id)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(item.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            listOfNotNull(Episodes.label(item.durationSeconds, item.playPosition, 0), item.feedTitle)
                                .joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    QueueButton(item.id)
                    IconButton(onClick = { scope.launch { container.downloads.delete(listOf(item.id)) } }) {
                        Icon(Icons.Default.Delete, contentDescription = "Download löschen")
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            }
        }
    }
}
