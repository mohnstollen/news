package io.github.mohnstollen.news.ui

import android.app.Application
import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.width
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import io.github.mohnstollen.news.data.ANY
import io.github.mohnstollen.news.data.ARCHIVED
import io.github.mohnstollen.news.data.ArticleListItem
import io.github.mohnstollen.news.data.NOT_ARCHIVED
import io.github.mohnstollen.news.playback.DownloadProgress
import io.github.mohnstollen.news.data.FeedWithUnread
import io.github.mohnstollen.news.data.ReadVisibility
import io.github.mohnstollen.news.data.SearchQuery
import io.github.mohnstollen.news.ui.MainViewModel.ListScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class MainViewModel(app: Application) : AndroidViewModel(app) {

    sealed interface ListScope {
        data object All : ListScope
        data object Starred : ListScope
        /** Archived articles: left out of all other lists. */
        data object Archive : ListScope
        data class Single(val feedId: Long) : ListScope
    }

    private val container = app.container
    private val repo = container.repository
    private val settings = container.settings

    val prefs = settings.flow
    val refreshing = repo.refreshing
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 4)

    private val _scope = MutableStateFlow<ListScope>(ListScope.All)
    val scope: StateFlow<ListScope> = _scope

    /**
     * Articles that were marked read while the current list is on screen. With the "unread only"
     * filter they stay visible until the list is reloaded, so nothing jumps while scrolling.
     */
    private val keepVisible = MutableStateFlow<Set<Long>>(emptySet())

    val feeds = repo.feedDao.observeWithUnread()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val unreadTotal = repo.articleDao.observeUnreadCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)
    val starredTotal = repo.articleDao.observeStarredCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)
    val downloads = container.downloads.progress
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())
    val queueSize = repo.queueDao.observeIds().map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    fun enqueue(item: ArticleListItem) {
        viewModelScope.launch {
            repo.enqueue(item.id)
            messages.tryEmit("„${item.title}“ in der Warteschlange")
        }
    }

    /** Archiving from the list, for the "undo" of the snackbar: (message, ids, archived). */
    val archived = MutableSharedFlow<Triple<String, List<Long>, Boolean>>(extraBufferCapacity = 4)

    /** Quick filter: only articles published within [RECENT_WINDOW_MS]. */
    private val _recentOnly = MutableStateFlow(false)
    val recentOnly: StateFlow<Boolean> = _recentOnly

    /** Quick filter of the "all articles" list: only this folder. */
    private val _folder = MutableStateFlow<String?>(null)
    val folder: StateFlow<String?> = _folder

    fun setRecentOnly(value: Boolean) {
        keepVisible.value = emptySet()
        _recentOnly.value = value
    }

    fun setFolder(value: String?) {
        keepVisible.value = emptySet()
        _folder.value = value
    }

    /** What is typed into the search field; null while the search is closed. */
    private val _search = MutableStateFlow<String?>(null)
    val search: StateFlow<String?> = _search

    /** null while loading. While searching, the hits within the current scope, read ones included. */
    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    val articles: StateFlow<List<ArticleListItem>?> = _scope
        .combine(
            _search.map { it?.let(SearchQuery::toMatch) }.distinctUntilChanged()
                // Wait for a pause in typing; closing the search takes effect at once.
                .debounce { if (it == null) 0L else 250L },
        ) { scope, match -> scope to match }
        .flatMapLatest { (scope, match) ->
            val filter = prefs.map { ListPrefs(it.unreadOnly, it.newestFirst, it.hideReadAfterMinutes) }
                .distinctUntilChanged()
            val feedId = (scope as? ListScope.Single)?.feedId
            if (match != null) {
                return@flatMapLatest combine(
                    repo.articleDao.observeSearch(match, feedId, scope is ListScope.Starred),
                    filter,
                ) { hits, listPrefs -> if (listPrefs.newestFirst) hits else hits.asReversed() }
            }
            combine(
                repo.articleDao.observeList(
                    (scope as? ListScope.Single)?.feedId,
                    scope is ListScope.Starred,
                    when (scope) {
                        ListScope.Archive -> ARCHIVED
                        // A starred article stays in the starred list even when archived.
                        ListScope.Starred -> ANY
                        else -> NOT_ARCHIVED
                    },
                ),
                filter,
                keepVisible,
                _recentOnly,
                _folder,
            ) { all, (unreadOnly, newestFirst, hideReadAfter), keep, recentOnly, folder ->
                val now = System.currentTimeMillis()
                // The quick filters belong to the article lists; starred and archive show everything.
                val filterable = scope !is ListScope.Starred && scope !is ListScope.Archive
                val since = if (recentOnly && filterable) now - RECENT_WINDOW_MS else null
                val inFolder = folder.takeIf { scope is ListScope.All }
                val filtered = all.filter { item ->
                    (!unreadOnly || !filterable || !item.read || item.id in keep) &&
                        // Read articles drop out after the time from the settings. The search still finds them.
                        (!filterable || item.id in keep ||
                            ReadVisibility.isVisible(item.read, item.readAt, hideReadAfter, now)) &&
                        (since == null || item.pubDate >= since) &&
                        (inFolder == null || item.category == inFolder)
                }
                if (newestFirst) filtered else filtered.asReversed()
            }
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun openSearch() {
        if (_search.value == null) _search.value = ""
    }

    fun closeSearch() {
        _search.value = null
    }

    fun setSearch(text: String) {
        _search.value = text
    }

    fun select(scope: ListScope) {
        keepVisible.value = emptySet()
        _scope.value = scope
    }

    fun setUnreadOnly(value: Boolean) {
        keepVisible.value = emptySet()
        settings.update { it.copy(unreadOnly = value) }
    }

    fun setNewestFirst(value: Boolean) = settings.update { it.copy(newestFirst = value) }

    fun setMarkReadOnScroll(value: Boolean) = settings.update { it.copy(markReadOnScroll = value) }

    fun markRead(ids: List<Long>) {
        keepVisible.update { it + ids }
        viewModelScope.launch { repo.setRead(ids, true) }
    }

    fun toggleRead(item: ArticleListItem) {
        keepVisible.update { it + item.id }
        viewModelScope.launch { repo.setRead(listOf(item.id), !item.read) }
    }

    /** Archives an article, or brings it back from the archive; offers to undo. */
    fun toggleArchived(item: ArticleListItem) = setArchived(listOf(item.id), !item.archived, announce = true)

    fun setArchived(ids: List<Long>, archived: Boolean, announce: Boolean = false) {
        viewModelScope.launch {
            repo.setArchived(ids, archived)
            if (archived) container.downloads.delete(ids)
            if (announce) this@MainViewModel.archived.tryEmit(Triple(if (archived) "Archiviert" else "Aus dem Archiv geholt", ids, archived))
        }
    }

    fun toggleStar(item: ArticleListItem) {
        viewModelScope.launch { repo.setStarred(item.id, !item.starred) }
    }

    /** With a "new" or folder filter on, only what the list shows is marked. */
    fun narrowed(): Boolean {
        val scope = _scope.value
        return (_recentOnly.value && scope !is ListScope.Starred) || (_folder.value != null && scope is ListScope.All)
    }

    fun markAllRead() {
        val scope = _scope.value
        val shown = if (narrowed()) articles.value.orEmpty().filter { !it.read }.map { it.id } else null
        keepVisible.value = emptySet()
        viewModelScope.launch {
            if (shown != null) repo.setRead(shown, true)
            else repo.articleDao.markAllRead(
                (scope as? ListScope.Single)?.feedId, scope is ListScope.Starred, System.currentTimeMillis(),
            )
        }
    }

    private data class ListPrefs(val unreadOnly: Boolean, val newestFirst: Boolean, val hideReadAfterMinutes: Int)

    companion object {
        const val RECENT_WINDOW_MS = 3 * 60 * 60 * 1000L
    }

    fun refresh() {
        keepVisible.value = emptySet()
        val feedId = (_scope.value as? ListScope.Single)?.feedId
        repo.scope.launch {
            val result = repo.refresh(feedId)
            if (result.skipped) return@launch
            val w = container.features
            val news = when (result.newArticles) {
                0 -> w.pick("Keine neuen Artikel", "Keine neuen Folgen")
                1 -> w.pick("1 neuer Artikel", "1 neue Folge")
                else -> "${result.newArticles} " + w.pick("neue Artikel", "neue Folgen")
            }
            val errors = when (result.failedFeeds) {
                0 -> ""
                1 -> " · " + w.pick("1 Feed", "1 Podcast") + " nicht erreichbar"
                else -> " · ${result.failedFeeds} " + w.pick("Feeds", "Podcasts") + " nicht erreichbar"
            }
            messages.tryEmit(news + errors)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArticleListScreen(
    vm: MainViewModel,
    onOpenArticle: (Long) -> Unit,
    onOpenFeeds: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenScreen: (String) -> Unit = {},
) {
    val features = LocalContext.current.features
    val scope by vm.scope.collectAsStateWithLifecycle()
    val feeds by vm.feeds.collectAsStateWithLifecycle()
    val articles by vm.articles.collectAsStateWithLifecycle()
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val unreadTotal by vm.unreadTotal.collectAsStateWithLifecycle()
    val starredTotal by vm.starredTotal.collectAsStateWithLifecycle()
    val search by vm.search.collectAsStateWithLifecycle()
    val searching = search != null
    val recentOnly by vm.recentOnly.collectAsStateWithLifecycle()
    val folder by vm.folder.collectAsStateWithLifecycle()
    val folders = remember(feeds) {
        feeds.mapNotNull { it.feed.category }.distinct().sortedWith(String.CASE_INSENSITIVE_ORDER)
    }
    // The chosen folder is gone, e.g. its last feed was deleted or moved.
    LaunchedEffect(folder, folders) {
        if (folder != null && feeds.isNotEmpty() && folder !in folders) vm.setFolder(null)
    }
    val narrowed = (recentOnly && scope !is ListScope.Starred) || (folder != null && scope == ListScope.All)
    val hasQuery = !search.isNullOrBlank()

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val coroutineScope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var menuOpen by remember { mutableStateOf(false) }
    var confirmMarkAll by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(Unit) {
        vm.archived.collect { (message, ids, archived) ->
            val result = snackbar.showSnackbar(message, actionLabel = "Rückgängig", duration = SnackbarDuration.Short)
            if (result == SnackbarResult.ActionPerformed) vm.setArchived(ids, !archived)
        }
    }
    val downloads by vm.downloads.collectAsStateWithLifecycle()
    val queueSize by vm.queueSize.collectAsStateWithLifecycle()
    BackHandler(enabled = searching) { vm.closeSearch() }
    BackHandler(enabled = drawerState.isOpen) { coroutineScope.launch { drawerState.close() } }
    // The selected feed was deleted in the meantime.
    LaunchedEffect(scope, feeds) {
        val s = scope
        if (s is ListScope.Single && feeds.isNotEmpty() && feeds.none { it.feed.id == s.feedId }) vm.select(ListScope.All)
    }

    val title = when (val s = scope) {
        ListScope.All -> features.allItems
        ListScope.Starred -> "Gemerkt"
        ListScope.Archive -> "Archiv"
        is ListScope.Single -> feeds.firstOrNull { it.feed.id == s.feedId }?.feed?.title ?: "Feed"
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            FeedDrawer(
                feeds = feeds,
                selected = scope,
                unreadTotal = unreadTotal,
                starredTotal = starredTotal,
                onSelect = {
                    vm.select(it)
                    coroutineScope.launch { drawerState.close() }
                },
                onOpenFeeds = {
                    coroutineScope.launch { drawerState.close() }
                    onOpenFeeds()
                },
                onOpenSettings = {
                    coroutineScope.launch { drawerState.close() }
                    onOpenSettings()
                },
                queueSize = queueSize,
                onOpenScreen = {
                    coroutineScope.launch { drawerState.close() }
                    onOpenScreen(it)
                },
            )
        },
    ) {
        val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
        Scaffold(
            modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = { MiniPlayer(onOpenArticle = onOpenArticle) },
            topBar = {
                if (searching) {
                    SearchBar(
                        text = search.orEmpty(),
                        placeholder = if (scope == ListScope.All) features.pick("Alle Artikel durchsuchen", "Alle Folgen durchsuchen") else "In „$title“ suchen",
                        onChange = vm::setSearch,
                        onClose = vm::closeSearch,
                    )
                } else TopAppBar(
                    title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    scrollBehavior = scrollBehavior,
                    navigationIcon = {
                        IconButton(onClick = { coroutineScope.launch { drawerState.open() } }) {
                            Icon(Icons.Default.Menu, contentDescription = features.pick("Feeds anzeigen", "Podcasts anzeigen"))
                        }
                    },
                    actions = {
                        IconButton(onClick = vm::openSearch) {
                            Icon(Icons.Default.Search, contentDescription = "Suchen")
                        }
                        if (scope != ListScope.Archive) {
                            IconButton(onClick = { confirmMarkAll = true }) {
                                Icon(AppIcons.DoneAll, contentDescription = features.pick("Alle als gelesen markieren", "Alle als erledigt markieren"))
                            }
                        }
                        IconButton(onClick = vm::refresh, enabled = !refreshing) {
                            Icon(Icons.Default.Refresh, contentDescription = "Aktualisieren")
                        }
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "Ansicht")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            CheckMenuItem("Neueste zuerst", prefs.newestFirst) {
                                vm.setNewestFirst(true); menuOpen = false
                            }
                            CheckMenuItem("Älteste zuerst", !prefs.newestFirst) {
                                vm.setNewestFirst(false); menuOpen = false
                            }
                            HorizontalDivider()
                            CheckMenuItem(features.pick("Beim Scrollen als gelesen markieren", "Beim Scrollen als erledigt markieren"), prefs.markReadOnScroll) {
                                vm.setMarkReadOnScroll(!prefs.markReadOnScroll); menuOpen = false
                            }
                        }
                    },
                )
            },
        ) { padding ->
          Column(Modifier.padding(padding).fillMaxSize()) {
            if (!searching && scope !is ListScope.Starred && scope !is ListScope.Archive && feeds.isNotEmpty()) {
                QuickFilters(
                    unreadOnly = prefs.unreadOnly,
                    onUnreadOnly = vm::setUnreadOnly,
                    recentOnly = recentOnly,
                    onRecentOnly = vm::setRecentOnly,
                    // Folders only make sense across feeds.
                    folders = if (scope == ListScope.All) folders else emptyList(),
                    folder = folder,
                    onFolder = vm::setFolder,
                )
            }
            PullToRefreshBox(
                isRefreshing = refreshing,
                onRefresh = vm::refresh,
                modifier = Modifier.fillMaxSize(),
            ) {
                val items = articles
                when {
                    items == null -> Unit
                    items.isEmpty() -> EmptyState(
                        text = when {
                            hasQuery -> "Keine Treffer für „${search.orEmpty().trim()}“."
                            feeds.isEmpty() -> features.pick("Noch keine Feeds abonniert.", "Noch keine Podcasts abonniert.")
                            scope is ListScope.Starred -> features.pick(
                                "Noch keine Artikel gemerkt.\nTippe bei einem Artikel auf den Stern.",
                                "Noch keine Folgen gemerkt.\nTippe bei einer Folge auf den Stern.",
                            )
                            scope is ListScope.Archive ->
                                features.pick(
                                    "Das Archiv ist leer.\nWische einen Artikel in der Liste nach links, um ihn zu archivieren.",
                                    "Das Archiv ist leer.\nWische eine Folge in der Liste nach links, um sie zu archivieren.",
                                )
                            narrowed -> features.pick("Keine Artikel für diese Filter.", "Keine Folgen für diese Filter.")
                            prefs.unreadOnly || prefs.hideReadAfterMinutes != ReadVisibility.NEVER ->
                                features.pick("Keine ungelesenen Artikel.", "Keine offenen Folgen.")
                            else -> features.pick("Keine Artikel.", "Keine Folgen.") + " Zum Aktualisieren nach unten ziehen."
                        },
                        action = if (feeds.isEmpty() && !hasQuery) features.addFeed to onOpenFeeds else null,
                    )
                    else -> ArticleList(
                        items = items,
                        // Search results keep their own scroll position, and browsing them marks nothing.
                        listKey = if (hasQuery) "search" to scope else Triple(scope, recentOnly, folder),
                        markReadOnScroll = prefs.markReadOnScroll && !hasQuery,
                        onScrolledPast = vm::markRead,
                        onOpen = { onOpenArticle(it.id) },
                        onToggleRead = vm::toggleRead,
                        onToggleStar = vm::toggleStar,
                        onToggleArchived = vm::toggleArchived,
                        onEnqueue = vm::enqueue,
                        downloads = downloads,
                    )
                }
            }
          }
        }
    }

    if (confirmMarkAll) {
        AlertDialog(
            onDismissRequest = { confirmMarkAll = false },
            title = { Text(features.pick("Als gelesen markieren?", "Als erledigt markieren?")) },
            text = {
                Text(
                    if (narrowed) {
                        features.pick("Alle Artikel", "Alle Folgen") + ", die die Filter gerade anzeigen, werden als " +
                            features.pick("gelesen", "erledigt") + " markiert."
                    } else {
                        features.pick("Alle Artikel", "Alle Folgen") + " in „$title“ werden als " +
                            features.pick("gelesen", "erledigt") + " markiert."
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = { vm.markAllRead(); confirmMarkAll = false }) { Text("Markieren") }
            },
            dismissButton = { TextButton(onClick = { confirmMarkAll = false }) { Text("Abbrechen") } },
        )
    }
}

/** Chips above the list: unread only, published in the last hours, one folder. They combine. */
@Composable
private fun QuickFilters(
    unreadOnly: Boolean,
    onUnreadOnly: (Boolean) -> Unit,
    recentOnly: Boolean,
    onRecentOnly: (Boolean) -> Unit,
    folders: List<String>,
    folder: String?,
    onFolder: (String?) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = unreadOnly,
            onClick = { onUnreadOnly(!unreadOnly) },
            label = { Text(LocalContext.current.features.pick("Ungelesen", "Offen")) },
            leadingIcon = if (unreadOnly) checkIcon else null,
        )
        FilterChip(
            selected = recentOnly,
            onClick = { onRecentOnly(!recentOnly) },
            label = { Text("Neu (${MainViewModel.RECENT_WINDOW_MS / 3_600_000} Std.)") },
            leadingIcon = if (recentOnly) checkIcon else null,
        )
        if (folders.isNotEmpty()) {
            var open by remember { mutableStateOf(false) }
            Box {
                FilterChip(
                    selected = folder != null,
                    onClick = { open = true },
                    label = { Text(folder ?: "Ordner", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingIcon = if (folder != null) checkIcon else null,
                    trailingIcon = {
                        Icon(Icons.Default.ArrowDropDown, contentDescription = "Ordner wählen", Modifier.size(18.dp))
                    },
                )
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    DropdownMenuItem(text = { Text("Alle Ordner") }, onClick = { onFolder(null); open = false })
                    folders.forEach { name ->
                        DropdownMenuItem(text = { Text(name) }, onClick = { onFolder(name); open = false })
                    }
                }
            }
        }
    }
}

