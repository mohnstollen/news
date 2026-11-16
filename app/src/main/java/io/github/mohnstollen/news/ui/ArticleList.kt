package io.github.mohnstollen.news.ui

import android.app.Application
import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
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
import io.github.mohnstollen.news.data.ArticleListItem
import io.github.mohnstollen.news.data.FeedWithUnread
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
            val filter = prefs.map { it.unreadOnly to it.newestFirst }.distinctUntilChanged()
            val feedId = (scope as? ListScope.Single)?.feedId
            if (match != null) {
                return@flatMapLatest combine(
                    repo.articleDao.observeSearch(match, feedId, scope is ListScope.Starred),
                    filter,
                ) { hits, (_, newestFirst) -> if (newestFirst) hits else hits.asReversed() }
            }
            combine(
                repo.articleDao.observeList((scope as? ListScope.Single)?.feedId, scope is ListScope.Starred),
                filter,
                keepVisible,
            ) { all, (unreadOnly, newestFirst), keep ->
                val filtered =
                    if (unreadOnly && scope !is ListScope.Starred) all.filter { !it.read || it.id in keep } else all
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

    fun toggleStar(item: ArticleListItem) {
        viewModelScope.launch { repo.setStarred(item.id, !item.starred) }
    }

    fun markAllRead() {
        val scope = _scope.value
        keepVisible.value = emptySet()
        viewModelScope.launch {
            repo.articleDao.markAllRead((scope as? ListScope.Single)?.feedId, scope is ListScope.Starred)
        }
    }

    fun refresh() {
        keepVisible.value = emptySet()
        val feedId = (_scope.value as? ListScope.Single)?.feedId
        repo.scope.launch {
            val result = repo.refresh(feedId)
            if (result.skipped) return@launch
            val news = when (result.newArticles) {
                0 -> "Keine neuen Artikel"
                1 -> "1 neuer Artikel"
                else -> "${result.newArticles} neue Artikel"
            }
            val errors = when (result.failedFeeds) {
                0 -> ""
                1 -> " · 1 Feed nicht erreichbar"
                else -> " · ${result.failedFeeds} Feeds nicht erreichbar"
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
) {
    val scope by vm.scope.collectAsStateWithLifecycle()
    val feeds by vm.feeds.collectAsStateWithLifecycle()
    val articles by vm.articles.collectAsStateWithLifecycle()
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val unreadTotal by vm.unreadTotal.collectAsStateWithLifecycle()
    val starredTotal by vm.starredTotal.collectAsStateWithLifecycle()
    val search by vm.search.collectAsStateWithLifecycle()
    val searching = search != null
    val hasQuery = !search.isNullOrBlank()

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val coroutineScope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var menuOpen by remember { mutableStateOf(false) }
    var confirmMarkAll by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }
    BackHandler(enabled = searching) { vm.closeSearch() }
    BackHandler(enabled = drawerState.isOpen) { coroutineScope.launch { drawerState.close() } }
    // The selected feed was deleted in the meantime.
    LaunchedEffect(scope, feeds) {
        val s = scope
        if (s is ListScope.Single && feeds.isNotEmpty() && feeds.none { it.feed.id == s.feedId }) vm.select(ListScope.All)
    }

    val title = when (val s = scope) {
        ListScope.All -> "Alle Artikel"
        ListScope.Starred -> "Gemerkt"
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
            )
        },
    ) {
        val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
        Scaffold(
            modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                if (searching) {
                    SearchBar(
                        text = search.orEmpty(),
                        placeholder = if (scope == ListScope.All) "Alle Artikel durchsuchen" else "In „$title“ suchen",
                        onChange = vm::setSearch,
                        onClose = vm::closeSearch,
                    )
                } else TopAppBar(
                    title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    scrollBehavior = scrollBehavior,
                    navigationIcon = {
                        IconButton(onClick = { coroutineScope.launch { drawerState.open() } }) {
                            Icon(Icons.Default.Menu, contentDescription = "Feeds anzeigen")
                        }
                    },
                    actions = {
                        IconButton(onClick = vm::openSearch) {
                            Icon(Icons.Default.Search, contentDescription = "Suchen")
                        }
                        IconButton(onClick = { confirmMarkAll = true }) {
                            Icon(AppIcons.DoneAll, contentDescription = "Alle als gelesen markieren")
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
                            CheckMenuItem("Nur ungelesene anzeigen", prefs.unreadOnly) {
                                vm.setUnreadOnly(!prefs.unreadOnly); menuOpen = false
                            }
                            CheckMenuItem("Beim Scrollen als gelesen markieren", prefs.markReadOnScroll) {
                                vm.setMarkReadOnScroll(!prefs.markReadOnScroll); menuOpen = false
                            }
                        }
                    },
                )
            },
        ) { padding ->
            PullToRefreshBox(
                isRefreshing = refreshing,
                onRefresh = vm::refresh,
                modifier = Modifier.padding(padding).fillMaxSize(),
            ) {
                val items = articles
                when {
                    items == null -> Unit
                    items.isEmpty() -> EmptyState(
                        text = when {
                            hasQuery -> "Keine Treffer für „${search.orEmpty().trim()}“."
                            feeds.isEmpty() -> "Noch keine Feeds abonniert."
                            scope is ListScope.Starred -> "Noch keine Artikel gemerkt.\nTippe bei einem Artikel auf den Stern."
                            prefs.unreadOnly -> "Keine ungelesenen Artikel."
                            else -> "Keine Artikel. Zum Aktualisieren nach unten ziehen."
                        },
                        action = if (feeds.isEmpty() && !hasQuery) "Feed hinzufügen" to onOpenFeeds else null,
                    )
                    else -> ArticleList(
                        items = items,
                        // Search results keep their own scroll position, and browsing them marks nothing.
                        listKey = if (hasQuery) "search" to scope else scope,
                        markReadOnScroll = prefs.markReadOnScroll && !hasQuery,
                        onScrolledPast = vm::markRead,
                        onOpen = { onOpenArticle(it.id) },
                        onToggleRead = vm::toggleRead,
                        onToggleStar = vm::toggleStar,
                    )
                }
            }
        }
    }

    if (confirmMarkAll) {
        AlertDialog(
            onDismissRequest = { confirmMarkAll = false },
            title = { Text("Als gelesen markieren?") },
            text = { Text("Alle Artikel in „$title“ werden als gelesen markiert.") },
            confirmButton = {
                TextButton(onClick = { vm.markAllRead(); confirmMarkAll = false }) { Text("Markieren") }
            },
            dismissButton = { TextButton(onClick = { confirmMarkAll = false }) { Text("Abbrechen") } },
        )
    }
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
            ArticleRow(
                item = item,
                now = now,
                modifier = Modifier.combinedClickable(
                    onClick = { onOpen(item) },
                    onLongClick = { onToggleRead(item) },
                    onLongClickLabel = if (item.read) "Als ungelesen markieren" else "Als gelesen markieren",
                ),
                onToggleStar = { onToggleStar(item) },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        }
    }
}

@Composable
private fun ArticleRow(item: ArticleListItem, now: Long, modifier: Modifier, onToggleStar: () -> Unit) {
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
) {
    val itemPadding = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
    // Feeds without a folder first, then folder by folder.
    val groups = remember(feeds) {
        feeds.groupBy { it.feed.category.orEmpty() }.toSortedMap(String.CASE_INSENSITIVE_ORDER)
    }
    ModalDrawerSheet {
        LazyColumn {
            item {
                Text(
                    "News",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(start = 28.dp, top = 20.dp, bottom = 12.dp),
                )
            }
            item {
                NavigationDrawerItem(
                    label = { Text("Alle Artikel") },
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
                    label = { Text("Feeds verwalten") },
                    icon = { Icon(Icons.Default.Edit, contentDescription = null) },
                    selected = false,
                    onClick = onOpenFeeds,
                    modifier = itemPadding,
                )
            }
            item {
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
