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
import io.github.mohnstollen.news.data.Recommendation
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
                    if (suggestions == null) {
                        Text("Vorschläge für dich", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Sucht im öffentlichen Podcast-Katalog von Apple nach Podcasts, die zu deinen passen: " +
                                "gleiche Kategorie, ähnliche Themen. Dafür gehen die Namen deiner Podcasts und einige " +
                                "Stichwörter aus ihren Folgentiteln an Apple. Ohne Konto, und nur wenn du hier tippst.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Button(onClick = { run { suggestions = container.recommender.recommend() } }, enabled = !busy) {
                            Text("Vorschläge laden")
                        }
                    }
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
            }

            results?.let { list ->
                item { SectionHeader(if (list.isEmpty()) "Keine Treffer" else "Suchergebnisse") }
                items(list, key = { "r" + it.feedUrl }) { podcast ->
                    PodcastRow(podcast, reason = null, subscribed = podcast.feedUrl in subscribed, onSubscribe = { subscribe(podcast) })
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
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
}

@Composable
private fun PodcastRow(podcast: CatalogPodcast, reason: String?, subscribed: Boolean, onSubscribe: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Column(Modifier.weight(1f)) {
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
