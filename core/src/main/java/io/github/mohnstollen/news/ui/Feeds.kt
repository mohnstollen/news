package io.github.mohnstollen.news.ui

import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mohnstollen.news.data.Feed
import io.github.mohnstollen.news.data.FeedException
import io.github.mohnstollen.news.data.FeedWithUnread
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val repo = remember { context.container.repository }
    val feeds by remember { repo.feedDao.observeWithUnread() }.collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    val folders = remember(feeds) {
        feeds.mapNotNull { it.feed.category }.distinct().sortedWith(String.CASE_INSENSITIVE_ORDER)
    }

    var menuOpen by remember { mutableStateOf(false) }
    var addOpen by rememberSaveable { mutableStateOf(false) }
    var editId by rememberSaveable { mutableStateOf<Long?>(null) }
    var deleteId by rememberSaveable { mutableStateOf<Long?>(null) }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                val (added, total) = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { repo.importOpml(it) }
                        ?: throw FeedException("Die Datei konnte nicht geöffnet werden")
                }
                if (added > 0) repo.scope.launch { repo.refreshAll() }
                snackbar.showSnackbar(
                    when {
                        total == 0 -> "Die Datei enthält keine Feeds"
                        added == 0 -> "Alle $total Feeds waren bereits abonniert"
                        else -> "$added von $total Feeds importiert, Artikel werden geladen"
                    },
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                snackbar.showSnackbar("Import fehlgeschlagen: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }
    val exportLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            scope.launch {
                try {
                    val opml = repo.exportOpml()
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(opml.toByteArray()) }
                            ?: throw FeedException("Die Datei konnte nicht geschrieben werden")
                    }
                    snackbar.showSnackbar("${feeds.size} Feeds exportiert")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    snackbar.showSnackbar("Export fehlgeschlagen: ${e.message ?: e.javaClass.simpleName}")
                }
            }
        }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(if (LocalContext.current.features.podcasts) "Podcasts" else "Feeds") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
                    }
                },
                actions = {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Import und Export")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("OPML importieren") },
                            onClick = { menuOpen = false; importLauncher.launch(arrayOf("*/*")) },
                        )
                        DropdownMenuItem(
                            text = { Text("OPML exportieren") },
                            enabled = feeds.isNotEmpty(),
                            onClick = { menuOpen = false; exportLauncher.launch("feeds.opml") },
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { addOpen = true },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text(LocalContext.current.features.addFeed) },
            )
        },
    ) { padding ->
        if (feeds.isEmpty()) {
            Box(Modifier.padding(padding).fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    "Noch keine Feeds. Füge einen Feed hinzu oder importiere eine OPML-Datei über das Menü.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.padding(padding).fillMaxSize(),
                contentPadding = PaddingValues(bottom = 96.dp),
            ) {
                items(feeds, key = { it.feed.id }) { entry ->
                    FeedRow(entry, onEdit = { editId = entry.feed.id }, onDelete = { deleteId = entry.feed.id })
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                }
            }
        }
    }

    if (addOpen) {
        AddFeedDialog(
            folders = folders,
            onAdd = { url, category, autoDownload -> repo.addFeed(url, category, autoDownload) },
            onAdded = { feed ->
                addOpen = false
                scope.launch { snackbar.showSnackbar("„${feed.title}“ hinzugefügt") }
            },
            onDismiss = { addOpen = false },
        )
    }
    feeds.firstOrNull { it.feed.id == editId }?.let { entry ->
        EditFeedDialog(
            feed = entry.feed,
            folders = folders,
            onSave = { title, url, category, fullText, notify, autoDownload ->
                repo.updateFeed(entry.feed.id, title, url, category, fullText, notify, autoDownload)
            },
            onSaved = { editId = null },
            onDismiss = { editId = null },
        )
    }
    feeds.firstOrNull { it.feed.id == deleteId }?.let { entry ->
        AlertDialog(
            onDismissRequest = { deleteId = null },
            title = { Text("Feed löschen?") },
            text = {
                Text("„${entry.feed.title}“ und alle zugehörigen Artikel werden entfernt, auch gemerkte Artikel und Notizen.")
            },
            confirmButton = {
                TextButton(onClick = {
                    deleteId = null
                    scope.launch { repo.deleteFeed(entry.feed.id) }
                }) { Text("Löschen", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleteId = null }) { Text("Abbrechen") } },
        )
    }
}

@Composable
private fun FeedRow(entry: FeedWithUnread, onEdit: () -> Unit, onDelete: () -> Unit) {
    val feed = entry.feed
    val podcasts = LocalContext.current.features.podcasts
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClickLabel = "Bearbeiten", onClick = onEdit)
            .padding(start = 16.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(feed.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                feed.url,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val status = buildList {
                feed.category?.let { add(it) }
                add(if (entry.unread == 1) "1 ungelesen" else "${entry.unread} ungelesen")
                if (feed.fetchFullText) add("Volltext")
                if (!feed.notify) add("stumm")
                if (feed.autoDownload && podcasts) add("lädt Folgen")
                add(
                    if (feed.lastFetched == 0L) "noch nicht geladen"
                    else "geladen " + DateUtils.getRelativeTimeSpanString(
                        feed.lastFetched, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS,
                    ),
                )
            }.joinToString(" · ")
            Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            feed.lastError?.let {
                Text(
                    "Fehler beim letzten Laden: $it",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Default.Delete, contentDescription = "„${feed.title}“ löschen")
        }
    }
}