private val checkIcon: @Composable () -> Unit = {
    Icon(Icons.Default.Check, contentDescription = null, Modifier.size(18.dp))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchBar(text: String, placeholder: String, onChange: (String) -> Unit, onClose: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    TopAppBar(
        navigationIcon = {
            IconButton(onClick = onClose) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Suche schließen")
            }
        },
        title = {
            TextField(
                value = text,
                onValueChange = onChange,
                placeholder = { Text(placeholder, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        },
        actions = {
            if (text.isNotEmpty()) {
                IconButton(onClick = { onChange("") }) {
                    Icon(Icons.Default.Close, contentDescription = "Eingabe löschen")
                }
            }
        },
    )
}

@Composable
private fun CheckMenuItem(text: String, checked: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(text) },
        onClick = onClick,
        leadingIcon = {
            if (checked) Icon(Icons.Default.Check, contentDescription = "aktiv") else Spacer(Modifier.size(24.dp))
        },
    )
}

@Composable
private fun EmptyState(text: String, action: Pair<String, () -> Unit>?) {
    // Scrollable so that pull-to-refresh also works on an empty list.
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (action != null) {
            Spacer(Modifier.height(16.dp))
            Button(onClick = action.second) { Text(action.first) }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ArticleList(
    items: List<ArticleListItem>,
    listKey: Any,
    markReadOnScroll: Boolean,
    onScrolledPast: (List<Long>) -> Unit,
    onOpen: (ArticleListItem) -> Unit,
    onToggleRead: (ArticleListItem) -> Unit,
    onToggleStar: (ArticleListItem) -> Unit,
    onToggleArchived: (ArticleListItem) -> Unit,
    onEnqueue: (ArticleListItem) -> Unit,
    downloads: Map<Long, DownloadProgress>,
) {
    val listState = rememberSaveable(listKey, saver = LazyListState.Saver) { LazyListState() }
    val currentItems by rememberUpdatedState(items)

    // Stay at the top when new articles arrive there, instead of being pushed down with the old ones.
    val firstId = items.firstOrNull()?.id
    val lastFirstId = remember(listKey) { mutableStateOf(firstId) }
    if (firstId != lastFirstId.value) {
        lastFirstId.value = firstId
        val atTop = Snapshot.withoutReadObservation {
            listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
        }
        if (atTop) listState.requestScrollToItem(0)
    }

    // Mark what the user scrolled past. The position is tracked by article, not by index: when
    // the list merely shifts because articles came in or went away, the article at the top is
    // still the same one (or the old one is gone), and nothing is marked.
    LaunchedEffect(listState, markReadOnScroll) {
        if (!markReadOnScroll) return@LaunchedEffect
        var previousFirst: Long? = null
        snapshotFlow {
            val index = listState.firstVisibleItemIndex
            index to currentItems.getOrNull(index)?.id
        }.collect { (index, firstId) ->
            val list = currentItems
            val previousIndex = previousFirst?.let { id -> list.indexOfFirst { it.id == id } } ?: -1
            if (previousIndex in 0 until index && index <= list.size) {
                val ids = list.subList(previousIndex, index).filter { !it.read }.map { it.id }
                if (ids.isNotEmpty()) onScrolledPast(ids)
            }
            previousFirst = firstId
        }
    }

    val now = remember(items) { System.currentTimeMillis() }
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
        items(items, key = { it.id }) { item ->
            val words = LocalContext.current.features
            ArchiveSwipe(item, onArchive = { onToggleArchived(item) }, onEnqueue = { onEnqueue(item) }) {
                ArticleRow(
                    item = item,
                    now = now,
                    download = downloads[item.id],
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.surface)
                        .combinedClickable(
                            onClick = { onOpen(item) },
                            onLongClick = { onToggleRead(item) },
                            onLongClickLabel = if (item.read) {
                                words.pick("Als ungelesen markieren", "Als offen markieren")
                            } else {
                                words.pick("Als gelesen markieren", "Als erledigt markieren")
                            },
                        )
                        .semantics {
                            customActions = listOf(
                                CustomAccessibilityAction(if (item.archived) "Aus dem Archiv holen" else "Archivieren") {
                                    onToggleArchived(item); true
                                },
                            )
                        },
                    onToggleStar = { onToggleStar(item) },
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        }
    }
}

/**
 * Swiping a row to the left archives it, or in the archive brings it back. Swiping an episode to
 * the right puts it into the queue.
 */
@Composable
private fun ArchiveSwipe(
    item: ArticleListItem,
    onArchive: () -> Unit,
    onEnqueue: () -> Unit,
    content: @Composable () -> Unit,
) {
    val currentOnArchive by rememberUpdatedState(onArchive)
    val currentOnEnqueue by rememberUpdatedState(onEnqueue)
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = {
            if (it == SwipeToDismissBoxValue.EndToStart) currentOnArchive()
            if (it == SwipeToDismissBoxValue.StartToEnd) currentOnEnqueue()
            // Never settle as dismissed: the list keeps this state per article, and a row that comes
            // back (undo, archive view) would stay swiped away. The list removes the row itself.
            false
        },
    )
    SwipeToDismissBox(
        state = state,
        enableDismissFromStartToEnd = item.hasAudio && !item.archived && LocalContext.current.features.podcasts,
        backgroundContent = {
            if (state.dismissDirection == SwipeToDismissBoxValue.StartToEnd) {
                Row(
                    Modifier.fillMaxSize().background(MaterialTheme.colorScheme.primaryContainer).padding(horizontal = 24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(AppIcons.QueueAdd, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Zur Warteschlange",
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
                return@SwipeToDismissBox
            }
            Row(
                Modifier.fillMaxSize().background(MaterialTheme.colorScheme.secondaryContainer).padding(horizontal = 24.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val label = if (item.archived) "Wiederherstellen" else "Archivieren"
                Text(label, color = MaterialTheme.colorScheme.onSecondaryContainer, style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.width(8.dp))
                Icon(
                    if (item.archived) AppIcons.Unarchive else AppIcons.Archive,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        },
    ) { content() }
}

@Composable
private fun ArticleRow(
    item: ArticleListItem,
    now: Long,
    download: DownloadProgress?,
    modifier: Modifier,
    onToggleStar: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val titleColor = if (item.read) colors.onSurfaceVariant.copy(alpha = 0.75f) else colors.onSurface
    val secondary = colors.onSurfaceVariant.copy(alpha = if (item.read) 0.6f else 1f)
    Row(modifier = modifier.fillMaxWidth().padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp)) {
        Column(Modifier.weight(1f)) {
            Text(
                text = item.feedTitle + " · " +
                    DateUtils.getRelativeTimeSpanString(item.pubDate, now, DateUtils.MINUTE_IN_MILLIS),
                style = MaterialTheme.typography.labelMedium,
                color = if (item.read) secondary else colors.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = if (item.read) FontWeight.Normal else FontWeight.SemiBold,
                color = titleColor,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            if (item.summary.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = item.summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = secondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (item.hasAudio && LocalContext.current.features.podcasts) {
                EpisodeStatus(
                    articleId = item.id,
                    durationSeconds = item.durationSeconds,
                    playPosition = item.playPosition,
                    playedAt = item.playedAt,
                    downloaded = item.downloaded,
                    download = download,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            IconButton(onClick = onToggleStar) {
                if (item.starred) {
                    Icon(Icons.Default.Star, contentDescription = "Nicht mehr merken", tint = colors.primary)
                } else {
                    Icon(AppIcons.StarBorder, contentDescription = "Merken", tint = colors.onSurfaceVariant)
                }
            }
            if (item.hasNote) {
                Icon(
                    Icons.Default.Edit,
                    contentDescription = "Hat eine Notiz",
                    tint = colors.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

@Composable
private fun FeedDrawer(
    feeds: List<FeedWithUnread>,
    selected: ListScope,
    unreadTotal: Int,
    starredTotal: Int,
    onSelect: (ListScope) -> Unit,
    onOpenFeeds: () -> Unit,
    onOpenSettings: () -> Unit,
    queueSize: Int,
    onOpenScreen: (String) -> Unit,
) {
    val itemPadding = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
    val features = LocalContext.current.features
    // Feeds without a folder first, then folder by folder.
    val groups = remember(feeds) {
        feeds.groupBy { it.feed.category.orEmpty() }.toSortedMap(String.CASE_INSENSITIVE_ORDER)
    }
    ModalDrawerSheet {
        LazyColumn {
            item {
                Text(
                    features.appName,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(start = 28.dp, top = 20.dp, bottom = 12.dp),
                )
            }
            item {
                NavigationDrawerItem(
                    label = { Text(features.allItems) },
                    icon = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = null) },
                    badge = { CountBadge(unreadTotal) },
                    selected = selected == ListScope.All,
                    onClick = { onSelect(ListScope.All) },
                    modifier = itemPadding,
                )
            }
            item {
                NavigationDrawerItem(
                    label = { Text("Gemerkt") },
                    icon = { Icon(Icons.Default.Star, contentDescription = null) },
                    badge = { CountBadge(starredTotal) },
                    selected = selected == ListScope.Starred,
                    onClick = { onSelect(ListScope.Starred) },
                    modifier = itemPadding,
                )
            }
            item {
                NavigationDrawerItem(
                    label = { Text("Archiv") },
                    icon = { Icon(AppIcons.Archive, contentDescription = null) },
                    selected = selected == ListScope.Archive,
                    onClick = { onSelect(ListScope.Archive) },
                    modifier = itemPadding,
                )
            }
            // With tabs, the queue and the settings have their own tab.
            if (features.podcasts && !features.tabs) item {
                NavigationDrawerItem(
                    label = { Text("Warteschlange") },
                    icon = { Icon(AppIcons.QueueAdd, contentDescription = null) },
                    badge = { CountBadge(queueSize) },
                    selected = false,
                    onClick = { onOpenScreen("queue") },
                    modifier = itemPadding,
                )
            }
            item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
            groups.forEach { (category, members) ->
                if (category.isNotEmpty()) {
                    item(key = "category:$category") {
                        Text(
                            category,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 28.dp, top = 12.dp, bottom = 4.dp, end = 28.dp),
                        )
                    }
                }
                items(members, key = { it.feed.id }) { entry ->
                    NavigationDrawerItem(
                        label = { Text(entry.feed.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        badge = { CountBadge(entry.unread) },
                        selected = selected == ListScope.Single(entry.feed.id),
                        onClick = { onSelect(ListScope.Single(entry.feed.id)) },
                        modifier = itemPadding,
                    )
                }
            }
            item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
            item {
                NavigationDrawerItem(
                    label = { Text(features.manageFeeds) },
                    icon = { Icon(Icons.Default.Edit, contentDescription = null) },
                    selected = false,
                    onClick = onOpenFeeds,
                    modifier = itemPadding,
                )
            }
            if (features.podcasts) item {
                NavigationDrawerItem(
                    label = { Text("Statistik") },
                    icon = { Icon(AppIcons.Stats, contentDescription = null) },
                    selected = false,
                    onClick = { onOpenScreen("stats") },
                    modifier = itemPadding,
                )
            }
            if (features.podcasts) item {
                NavigationDrawerItem(
                    label = { Text("Podcasts entdecken") },
                    icon = { Icon(AppIcons.Explore, contentDescription = null) },
                    selected = false,
                    onClick = { onOpenScreen("discover") },
                    modifier = itemPadding,
                )
            }
            if (!features.tabs) item {
                NavigationDrawerItem(
                    label = { Text("Einstellungen") },
                    icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                    selected = false,
                    onClick = onOpenSettings,
                    modifier = itemPadding,
                )
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun CountBadge(count: Int) {
    if (count > 0) Text(if (count > 9999) "9999+" else count.toString())
}
