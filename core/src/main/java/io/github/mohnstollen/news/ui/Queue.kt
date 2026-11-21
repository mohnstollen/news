package io.github.mohnstollen.news.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mohnstollen.news.playback.Episodes
import kotlinx.coroutines.launch

/** The episodes that play after the current one, in order. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueScreen(onBack: (() -> Unit)?, onOpenArticle: (Long) -> Unit) {
    val repo = LocalContext.current.container.repository
    val items by remember { repo.queueDao.observe() }.collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Warteschlange") },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück") }
                    }
                },
                actions = {
                    if (items.isNotEmpty()) TextButton(onClick = { scope.launch { repo.queueDao.clear() } }) { Text("Leeren") }
                },
            )
        },
        bottomBar = { MiniPlayer(onOpenArticle = onOpenArticle) },
    ) { padding ->
        if (items.isEmpty()) {
            Box(Modifier.padding(padding).fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    "Die Warteschlange ist leer.\nFolgen kommen hinein über den Knopf in der Folge oder indem du sie " +
                        "in der Liste nach rechts wischst. Sie laufen nacheinander, wenn eine Folge zu Ende ist.",
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Scaffold
        }
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            item {
                val (left, unknown) = Episodes.remaining(items.map { it.durationSeconds to it.playPosition })
                val parts = listOfNotNull(
                    if (items.size == 1) "1 Folge" else "${items.size} Folgen",
                    "${Episodes.total(left)} verbleibend".takeIf { left > 0 },
                    when (unknown) {
                        0 -> null
                        1 -> "1 ohne Längenangabe"
                        else -> "$unknown ohne Längenangabe"
                    },
                )
                Text(
                    parts.joinToString(" · "),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            }
            itemsIndexed(items, key = { _, it -> it.id }) { index, item ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().clickable { onOpenArticle(item.id) }
                        .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                ) {
                    EpisodePlayButton(item.id)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(item.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        // The length first: the podcast's name is often long and gets cut off.
                        val details = listOfNotNull(
                            Episodes.label(item.durationSeconds, item.playPosition, 0) ?: "Länge unbekannt",
                            "geladen".takeIf { item.downloaded },
                            item.feedTitle,
                        )
                        Text(
                            details.joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Column(verticalArrangement = Arrangement.Center) {
                        IconButton(
                            onClick = { scope.launch { repo.moveInQueue(item.id, up = true) } },
                            enabled = index > 0,
                            modifier = Modifier.size(32.dp),
                        ) { Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Nach oben") }
                        IconButton(
                            onClick = { scope.launch { repo.moveInQueue(item.id, up = false) } },
                            enabled = index < items.lastIndex,
                            modifier = Modifier.size(32.dp),
                        ) { Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Nach unten") }
                    }
                    IconButton(onClick = { scope.launch { repo.dequeue(item.id) } }) {
                        Icon(Icons.Default.Close, contentDescription = "Aus der Warteschlange entfernen")
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            }
        }
    }
}
