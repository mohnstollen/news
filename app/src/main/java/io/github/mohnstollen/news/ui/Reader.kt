package io.github.mohnstollen.news.ui

import android.annotation.SuppressLint
import android.app.Application
import android.content.Intent
import android.text.TextUtils
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.mohnstollen.news.data.Article
import io.github.mohnstollen.news.data.Feed
import io.github.mohnstollen.news.playback.DownloadProgress
import io.github.mohnstollen.news.playback.Episodes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

class ReaderViewModel(app: Application, handle: SavedStateHandle) : AndroidViewModel(app) {
    private val repo = app.container.repository
    private val id: Long = checkNotNull(handle["id"])

    val article = repo.articleDao.observe(id)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val feed = MutableStateFlow<Feed?>(null)
    val loadingFullText = MutableStateFlow(false)
    /** Show the extracted page instead of the feed's own text, when there is one. */
    val preferFullText = MutableStateFlow(true)
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    private val downloads = app.container.downloads
    val download = downloads.progress.map { it[id] }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    init {
        viewModelScope.launch {
            repo.setRead(listOf(id), true)
            val loaded = repo.articleDao.observe(id).filterNotNull().first()
            val f = repo.feedDao.byId(loaded.feedId)
            feed.value = f
            if (f?.fetchFullText == true && loaded.fullText == null && loaded.link != null) loadFullText(silent = true)
        }
    }

    fun toggleStar() {
        val a = article.value ?: return
        viewModelScope.launch { repo.setStarred(id, !a.starred) }
    }

    fun saveNote(text: String) {
        viewModelScope.launch { repo.setNote(id, text) }
    }

    fun togglePlayed() {
        val a = article.value ?: return
        viewModelScope.launch {
            val played = a.playedAt == 0L
            repo.setPlayed(id, played)
            if (played) downloads.onPlayed(id)
        }
    }

    fun download() = downloads.enqueue(listOf(id))

    fun cancelDownload() = downloads.cancel(id)

    fun deleteDownload() {
        viewModelScope.launch { downloads.delete(listOf(id)) }
    }

    /** Archiving leaves the article, like marking it unread; the archive in the drawer has it. */
    fun toggleArchived(then: () -> Unit) {
        val a = article.value ?: return
        viewModelScope.launch {
            val archive = a.archivedAt == 0L
            repo.setArchived(listOf(id), archive)
            if (archive) {
                downloads.delete(listOf(id))
                then()
            }
        }
    }

    fun markUnread(then: () -> Unit) {
        viewModelScope.launch {
            repo.setRead(listOf(id), false)
            then()
        }
    }

