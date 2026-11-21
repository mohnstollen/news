package io.github.mohnstollen.news.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mohnstollen.news.data.CatalogPodcast
import io.github.mohnstollen.news.data.FeedException
import io.github.mohnstollen.news.data.PodcastPreview
import io.github.mohnstollen.news.data.Recommendation
import io.github.mohnstollen.news.playback.Episodes
import androidx.compose.foundation.clickable
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Finds podcasts in Apple's public catalogue: by search, or suggested from the subscribed ones.
 * Nothing is sent before the user asks for it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoverScreen(onBack: () -> Unit) {
    val container = LocalContext.current.container
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<CatalogPodcast>?>(null) }
    var suggestions by remember { mutableStateOf<List<Recommendation>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val subscribed = remember { mutableStateListOf<String>() }
    // Only local data: which subscribed podcast to start from. null = all of them.
    var seeds by remember { mutableStateOf<List<io.github.mohnstollen.news.data.Feed>>(emptyList()) }
    LaunchedEffect(Unit) { seeds = container.recommender.seeds() }
    var seed by rememberSaveable { mutableStateOf<Long?>(null) }
    var seedMenu by remember { mutableStateOf(false) }
    var previewOf by remember { mutableStateOf<CatalogPodcast?>(null) }

    fun run(block: suspend () -> Unit) {
        busy = true
        error = null
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = "Der Podcast-Katalog ist gerade nicht erreichbar: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                busy = false
            }
        }
    }

    fun subscribe(podcast: CatalogPodcast) {
        scope.launch {
            try {
                container.repository.addFeed(podcast.feedUrl, null)
                subscribed += podcast.feedUrl
                snackbar.showSnackbar("„${podcast.title}“ abonniert")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                snackbar.showSnackbar((e as? FeedException)?.message ?: "Abonnieren fehlgeschlagen")
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Podcasts entdecken") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück") }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            item {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text("Podcast suchen") },
                        singleLine = true,
                        trailingIcon = {
                            IconButton(onClick = { run { results = container.recommender.search(query) } }, enabled = query.isNotBlank()) {
                                Icon(Icons.Default.Search, contentDescription = "Suchen")
                            }
                        },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { if (query.isNotBlank()) run { results = container.recommender.search(query) } }),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text("Ähnliche Podcasts", style = MaterialTheme.typography.titleMedium)
                    ExposedDropdownMenuBox(expanded = seedMenu, onExpandedChange = { seedMenu = it }) {
                        OutlinedTextField(
                            value = seeds.firstOrNull { it.id == seed }?.title ?: "Alle meine Podcasts",
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Ähnlich zu") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = seedMenu) },
                            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
                        )
                        ExposedDropdownMenu(expanded = seedMenu, onDismissRequest = { seedMenu = false }) {
                            DropdownMenuItem(text = { Text("Alle meine Podcasts") }, onClick = { seed = null; seedMenu = false })
                            seeds.forEach { feed ->
                                DropdownMenuItem(
                                    text = { Text(feed.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                    onClick = { seed = feed.id; seedMenu = false },
                                )
                            }
                        }
                    }
                    Text(
                        "Sucht im öffentlichen Podcast-Katalog von Apple nach Podcasts mit gleicher Kategorie und " +
                            "ähnlichen Themen. Dafür gehen Podcast-Namen und einige Stichwörter aus den Folgentiteln an " +
                            "Apple. Ohne Konto, und nur wenn du hier tippst.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(onClick = { run { suggestions = container.recommender.recommend(seed) } }, enabled = !busy) {
                        Text("Vorschläge laden")
                    }
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
            }

            results?.let { list ->
                item { SectionHeader(if (list.isEmpty()) "Keine Treffer" else "Suchergebnisse") }
                items(list, key = { "r" + it.feedUrl }) { podcast ->
                    PodcastRow(
                        podcast,
                        reason = null,
                        subscribed = podcast.feedUrl in subscribed,
                        onSubscribe = { subscribe(podcast) },
                        onOpen = { previewOf = podcast },
                    )
                }
            }

            suggestions?.let { list ->
                item {
                    SectionHeader(
                        if (list.isEmpty()) {
                            "Keine Vorschläge gefunden. Sie stützen sich auf abonnierte Podcasts; mit mehr Folgen werden sie besser."
                        } else "Vorschläge für dich",
                    )
                }
                items(list, key = { "s" + it.podcast.feedUrl }) { rec ->
                    val reason = "Weil du „${rec.because}“ hörst" + (rec.keyword?.let { " · Thema: $it" } ?: "")
                    PodcastRow(
                        rec.podcast,
                        reason = reason,
                        subscribed = rec.podcast.feedUrl in subscribed,
                        onSubscribe = { subscribe(rec.podcast) },
                        onOpen = { previewOf = rec.podcast },
                    )
                }
            }
        }
    }

    previewOf?.let { podcast ->
        PreviewSheet(
            podcast = podcast,
            subscribed = podcast.feedUrl in subscribed,
            onSubscribe = { subscribe(podcast) },
            onDismiss = { previewOf = null },
        )
    }
}

