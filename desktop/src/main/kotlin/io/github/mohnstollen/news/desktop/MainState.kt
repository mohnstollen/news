package io.github.mohnstollen.news.desktop

import io.github.mohnstollen.news.data.Article
import io.github.mohnstollen.news.data.ArticleListItem
import io.github.mohnstollen.news.data.Feed
import io.github.mohnstollen.news.data.ReadVisibility
import io.github.mohnstollen.news.data.SearchQuery
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The state behind the main window: list scope, filters, search and the open article. */
class MainState(private val container: AppContainer, val scope: CoroutineScope) {

    sealed interface ListScope {
        data object All : ListScope
        data object Starred : ListScope
        data class Single(val feedId: Long) : ListScope
    }

    private val repo = container.repository
    private val settings = container.settings

    val prefs = settings.flow
    val refreshing = repo.refreshing
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 4)

    private val _listScope = MutableStateFlow<ListScope>(ListScope.All)
    val listScope: StateFlow<ListScope> = _listScope

    /**
     * Articles that were marked read while the current list is on screen. With the "unread only"
     * filter they stay visible until the list is reloaded, so nothing jumps while scrolling.
     */
    private val keepVisible = MutableStateFlow<Set<Long>>(emptySet())

    val feeds = repo.feedDao.observeWithUnread().stateIn(scope, SharingStarted.Eagerly, emptyList())
    val unreadTotal = repo.articleDao.observeUnreadCount().stateIn(scope, SharingStarted.Eagerly, 0)
    val starredTotal = repo.articleDao.observeStarredCount().stateIn(scope, SharingStarted.Eagerly, 0)

    private val _recentOnly = MutableStateFlow(false)
    val recentOnly: StateFlow<Boolean> = _recentOnly

    private val _folder = MutableStateFlow<String?>(null)
    val folder: StateFlow<String?> = _folder

    private val _search = MutableStateFlow<String?>(null)
    val search: StateFlow<String?> = _search

    fun setRecentOnly(value: Boolean) {
        keepVisible.value = emptySet()
        _recentOnly.value = value
    }

    fun setFolder(value: String?) {
        keepVisible.value = emptySet()
        _folder.value = value
    }

    /** null while loading. While searching, the hits within the current scope, read ones included. */
    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    val articles: StateFlow<List<ArticleListItem>?> = _listScope
        .combine(
            _search.map { it?.let(SearchQuery::toMatch) }.distinctUntilChanged()
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
                repo.articleDao.observeList(feedId, scope is ListScope.Starred),
                filter,
                keepVisible,
                _recentOnly,
                _folder,
            ) { all, (unreadOnly, newestFirst, hideReadAfter), keep, recentOnly, folder ->
                val now = System.currentTimeMillis()
                val filterable = scope !is ListScope.Starred
                val since = if (recentOnly && filterable) now - RECENT_WINDOW_MS else null
                val inFolder = folder.takeIf { scope is ListScope.All }
                val filtered = all.filter { item ->
                    (!unreadOnly || !filterable || !item.read || item.id in keep) &&
                        (!filterable || item.id in keep ||
                            ReadVisibility.isVisible(item.read, item.readAt, hideReadAfter, now)) &&
                        (since == null || item.pubDate >= since) &&
                        (inFolder == null || item.category == inFolder)
                }
                if (newestFirst) filtered else filtered.asReversed()
            }
        }
        .flowOn(Dispatchers.Default)
        .stateIn(scope, SharingStarted.Eagerly, null)

    fun openSearch() {
        if (_search.value == null) _search.value = ""
    }

    fun closeSearch() {
        _search.value = null
    }

    fun setSearch(text: String) {
        _search.value = text
    }

    fun select(listScope: ListScope) {
        keepVisible.value = emptySet()
        _listScope.value = listScope
    }

    fun setUnreadOnly(value: Boolean) {
        keepVisible.value = emptySet()
        settings.update { it.copy(unreadOnly = value) }
    }

    fun setNewestFirst(value: Boolean) = settings.update { it.copy(newestFirst = value) }

    fun setMarkReadOnScroll(value: Boolean) = settings.update { it.copy(markReadOnScroll = value) }

    fun markRead(ids: List<Long>) {
        keepVisible.update { it + ids }
        scope.launch { repo.setRead(ids, true) }
    }

    fun toggleRead(item: ArticleListItem) {
        keepVisible.update { it + item.id }
        scope.launch { repo.setRead(listOf(item.id), !item.read) }
    }

    fun toggleStar(item: ArticleListItem) {
        scope.launch { repo.setStarred(item.id, !item.starred) }
    }

    fun narrowed(): Boolean {
        val s = _listScope.value
        return (_recentOnly.value && s !is ListScope.Starred) || (_folder.value != null && s is ListScope.All)
    }

    fun markAllRead() {
        val s = _listScope.value
        val shown = if (narrowed()) articles.value.orEmpty().filter { !it.read }.map { it.id } else null
        keepVisible.value = emptySet()
        scope.launch {
            if (shown != null) repo.setRead(shown, true)
            else repo.articleDao.markAllRead(
                (s as? ListScope.Single)?.feedId, s is ListScope.Starred, System.currentTimeMillis(),
            )
        }
    }

    fun refresh() {
        keepVisible.value = emptySet()
        val feedId = (_listScope.value as? ListScope.Single)?.feedId
        repo.scope.launch {
            val result = repo.refresh(feedId)
            if (result.skipped) return@launch
            messages.tryEmit(refreshMessage(result.newArticles, result.failedFeeds))
        }
    }

    // ---- reader -----------------------------------------------------------------------------

    private val _openId = MutableStateFlow<Long?>(null)
    val openId: StateFlow<Long?> = _openId

    @OptIn(ExperimentalCoroutinesApi::class)
    val openArticle: StateFlow<Article?> = _openId
        .flatMapLatest { id -> if (id == null) flowOf(null) else repo.articleDao.observe(id) }
        .stateIn(scope, SharingStarted.Eagerly, null)

    val openFeed = MutableStateFlow<Feed?>(null)
    val loadingFullText = MutableStateFlow(false)
    /** Show the extracted page instead of the feed's own text, when there is one. */
    val preferFullText = MutableStateFlow(true)
    private var openJob: Job? = null

    fun open(id: Long?) {
        if (id == _openId.value) return
        _openId.value = id
        openFeed.value = null
        preferFullText.value = true
        loadingFullText.value = false
        openJob?.cancel()
        if (id == null) return
        keepVisible.update { it + id }
        openJob = scope.launch {
            repo.setRead(listOf(id), true)
            val article = repo.articleDao.byId(id) ?: return@launch
            val feed = repo.feedDao.byId(article.feedId)
            openFeed.value = feed
            if (feed?.fetchFullText == true && article.fullText == null && article.link != null) {
                loadFullText(silent = true)
            }
        }
    }

    /** Opens the next (+1) or previous (-1) article of the list. */
    fun step(delta: Int) {
        val list = articles.value.orEmpty()
        if (list.isEmpty()) return
        val index = list.indexOfFirst { it.id == _openId.value }
        val next = when {
            index < 0 -> if (delta > 0) 0 else list.lastIndex
            else -> (index + delta).coerceIn(0, list.lastIndex)
        }
        open(list[next].id)
    }

    fun toggleOpenStar() {
        val a = openArticle.value ?: return
        scope.launch { repo.setStarred(a.id, !a.starred) }
    }

    fun saveNote(text: String) {
        val a = openArticle.value ?: return
        scope.launch { repo.setNote(a.id, text) }
    }

    fun markOpenUnread() {
        val a = openArticle.value ?: return
        scope.launch { repo.setRead(listOf(a.id), false) }
        open(null)
    }

    fun loadFullText(silent: Boolean = false) {
        val id = _openId.value ?: return
        if (loadingFullText.value) return
        loadingFullText.value = true
        scope.launch {
            try {
                val found = repo.loadFullText(id)
                if (found) preferFullText.value = true
                else if (!silent) messages.tryEmit("Auf der Seite wurde kein Artikeltext gefunden")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!silent) messages.tryEmit("Volltext konnte nicht geladen werden: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                if (_openId.value == id) loadingFullText.value = false
            }
        }
    }

    private data class ListPrefs(val unreadOnly: Boolean, val newestFirst: Boolean, val hideReadAfterMinutes: Int)

    companion object {
        const val RECENT_WINDOW_MS = 3 * 60 * 60 * 1000L

        fun refreshMessage(newArticles: Int, failedFeeds: Int): String {
            val news = when (newArticles) {
                0 -> "Keine neuen Artikel"
                1 -> "1 neuer Artikel"
                else -> "$newArticles neue Artikel"
            }
            val errors = when (failedFeeds) {
                0 -> ""
                1 -> " · 1 Feed nicht erreichbar"
                else -> " · $failedFeeds Feeds nicht erreichbar"
            }
            return news + errors
        }
    }
}