    fun loadFullText(silent: Boolean = false) {
        if (loadingFullText.value) return
        loadingFullText.value = true
        viewModelScope.launch {
            try {
                val found = repo.loadFullText(id)
                if (found) preferFullText.value = true
                else if (!silent) messages.tryEmit("Auf der Seite wurde kein Artikeltext gefunden")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!silent) messages.tryEmit("Volltext konnte nicht geladen werden: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                loadingFullText.value = false
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(onBack: () -> Unit, onOpenArticle: (Long) -> Unit, vm: ReaderViewModel = viewModel()) {
    val article by vm.article.collectAsStateWithLifecycle()
    val feed by vm.feed.collectAsStateWithLifecycle()
    val loading by vm.loadingFullText.collectAsStateWithLifecycle()
    val preferFullText by vm.preferFullText.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var noteOpen by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }

    val a = article
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = { MiniPlayer(onOpenArticle = { if (it != a?.id) onOpenArticle(it) }) },
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
                    }
                },
                actions = {
                    if (a == null) return@TopAppBar
                    IconButton(onClick = vm::toggleStar) {
                        if (a.starred) {
                            Icon(Icons.Default.Star, "Nicht mehr merken", tint = MaterialTheme.colorScheme.primary)
                        } else {
                            Icon(AppIcons.StarBorder, "Merken")
                        }
                    }
                    IconButton(onClick = { noteOpen = true }) {
                        Icon(
                            Icons.Default.Edit,
                            contentDescription = if (a.note.isNullOrBlank()) "Notiz hinzufügen" else "Notiz bearbeiten",
                            tint = if (a.note.isNullOrBlank()) MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.primary,
                        )
                    }
                    IconButton(onClick = {
                        val text = listOfNotNull(a.title, a.link).joinToString("\n")
                        val send = Intent(Intent.ACTION_SEND)
                            .setType("text/plain")
                            .putExtra(Intent.EXTRA_SUBJECT, a.title)
                            .putExtra(Intent.EXTRA_TEXT, text)
                        context.startActivity(Intent.createChooser(send, "Artikel teilen"))
                    }) {
                        Icon(Icons.Default.Share, contentDescription = "Teilen")
                    }
                    if (a.link != null) {
                        val showsFullText = a.fullText != null && preferFullText
                        IconButton(
                            enabled = !loading,
                            onClick = {
                                when {
                                    a.fullText == null -> vm.loadFullText()
                                    else -> vm.preferFullText.value = !preferFullText
                                }
                            },
                        ) {
                            Icon(
                                AppIcons.FullText,
                                contentDescription = when {
                                    a.fullText == null -> "Volltext laden"
                                    showsFullText -> "Feed-Text anzeigen"
                                    else -> "Volltext anzeigen"
                                },
                                tint = if (showsFullText) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    IconButton(onClick = { vm.toggleArchived(onBack) }) {
                        if (a.archivedAt > 0) {
                            Icon(AppIcons.Unarchive, contentDescription = "Aus dem Archiv holen")
                        } else {
                            Icon(AppIcons.Archive, contentDescription = "Archivieren")
                        }
                    }
                    IconButton(onClick = { vm.markUnread(onBack) }) {
                        Icon(AppIcons.MarkUnread, contentDescription = "Als ungelesen markieren")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
          if (a?.enclosureUrl != null) {
              val download by vm.download.collectAsStateWithLifecycle()
              EpisodePanel(a, download, vm)
          }
          Box(Modifier.weight(1f).fillMaxWidth()) {
            if (a != null) {
                val colors = MaterialTheme.colorScheme
                val body = (if (preferFullText) a.fullText else null) ?: a.content
                val html = remember(a.id, a.title, a.note, body, feed, colors) {
                    articleHtml(
                        article = a,
                        feedTitle = feed?.title,
                        body = body,
                        text = colors.onSurface,
                        muted = colors.onSurfaceVariant,
                        link = colors.primary,
                        noteBackground = colors.secondaryContainer,
                        noteText = colors.onSecondaryContainer,
                    )
                }
                ArticleWebView(html = html, baseUrl = a.link, modifier = Modifier.fillMaxSize())
            }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
          }
        }
    }

    if (noteOpen && a != null) {
        NoteDialog(
            initial = a.note.orEmpty(),
            onSave = { vm.saveNote(it); noteOpen = false },
            onDismiss = { noteOpen = false },
        )
    }
}

/** Play button and progress of a podcast episode, above its show notes. */
@Composable
private fun EpisodePanel(a: Article, download: DownloadProgress?, vm: ReaderViewModel) {
    val state = playerState()
    // While this episode is loaded, the player knows better than the stored position.
    val live = state.articleId == a.id && state.durationMs > 0
    val label = if (live) {
        Episodes.clock(state.positionMs) + " / " + Episodes.clock(state.durationMs)
    } else {
        Episodes.label(a.durationSeconds, a.playPosition, a.playedAt)
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 8.dp),
    ) {
        EpisodePlayButton(a.id, size = 48.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = if (state.isPlaying(a.id)) "Läuft" else if (a.playPosition > 0 && a.playedAt == 0L) "Weiterhören" else "Abspielen",
                style = MaterialTheme.typography.titleSmall,
            )
            if (label != null) {
                Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        SpeedButton()
        QueueButton(a.id)
        EpisodeDownloadButton(
            downloaded = a.downloadPath != null,
            download = download,
            onDownload = vm::download,
            onCancel = vm::cancelDownload,
            onDelete = vm::deleteDownload,
        )
        IconButton(onClick = vm::togglePlayed) {
            Icon(
                AppIcons.Played,
                contentDescription = if (a.playedAt > 0) "Als nicht gehört markieren" else "Als gehört markieren",
                tint = if (a.playedAt > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun NoteDialog(initial: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var text by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Notiz") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text("Notiz zu diesem Artikel") },
                modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp),
            )
        },
        confirmButton = { TextButton(onClick = { onSave(text) }) { Text("Speichern") } },
        dismissButton = {
            if (initial.isNotBlank()) TextButton(onClick = { onSave("") }) { Text("Löschen") }
            TextButton(onClick = onDismiss) { Text("Abbrechen") }
        },
    )
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun ArticleWebView(html: String, baseUrl: String?, modifier: Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = false
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                // Pictures that were shown once stay readable without a connection.
                settings.cacheMode = WebSettings.LOAD_CACHE_ELSE_NETWORK
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        context.openInBrowser(request.url.toString())
                        return true
                    }
                }
            }
        },
        update = { webView ->
            if (webView.tag != html) {
                webView.tag = html
                webView.loadDataWithBaseURL(baseUrl, html, "text/html", "utf-8", null)
            }
        },
        onRelease = { it.destroy() },
    )
}

private fun Color.css(): String = String.format("#%06X", 0xFFFFFF and toArgb())

internal fun articleHtml(
    article: Article,
    feedTitle: String?,
    body: String?,
    text: Color,
    muted: Color,
    link: Color,
    noteBackground: Color,
    noteText: Color,
): String {
    val date = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(article.pubDate))
    val meta = listOfNotNull(feedTitle, article.author, date).joinToString(" · ") { TextUtils.htmlEncode(it) }
    val note = article.note?.takeIf { it.isNotBlank() }
        ?.let { "<div class=\"note\"><b>Notiz</b><br>${TextUtils.htmlEncode(it)}</div>" }.orEmpty()
    val content = body?.takeIf { it.isNotBlank() }
        ?: "<p class=\"meta\">Dieser Feed liefert keinen Text. Das Textsymbol oben lädt den Volltext.</p>"
    // The headline links to the article; the WebView hands links to the browser.
    val title = TextUtils.htmlEncode(article.title)
    val headline = article.link?.let { "<a href=\"${TextUtils.htmlEncode(it)}\">$title</a>" } ?: title
    return """
        <!DOCTYPE html><html><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <style>
          body { font-family: sans-serif; font-size: 17px; line-height: 1.55; color: ${text.css()};
                 margin: 0; padding: 8px 16px 48px; overflow-wrap: break-word; }
          h1 { font-size: 1.4em; line-height: 1.25; margin: 0 0 8px; }
          h1 a { color: inherit; text-decoration: none; }
          h2, h3, h4 { line-height: 1.3; }
          .meta { color: ${muted.css()}; font-size: 0.85em; }
          .note { background: ${noteBackground.css()}; color: ${noteText.css()}; border-radius: 12px;
                  padding: 12px 14px; margin: 16px 0; white-space: pre-wrap; }
          a { color: ${link.css()}; }
          img, video, iframe { max-width: 100%; height: auto; border-radius: 8px; }
          figure { margin: 16px 0; } figcaption { color: ${muted.css()}; font-size: 0.85em; }
          pre { white-space: pre-wrap; } table { display: block; overflow-x: auto; }
          blockquote { margin: 16px 0; padding-left: 14px; border-left: 3px solid ${muted.css()}; }
        </style></head><body>
        <h1>$headline</h1>
        <div class="meta">$meta</div>
        $note
        $content
        </body></html>
    """.trimIndent()
}
