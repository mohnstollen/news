package io.github.mohnstollen.news.desktop

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalScrollbarStyle
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MarkEmailUnread
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.mohnstollen.news.data.Article
import io.github.mohnstollen.news.data.ArticleListItem
import io.github.mohnstollen.news.data.FeedWithUnread
import io.github.mohnstollen.news.data.ReadVisibility
import io.github.mohnstollen.news.desktop.MainState.ListScope

/** Toolbar button with a tooltip, since desktop users hover rather than long-press. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState(),
    ) {
        IconButton(onClick = onClick, enabled = enabled) {
            Icon(icon, contentDescription = label, tint = if (enabled) tint else tint.copy(alpha = 0.38f))
        }
    }
}

@Composable
fun PaneBar(
    modifier: Modifier = Modifier,
    navigation: (@Composable () -> Unit)? = null,
    title: @Composable () -> Unit,
    actions: @Composable () -> Unit = {},
) {
    Row(
        modifier = modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (navigation != null) navigation() else Spacer(Modifier.width(12.dp))
        Box(Modifier.weight(1f).padding(horizontal = 4.dp)) {
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.material3.LocalTextStyle provides MaterialTheme.typography.titleLarge,
            ) { title() }
        }
        actions()
    }
}

/**
 * The main window: feeds on the left, the article list in the middle and the open article on the
 * right. In a narrow window the article takes the place of the list, as on the phone.
 */
@Composable
fun MainScreen(
    state: MainState,
    images: ImageLoader,
    wide: Boolean,
    showSidebar: Boolean,
    onOpenFeeds: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val listScope by state.listScope.collectAsState()
    val feeds by state.feeds.collectAsState()
    val unreadTotal by state.unreadTotal.collectAsState()
    val starredTotal by state.starredTotal.collectAsState()
    val openId by state.openId.collectAsState()

    // The selected feed was deleted in the meantime.
    LaunchedEffect(listScope, feeds) {
        val s = listScope
        if (s is ListScope.Single && feeds.isNotEmpty() && feeds.none { it.feed.id == s.feedId }) state.select(ListScope.All)
    }

    Row(Modifier.fillMaxSize()) {
        if (showSidebar) {
            Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.width(272.dp).fillMaxHeight()) {
                FeedSidebar(
                    feeds = feeds,
                    selected = listScope,
                    unreadTotal = unreadTotal,
                    starredTotal = starredTotal,
                    onSelect = state::select,
                    onOpenFeeds = onOpenFeeds,
                    onOpenSettings = onOpenSettings,
                )
            }
            VerticalDivider()
        }
        if (wide) {
            Box(Modifier.width(420.dp).fillMaxHeight()) { ArticleListPane(state, onOpenFeeds) }
            VerticalDivider()
            Box(Modifier.weight(1f).fillMaxHeight()) {
                if (openId == null) {
                    Text(
                        "Wähle links einen Artikel aus.\n\nTastatur: J/K nächster/vorheriger Artikel, S merken, " +
                            "M gelesen/ungelesen, V im Browser öffnen, Strg+F suchen, F5 aktualisieren.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.align(Alignment.Center).padding(48.dp),
                    )
                } else ReaderPane(state, images, onClose = null)
            }
        } else {
            Box(Modifier.weight(1f).fillMaxHeight()) {
                if (openId == null) ArticleListPane(state, onOpenFeeds)
                else ReaderPane(state, images, onClose = { state.open(null) })
            }
        }
    }
}

// ---- sidebar ------------------------------------------------------------------------------------

