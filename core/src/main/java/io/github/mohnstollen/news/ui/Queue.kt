package io.github.mohnstollen.news.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
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
    val snackbar = remember { SnackbarHostState() }
    val downloads = LocalContext.current.container.downloads
    val progress by remember { downloads.progress }.collectAsStateWithLifecycle(emptyMap())

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
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
                    "Die Warteschlange ist leer.\nFolgen kommen hinein, wenn du sie unter „Neu“ nach rechts wischst, " +
                        "herunterlädst oder abspielst. Sie laufen nacheinander und werden im WLAN geladen.",
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
                QueueSwipe(
                    onToEnd = {
                        scope.launch {
                            repo.enqueue(item.id)
                            snackbar.showSnackbar("„${item.title}“ ans Ende verschoben")
                        }
                    },
                    onRemove = {
                        val before = items.map { it.id }
                        scope.launch {
                            repo.dequeue(item.id)
                            val result = snackbar.showSnackbar(
                                "Aus der Warteschlange genommen", actionLabel = "Rückgängig", duration = SnackbarDuration.Short,
                            )
                            if (result == SnackbarResult.ActionPerformed) repo.setQueueOrder(before)
                        }
                    },
                ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)
                        .clickable { onOpenArticle(item.id) }
                        .semantics {
                            customActions = listOf(
                                CustomAccessibilityAction("Ans Ende") { scope.launch { repo.enqueue(item.id) }; true },
                                CustomAccessibilityAction("Aus der Warteschlange entfernen") {
                                    scope.launch { repo.dequeue(item.id) }; true
                                },
                            )
                        }
                        .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                ) {
                    Cover(item.imageUrl ?: item.feedImageUrl, item.feedTitle, 48.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(item.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        // The length first: the podcast's name is often long and gets cut off.
                        val details = listOfNotNull(
                            Episodes.label(item.durationSeconds, item.playPosition, 0) ?: "Länge unbekannt",
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
                    EpisodePlayButton(item.id)
                    DownloadButton(item.id, item.downloaded, progress[item.id])
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
                }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            }
        }
    }
}

/** Swipe right: to the end of the queue ("later"). Swipe left: out of the queue. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QueueSwipe(onToEnd: () -> Unit, onRemove: () -> Unit, content: @Composable () -> Unit) {
    val toEnd by rememberUpdatedState(onToEnd)
    val remove by rememberUpdatedState(onRemove)
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = {
            when (it) {
                SwipeToDismissBoxValue.StartToEnd -> toEnd()
                SwipeToDismissBoxValue.EndToStart -> remove()
                SwipeToDismissBoxValue.Settled -> Unit
            }
            // Never settle swiped away: the list moves or drops the row itself.
            false
        },
    )
    SwipeToDismissBox(
        state = state,
        backgroundContent = {
            val toEndSide = state.dismissDirection == SwipeToDismissBoxValue.StartToEnd
            Row(
                Modifier.fillMaxSize()
                    .background(if (toEndSide) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer)
                    .padding(horizontal = 24.dp),
                horizontalArrangement = if (toEndSide) Arrangement.Start else Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (toEndSide) "Später (ans Ende)" else "Entfernen",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (toEndSide) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        },
    ) { content() }
}