/** A catalogue podcast's latest episodes and how often it publishes, before subscribing. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PreviewSheet(podcast: CatalogPodcast, subscribed: Boolean, onSubscribe: () -> Unit, onDismiss: () -> Unit) {
    val recommender = LocalContext.current.container.recommender
    var preview by remember(podcast) { mutableStateOf<PodcastPreview?>(null) }
    var error by remember(podcast) { mutableStateOf<String?>(null) }
    LaunchedEffect(podcast) {
        try {
            preview = recommender.preview(podcast)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = (e as? FeedException)?.message ?: "Der Podcast lässt sich gerade nicht laden"
        }
    }
    val dateFormat = remember { java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
            item {
                Text(podcast.title, style = MaterialTheme.typography.titleLarge)
                Text(
                    listOfNotNull(podcast.author.ifBlank { null }, podcast.genre).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                val p = preview
                when {
                    error != null -> Text(error!!, color = MaterialTheme.colorScheme.error)
                    p == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
                    else -> {
                        val newest = p.latest.firstOrNull()?.pubDate
                        val facts = listOfNotNull(
                            p.rhythm?.let { "Erscheint $it" },
                            newest?.let { "letzte Folge " + daysAgo(it) },
                            if (p.episodeCount == 1) "1 Folge" else "${p.episodeCount} Folgen",
                        )
                        Text(facts.joinToString(" · "), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Spacer(Modifier.height(12.dp))
                if (subscribed) {
                    Text("Abonniert", color = MaterialTheme.colorScheme.primary)
                } else {
                    Button(onClick = onSubscribe) { Text("Abonnieren") }
                }
                Spacer(Modifier.height(16.dp))
                if (preview?.latest?.isNotEmpty() == true) Text("Letzte Folgen", style = MaterialTheme.typography.titleMedium)
            }
            items(preview?.latest.orEmpty()) { episode ->
                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Text(
                        listOfNotNull(
                            dateFormat.format(java.util.Date(episode.pubDate)),
                            episode.durationSeconds?.let(Episodes::minutes),
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(episode.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            }
        }
    }
}

/** "heute", "gestern", "vor 5 Tagen". */
internal fun daysAgo(millis: Long, now: Long = System.currentTimeMillis()): String {
    val days = ((now - millis) / (24 * 60 * 60 * 1000L)).coerceAtLeast(0)
    return when (days) {
        0L -> "heute"
        1L -> "gestern"
        else -> "vor $days Tagen"
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
}

@Composable
private fun PodcastRow(
    podcast: CatalogPodcast,
    reason: String?,
    subscribed: Boolean,
    onSubscribe: () -> Unit,
    onOpen: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Column(Modifier.weight(1f).clickable(onClickLabel = "Letzte Folgen ansehen", onClick = onOpen)) {
            reason?.let {
                Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(2.dp))
            }
            Text(podcast.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(podcast.author.ifBlank { null }, podcast.genre).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        if (subscribed) {
            Icon(Icons.Default.Check, contentDescription = "Abonniert", tint = MaterialTheme.colorScheme.primary)
        } else {
            FilledTonalButton(onClick = onSubscribe) { Text("Abonnieren") }
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
}