@Composable
private fun FeedSidebar(
    feeds: List<FeedWithUnread>,
    selected: ListScope,
    unreadTotal: Int,
    starredTotal: Int,
    onSelect: (ListScope) -> Unit,
    onOpenFeeds: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val itemPadding = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding).height(44.dp)
    val groups = remember(feeds) {
        feeds.groupBy { it.feed.category.orEmpty() }.toSortedMap(String.CASE_INSENSITIVE_ORDER)
    }
    val listState = rememberLazyListState()
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) {
            LazyColumn(state = listState) {
                item {
                    Row(Modifier.padding(start = 24.dp, top = 16.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(AppIcons.Logo, contentDescription = null, tint = Color.Unspecified, modifier = Modifier.size(28.dp))
                        Spacer(Modifier.width(10.dp))
                        Text("News", style = MaterialTheme.typography.titleLarge)
                    }
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
                item { HorizontalDivider(Modifier.padding(vertical = 8.dp, horizontal = 16.dp)) }
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
                            label = {
                                Text(
                                    entry.feed.title,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = if (entry.feed.lastError != null) MaterialTheme.colorScheme.error else Color.Unspecified,
                                )
                            },
                            badge = { CountBadge(entry.unread) },
                            selected = selected == ListScope.Single(entry.feed.id),
                            onClick = { onSelect(ListScope.Single(entry.feed.id)) },
                            modifier = itemPadding,
                        )
                    }
                }
                item { Spacer(Modifier.height(8.dp)) }
            }
            VerticalScrollbar(
                rememberScrollbarAdapter(listState),
                Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
            )
        }
        HorizontalDivider(Modifier.padding(horizontal = 16.dp))
        NavigationDrawerItem(
            label = { Text("Feeds verwalten") },
            icon = { Icon(Icons.Default.Edit, contentDescription = null) },
            selected = false,
            onClick = onOpenFeeds,
            modifier = itemPadding.padding(top = 4.dp),
        )
        NavigationDrawerItem(
            label = { Text("Einstellungen") },
            icon = { Icon(Icons.Default.Settings, contentDescription = null) },
            selected = false,
            onClick = onOpenSettings,
            modifier = itemPadding.padding(bottom = 8.dp),
        )
    }
}

// ---- article list -------------------------------------------------------------------------------