/** Folder input that offers the existing folders, so that a typo does not create a second one. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FolderField(value: String, onChange: (String) -> Unit, folders: List<String>, enabled: Boolean) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded && enabled && folders.isNotEmpty(),
        onExpandedChange = { expanded = it },
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            label = { Text("Ordner (optional)") },
            supportingText = if (folders.isEmpty()) null else {
                { Text("Auswählen oder neuen Namen eingeben") }
            },
            trailingIcon = if (folders.isEmpty()) null else {
                { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) }
            },
            singleLine = true,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryEditable),
        )
        ExposedDropdownMenu(expanded = expanded && enabled && folders.isNotEmpty(), onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("Kein Ordner", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                onClick = { onChange(""); expanded = false },
            )
            folders.forEach { folder ->
                DropdownMenuItem(text = { Text(folder) }, onClick = { onChange(folder); expanded = false })
            }
        }
    }
}

@Composable
private fun AddFeedDialog(
    folders: List<String>,
    onAdd: suspend (url: String, category: String?, autoDownload: Boolean) -> Feed,
    onAdded: (Feed) -> Unit,
    onDismiss: () -> Unit,
) {
    var url by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf("") }
    var autoDownload by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(LocalContext.current.features.addFeed) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it; error = null },
                    label = { Text("Adresse des Feeds oder der Website") },
                    placeholder = { Text("beispiel.de/feed") },
                    singleLine = true,
                    enabled = !busy,
                    isError = error != null,
                    supportingText = error?.let { { Text(it) } },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                FolderField(value = category, onChange = { category = it }, folders = folders, enabled = !busy)
                if (LocalContext.current.features.podcasts) Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Folgen automatisch herunterladen")
                        Text(
                            "Für Podcasts: lädt die neueste Folge gleich und neue Folgen nach jedem Update.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = autoDownload, onCheckedChange = { autoDownload = it }, enabled = !busy)
                }
                if (busy) {
                    Text("Feed wird geprüft …", style = MaterialTheme.typography.bodySmall)
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && url.isNotBlank(),
                onClick = {
                    busy = true
                    scope.launch {
                        try {
                            onAdded(onAdd(url, category, autoDownload))
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            error = (e as? FeedException)?.message ?: "Fehler: ${e.message ?: e.javaClass.simpleName}"
                        } finally {
                            busy = false
                        }
                    }
                },
            ) { Text("Hinzufügen") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}

@Composable
private fun EditFeedDialog(
    feed: Feed,
    folders: List<String>,
    onSave: suspend (
        title: String,
        url: String,
        category: String?,
        fetchFullText: Boolean,
        notify: Boolean,
        autoDownload: Boolean,
    ) -> Unit,
    onSaved: () -> Unit,
    onDismiss: () -> Unit,
) {
    var title by rememberSaveable { mutableStateOf(feed.title) }
    var url by rememberSaveable { mutableStateOf(feed.url) }
    var category by rememberSaveable { mutableStateOf(feed.category.orEmpty()) }
    var fullText by rememberSaveable { mutableStateOf(feed.fetchFullText) }
    var notify by rememberSaveable { mutableStateOf(feed.notify) }
    var autoDownload by rememberSaveable { mutableStateOf(feed.autoDownload) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Feed bearbeiten") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it; error = null },
                    label = { Text("Titel") },
                    singleLine = true,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it; error = null },
                    label = { Text("Adresse") },
                    singleLine = true,
                    enabled = !busy,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                FolderField(value = category, onChange = { category = it }, folders = folders, enabled = !busy)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Volltext automatisch laden")
                        Text(
                            "Lädt bei jedem Update die verlinkten Artikel, damit sie offline lesbar sind. " +
                                "Verbraucht mehr Datenvolumen.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = fullText, onCheckedChange = { fullText = it }, enabled = !busy)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Bei neuen Artikeln benachrichtigen")
                        Text(
                            "Wirkt nur, wenn Benachrichtigungen in den Einstellungen eingeschaltet sind.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = notify, onCheckedChange = { notify = it }, enabled = !busy)
                }
                if (LocalContext.current.features.podcasts) Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Neue Folgen herunterladen")
                        Text(
                            "Für Podcasts: lädt neue Folgen nach jedem Update, damit sie offline abspielbar sind.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = autoDownload, onCheckedChange = { autoDownload = it }, enabled = !busy)
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy,
                onClick = {
                    busy = true
                    scope.launch {
                        try {
                            onSave(title, url, category, fullText, notify, autoDownload)
                            onSaved()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            error = (e as? FeedException)?.message ?: "Fehler: ${e.message ?: e.javaClass.simpleName}"
                        } finally {
                            busy = false
                        }
                    }
                },
            ) { Text("Speichern") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}