@Composable
private fun ArticleListPane(state: MainState, onOpenFeeds: () -> Unit) {
    val listScope by state.listScope.collectAsState()
    val feeds by state.feeds.collectAsState()
    val articles by state.articles.collectAsState()
    val prefs by state.prefs.collectAsState()
    val refreshing by state.refreshing.collectAsState()
    val search by state.search.collectAsState()
    val recentOnly by state.recentOnly.collectAsState()
    val folder by state.folder.collectAsState()
    val openId by state.openId.collectAsState()
    val searching = search != null
    val hasQuery = !search.isNullOrBlank()
    val folders = remember(feeds) {
        feeds.mapNotNull { it.feed.category }.distinct().sortedWith(String.CASE_INSENSITIVE_ORDER)
    }
    LaunchedEffect(folder, folders) {
        if (folder != null && feeds.isNotEmpty() && folder !in folders) state.setFolder(null)
    }
    val narrowed = (recentOnly && listScope !is ListScope.Starred) || (folder != null && listScope == ListScope.All)
    var menuOpen by remember { mutableStateOf(false) }
    var confirmMarkAll by remember { mutableStateOf(false) }

    val title = when (val s = listScope) {
        ListScope.All -> "Alle Artikel"
        ListScope.Starred -> "Gemerkt"
        is ListScope.Single -> feeds.firstOrNull { it.feed.id == s.feedId }?.feed?.title ?: "Feed"
    }

    Column(Modifier.fillMaxSize()) {
        if (searching) {
            SearchBar(
                text = search.orEmpty(),
                placeholder = if (listScope == ListScope.All) "Alle Artikel durchsuchen" else "In „$title“ suchen",
                onChange = state::setSearch,
                onClose = state::closeSearch,
            )
        } else PaneBar(
            title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            actions = {
                ToolButton(Icons.Default.Search, "Suchen (Strg+F)", state::openSearch)
                ToolButton(Icons.Default.DoneAll, "Alle als gelesen markieren", { confirmMarkAll = true })
                ToolButton(Icons.Default.Refresh, "Aktualisieren (F5)", state::refresh, enabled = !refreshing)
                Box {
                    ToolButton(Icons.Default.MoreVert, "Ansicht", { menuOpen = true })
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        CheckMenuItem("Neueste zuerst", prefs.newestFirst) { state.setNewestFirst(true); menuOpen = false }
                        CheckMenuItem("Älteste zuerst", !prefs.newestFirst) { state.setNewestFirst(false); menuOpen = false }
                        HorizontalDivider()
                        CheckMenuItem("Beim Scrollen als gelesen markieren", prefs.markReadOnScroll) {
                            state.setMarkReadOnScroll(!prefs.markReadOnScroll); menuOpen = false
                        }
                    }
                }
            },
        )
        if (refreshing) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp)) else Spacer(Modifier.height(2.dp))
        if (!searching && listScope !is ListScope.Starred && feeds.isNotEmpty()) {
            QuickFilters(
                unreadOnly = prefs.unreadOnly,
                onUnreadOnly = state::setUnreadOnly,
                recentOnly = recentOnly,
                onRecentOnly = state::setRecentOnly,
                folders = if (listScope == ListScope.All) folders else emptyList(),
                folder = folder,
                onFolder = state::setFolder,
            )
        }
        val items = articles
        when {
            items == null -> Unit
            items.isEmpty() -> EmptyState(
                text = when {
                    hasQuery -> "Keine Treffer für „${search.orEmpty().trim()}“."
                    feeds.isEmpty() -> "Noch keine Feeds abonniert."
                    listScope is ListScope.Starred -> "Noch keine Artikel gemerkt.\nKlicke bei einem Artikel auf den Stern."
                    narrowed -> "Keine Artikel für diese Filter."
                    prefs.unreadOnly || prefs.hideReadAfterMinutes != ReadVisibility.NEVER -> "Keine ungelesenen Artikel."
                    else -> "Keine Artikel. Zum Aktualisieren F5 drücken."
                },
                action = if (feeds.isEmpty() && !hasQuery) "Feed hinzufügen" to onOpenFeeds else null,
            )
            else -> ArticleList(
                items = items,
                listKey = if (hasQuery) "search" to listScope else Triple(listScope, recentOnly, folder),
                selectedId = openId,
                markReadOnScroll = prefs.markReadOnScroll && !hasQuery,
                onScrolledPast = state::markRead,
                onOpen = { state.open(it.id) },
                onToggleRead = state::toggleRead,
                onToggleStar = state::toggleStar,
            )
        }
    }

    if (confirmMarkAll) {
        AlertDialog(
            onDismissRequest = { confirmMarkAll = false },
            title = { Text("Als gelesen markieren?") },
            text = {
                Text(
                    if (narrowed) "Alle Artikel, die die Filter gerade anzeigen, werden als gelesen markiert."
                    else "Alle Artikel in „$title“ werden als gelesen markiert.",
                )
            },
            confirmButton = { TextButton(onClick = { state.markAllRead(); confirmMarkAll = false }) { Text("Markieren") } },
            dismissButton = { TextButton(onClick = { confirmMarkAll = false }) { Text("Abbrechen") } },
        )
    }
}

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
    val check: @Composable () -> Unit = { Icon(Icons.Default.Check, contentDescription = null, Modifier.size(18.dp)) }
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = unreadOnly,
            onClick = { onUnreadOnly(!unreadOnly) },
            label = { Text("Ungelesen") },
            leadingIcon = if (unreadOnly) check else null,
        )
        FilterChip(
            selected = recentOnly,
            onClick = { onRecentOnly(!recentOnly) },
            label = { Text("Neu (${MainState.RECENT_WINDOW_MS / 3_600_000} Std.)") },
            leadingIcon = if (recentOnly) check else null,
        )
        if (folders.isNotEmpty()) {
            var open by remember { mutableStateOf(false) }
            Box {
                FilterChip(
                    selected = folder != null,
                    onClick = { open = true },
                    label = { Text(folder ?: "Ordner", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingIcon = if (folder != null) check else null,
                    trailingIcon = { Icon(Icons.Default.ArrowDropDown, contentDescription = "Ordner wählen", Modifier.size(18.dp)) },
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

@Composable
private fun SearchBar(text: String, placeholder: String, onChange: (String) -> Unit, onClose: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    PaneBar(
        navigation = { ToolButton(Icons.AutoMirrored.Filled.ArrowBack, "Suche schließen (Esc)", onClose) },
        title = {
            TextField(
                value = text,
                onValueChange = onChange,
                placeholder = { Text(placeholder, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
                modifier = Modifier.fillMaxWidth().focusRequester(focus).onPreviewKeyEvent {
                    if (it.type == KeyEventType.KeyDown && it.key == Key.Escape) { onClose(); true } else false
                },
            )
        },
        actions = { if (text.isNotEmpty()) ToolButton(Icons.Default.Close, "Eingabe löschen", { onChange("") }) },
    )
}

@Composable
private fun EmptyState(text: String, action: Pair<String, () -> Unit>?) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
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
    selectedId: Long?,
    markReadOnScroll: Boolean,
    onScrolledPast: (List<Long>) -> Unit,
    onOpen: (ArticleListItem) -> Unit,
    onToggleRead: (ArticleListItem) -> Unit,
    onToggleStar: (ArticleListItem) -> Unit,
) {
    val listState = remember(listKey) { LazyListState() }
    val currentItems by rememberUpdatedState(items)

    // Stay at the top when new articles arrive there.
    val firstId = items.firstOrNull()?.id
    val lastFirstId = remember(listKey) { mutableStateOf(firstId) }
    if (firstId != lastFirstId.value) {
        lastFirstId.value = firstId
        val atTop = Snapshot.withoutReadObservation {
            listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
        }
        if (atTop) listState.requestScrollToItem(0)
    }

    // Keep the open article in view when it is changed with the keyboard.
    LaunchedEffect(selectedId) {
        val index = currentItems.indexOfFirst { it.id == selectedId }
        if (index < 0) return@LaunchedEffect
        val visible = listState.layoutInfo.visibleItemsInfo
        val fullyVisible = visible.filter { it.offset >= 0 && it.offset + it.size <= listState.layoutInfo.viewportEndOffset }
        if (fullyVisible.none { it.index == index }) {
            listState.animateScrollToItem((index - 1).coerceAtLeast(0))
        }
    }

    // Mark what the user scrolled past; tracked by article, not by index (see the Android app).
    LaunchedEffect(listState, markReadOnScroll) {
        if (!markReadOnScroll) return@LaunchedEffect
        var previousFirst: Long? = null
        snapshotFlow {
            val index = listState.firstVisibleItemIndex
            index to currentItems.getOrNull(index)?.id
        }.collect { (index, first) ->
            val list = currentItems
            val previousIndex = previousFirst?.let { id -> list.indexOfFirst { it.id == id } } ?: -1
            if (previousIndex in 0 until index && index <= list.size) {
                val ids = list.subList(previousIndex, index).filter { !it.read }.map { it.id }
                if (ids.isNotEmpty()) onScrolledPast(ids)
            }
            previousFirst = first
        }
    }

    val now = remember(items) { System.currentTimeMillis() }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            items(items, key = { it.id }) { item ->
                ContextMenuArea(items = {
                    listOf(
                        ContextMenuItem(if (item.read) "Als ungelesen markieren" else "Als gelesen markieren") { onToggleRead(item) },
                        ContextMenuItem(if (item.starred) "Nicht mehr merken" else "Merken") { onToggleStar(item) },
                    )
                }) {
                    ArticleRow(
                        item = item,
                        now = now,
                        selected = item.id == selectedId,
                        modifier = Modifier.clickable { onOpen(item) },
                        onToggleStar = { onToggleStar(item) },
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            }
        }
        VerticalScrollbar(
            rememberScrollbarAdapter(listState),
            Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
            style = LocalScrollbarStyle.current,
        )
    }
}

@Composable
private fun ArticleRow(item: ArticleListItem, now: Long, selected: Boolean, modifier: Modifier, onToggleStar: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val titleColor = if (item.read) colors.onSurfaceVariant.copy(alpha = 0.75f) else colors.onSurface
    val secondary = colors.onSurfaceVariant.copy(alpha = if (item.read) 0.6f else 1f)
    Row(
        modifier = modifier.fillMaxWidth()
            .background(if (selected) colors.secondaryContainer.copy(alpha = 0.6f) else Color.Transparent)
            .padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = item.feedTitle + " · " + relativeTime(item.pubDate, now),
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
            if (item.starred) {
                ToolButton(Icons.Default.Star, "Nicht mehr merken", onToggleStar, tint = colors.primary)
            } else {
                ToolButton(Icons.Default.StarBorder, "Merken", onToggleStar)
            }
            if (item.hasNote) {
                Icon(Icons.Default.Edit, contentDescription = "Hat eine Notiz", tint = colors.onSurfaceVariant, modifier = Modifier.size(16.dp))
            }
        }
    }
}

// ---- reader -------------------------------------------------------------------------------------

@Composable
private fun ReaderPane(state: MainState, images: ImageLoader, onClose: (() -> Unit)?) {
    val article by state.openArticle.collectAsState()
    val feed by state.openFeed.collectAsState()
    val loading by state.loadingFullText.collectAsState()
    val preferFullText by state.preferFullText.collectAsState()
    var noteOpen by remember { mutableStateOf(false) }
    val a = article ?: return

    Column(Modifier.fillMaxSize()) {
        PaneBar(
            navigation = onClose?.let { close -> { ToolButton(Icons.AutoMirrored.Filled.ArrowBack, "Zurück (Esc)", close) } },
            title = {},
            actions = {
                if (a.starred) ToolButton(Icons.Default.Star, "Nicht mehr merken (S)", state::toggleOpenStar, tint = MaterialTheme.colorScheme.primary)
                else ToolButton(Icons.Default.StarBorder, "Merken (S)", state::toggleOpenStar)
                ToolButton(
                    Icons.Default.Edit,
                    if (a.note.isNullOrBlank()) "Notiz hinzufügen" else "Notiz bearbeiten",
                    { noteOpen = true },
                    tint = if (a.note.isNullOrBlank()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                )
                if (a.link != null) {
                    ToolButton(Icons.Default.ContentCopy, "Link kopieren", {
                        Platform.copyToClipboard(listOfNotNull(a.title, a.link).joinToString("\n"))
                        state.messages.tryEmit("Titel und Link kopiert")
                    })
                    val showsFullText = a.fullText != null && preferFullText
                    ToolButton(
                        Icons.AutoMirrored.Filled.Notes,
                        when {
                            a.fullText == null -> "Volltext laden"
                            showsFullText -> "Feed-Text anzeigen"
                            else -> "Volltext anzeigen"
                        },
                        { if (a.fullText == null) state.loadFullText() else state.preferFullText.value = !preferFullText },
                        enabled = !loading,
                        tint = if (showsFullText) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    ToolButton(Icons.AutoMirrored.Filled.OpenInNew, "Im Browser öffnen (V)", { Platform.openInBrowser(a.link!!) })
                }
                ToolButton(Icons.Default.MarkEmailUnread, "Als ungelesen markieren (M)", state::markOpenUnread)
            },
        )
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp)) else Spacer(Modifier.height(2.dp))
        ArticleBody(a, feed?.title, preferFullText, images)
    }

    if (noteOpen) {
        NoteDialog(
            initial = a.note.orEmpty(),
            onSave = { state.saveNote(it); noteOpen = false },
            onDismiss = { noteOpen = false },
        )
    }
}

@Composable
private fun ArticleBody(a: Article, feedTitle: String?, preferFullText: Boolean, images: ImageLoader) {
    val colors = MaterialTheme.colorScheme
    val body = (if (preferFullText) a.fullText else null) ?: a.content
    val linkStyle = SpanStyle(color = colors.primary, textDecoration = TextDecoration.Underline)
    val blocks = remember(a.id, body, linkStyle) {
        HtmlLayout(linkStyle) { Platform.openInBrowser(it) }.layout(body.orEmpty(), a.link)
    }
    val scroll = remember(a.id) { androidx.compose.foundation.ScrollState(0) }
    val bodyStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp, lineHeight = 26.sp, color = colors.onSurface)
    Box(Modifier.fillMaxSize()) {
        androidx.compose.foundation.text.selection.SelectionContainer {
            Column(
                Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = 32.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(Modifier.fillMaxWidth().widthIn(max = 760.dp)) {
                    val titleText = a.title
                    Text(
                        titleText,
                        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = colors.onSurface,
                        modifier = if (a.link != null) Modifier.clickable { Platform.openInBrowser(a.link!!) } else Modifier,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        listOfNotNull(feedTitle, a.author, dateTime(a.pubDate)).joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                    )
                    a.note?.takeIf { it.isNotBlank() }?.let { note ->
                        Surface(
                            color = colors.secondaryContainer,
                            contentColor = colors.onSecondaryContainer,
                            shape = MaterialTheme.shapes.medium,
                            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                        ) {
                            Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                                Text("Notiz", fontWeight = FontWeight.Bold)
                                Text(note)
                            }
                        }
                    }
                    if (blocks.isEmpty()) {
                        Text(
                            "Dieser Feed liefert keinen Text. Das Textsymbol oben lädt den Volltext.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.onSurfaceVariant,
                            modifier = Modifier.padding(top = 16.dp),
                        )
                    } else {
                        HtmlContent(blocks, images, bodyStyle)
                    }
                    Spacer(Modifier.height(48.dp))
                }
            }
        }
        androidx.compose.foundation.VerticalScrollbar(
            androidx.compose.foundation.rememberScrollbarAdapter(scroll),
            Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
        )
    }
}

@Composable
private fun NoteDialog(initial: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Notiz") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text("Notiz zu diesem Artikel") },
                modifier = Modifier.fillMaxWidth().height(160.dp).focusRequester(focus),
            )
        },
        confirmButton = { TextButton(onClick = { onSave(text) }) { Text("Speichern") } },
        dismissButton = {
            Row {
                if (initial.isNotBlank()) TextButton(onClick = { onSave("") }) { Text("Löschen") }
                TextButton(onClick = onDismiss) { Text("Abbrechen") }
            }
        },
    )
}

/**
 * Keyboard shortcuts of the main window. Called for key presses that no text field consumed, so
 * typing into the search or a note does not trigger them.
 */
fun handleMainKey(state: MainState, event: androidx.compose.ui.input.key.KeyEvent): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    val a = state.openArticle.value
    return when {
        event.isCtrlPressed && event.key == Key.F -> { state.openSearch(); true }
        event.isCtrlPressed && event.key == Key.R || event.key == Key.F5 -> { state.refresh(); true }
        event.isCtrlPressed || event.isAltPressed || event.isMetaPressed -> false
        event.key == Key.J || event.key == Key.DirectionDown && a != null -> { state.step(+1); true }
        event.key == Key.K || event.key == Key.DirectionUp && a != null -> { state.step(-1); true }
        event.key == Key.S && a != null -> { state.toggleOpenStar(); true }
        event.key == Key.M && a != null -> { state.markOpenUnread(); true }
        event.key == Key.V && a?.link != null -> { Platform.openInBrowser(a.link!!); true }
        event.key == Key.Escape && state.search.value != null -> { state.closeSearch(); true }
        event.key == Key.Escape && a != null -> { state.open(null); true }
        else -> false
    }
}
