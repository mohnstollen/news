package io.github.mohnstollen.news.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Star
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import io.github.mohnstollen.news.AppContainer
import io.github.mohnstollen.news.AppFeatures
import io.github.mohnstollen.news.NewsApp
import io.github.mohnstollen.news.playback.PlayerConnection
import io.github.mohnstollen.news.widget.NewsWidget
import io.github.mohnstollen.news.widget.WidgetSource
import io.github.mohnstollen.news.work.Notifier
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    /** Article to open, as requested by a tap on the notification. */
    private val requestedArticle = mutableStateOf<Long?>(null)

    /** Feed or folder to show, as requested by a tap on a widget's heading. */
    private val requestedSource = mutableStateOf<WidgetSource?>(null)

    /** Connected while the app is visible, so the screens can show and steer playback. */
    private val player by lazy { PlayerConnection(this) }
    private val podcasts get() = container.features.podcasts

    override fun onStart() {
        super.onStart()
        if (podcasts) player.connect()
    }

    private fun readIntent(intent: Intent?) {
        intent?.getStringExtra(NewsWidget.EXTRA_SOURCE)?.let {
            requestedSource.value = WidgetSource.decode(it)
            intent.removeExtra(NewsWidget.EXTRA_SOURCE)
        }
        val id = intent?.getLongExtra(Notifier.EXTRA_ARTICLE_ID, -1L) ?: -1L
        if (id > 0) {
            requestedArticle.value = id
            // Do not open it again when the activity is recreated.
            intent?.removeExtra(Notifier.EXTRA_ARTICLE_ID)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        readIntent(intent)
    }

    /** Reading changes what the widget should list. */
    override fun onStop() {
        super.onStop()
        if (podcasts) player.release()
        val app = applicationContext
        container.repository.scope.launch { NewsWidget.updateAll(app) }
    }

    override fun onResume() {
        super.onResume()
        Notifier.cancel(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) readIntent(intent)
        setContent {
            NewsTheme {
              // Without podcasts there is no player; the player controls then show nothing.
              CompositionLocalProvider(LocalPlayer provides player.takeIf { podcasts }) {
                val nav = rememberNavController()
                // Shared by all destinations, so list scope and filters survive navigation.
                val mainViewModel: MainViewModel = viewModel()
                LaunchedEffect(requestedArticle.value) {
                    val id = requestedArticle.value ?: return@LaunchedEffect
                    requestedArticle.value = null
                    nav.navigate("reader/$id") { launchSingleTop = true }
                }
                LaunchedEffect(requestedSource.value) {
                    val source = requestedSource.value ?: return@LaunchedEffect
                    requestedSource.value = null
                    when (source) {
                        WidgetSource.All -> {
                            mainViewModel.select(MainViewModel.ListScope.All)
                            mainViewModel.setFolder(null)
                        }
                        is WidgetSource.Folder -> {
                            mainViewModel.select(MainViewModel.ListScope.All)
                            mainViewModel.setFolder(source.name)
                        }
                        is WidgetSource.Feed -> mainViewModel.select(MainViewModel.ListScope.Single(source.id))
                    }
                    nav.popBackStack("list", inclusive = false)
                }
                val tabs = container.features.tabs
                // A tab is shown without a back arrow; opened from the menu, a screen has one.
                fun backOrTab(): (() -> Unit)? = if (tabs) null else ({ nav.popBackStack() })
                TabScaffold(
                    nav,
                    enabled = tabs,
                    onShowScope = { scope ->
                        mainViewModel.select(scope)
                        if (scope == MainViewModel.ListScope.All) mainViewModel.setFolder(null)
                    },
                ) { modifier ->
                NavHost(nav, startDestination = "list", modifier = modifier) {
                    composable("list") {
                        ArticleListScreen(
                            vm = mainViewModel,
                            onOpenArticle = { nav.navigate("reader/$it") },
                            onOpenFeeds = { nav.navigate("feeds") },
                            onOpenSettings = { nav.navigate("settings") },
                            onOpenScreen = { nav.navigate(it) },
                        )
                    }
                    composable("reader/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) {
                        ReaderScreen(
                            onBack = { nav.popBackStack() },
                            onOpenArticle = { nav.navigate("reader/$it") },
                            onOpenPodcast = if (tabs) ({ nav.navigate("podcast/$it") }) else null,
                        )
                    }
                    composable("podcasts") {
                        PodcastsScreen(
                            onOpenPodcast = { nav.navigate("podcast/$it") },
                            onOpenArticle = { nav.navigate("reader/$it") },
                            onManage = { nav.navigate("feeds") },
                            onDiscover = { nav.navigate("discover") },
                        )
                    }
                    composable("podcast/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
                        PodcastScreen(
                            feedId = entry.arguments!!.getLong("id"),
                            onBack = { nav.popBackStack() },
                            onOpenArticle = { nav.navigate("reader/$it") },
                        )
                    }
                    composable("feeds") { FeedsScreen(onBack = { nav.popBackStack() }) }
                    composable("queue") {
                        QueueScreen(onBack = backOrTab(), onOpenArticle = { nav.navigate("reader/$it") })
                    }
                    composable("downloads") {
                        DownloadsScreen(onBack = { nav.popBackStack() }, onOpenArticle = { nav.navigate("reader/$it") })
                    }
                    composable("stats") { StatsScreen(onBack = { nav.popBackStack() }) }
                    composable("discover") { DiscoverScreen(onBack = { nav.popBackStack() }) }
                    // Opened from "Mehr" with tabs, so it has a back arrow either way.
                    composable("settings") {
                        SettingsScreen(onBack = { nav.popBackStack() }, onOpenDownloads = { nav.navigate("downloads") })
                    }
                }
                }
              }
            }
        }
    }
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

/**
 * With [enabled], a bar of tabs at the bottom while one of the tab screens is shown; other screens
 * (an episode, statistics, …) cover the whole height. [content] gets the modifier for its padding.
 */
@Composable
private fun TabScaffold(
    nav: androidx.navigation.NavHostController,
    enabled: Boolean,
    onShowScope: (MainViewModel.ListScope) -> Unit,
    content: @Composable (Modifier) -> Unit,
) {
    if (!enabled) {
        content(Modifier)
        return
    }
    val tabs = remember {
        listOf(
            Tab("list", "Neu", AppIcons.Inbox),
            Tab("queue", "Warteschlange", AppIcons.QueueAdd),
            Tab("podcasts", "Podcasts", AppIcons.Grid),
        )
    }
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val onTab = tabs.any { it.route == route }
    var moreOpen by remember { mutableStateOf(false) }
    fun go(route: String) = nav.navigate(route) {
        // One copy of each tab, and the back button leads to the episodes.
        popUpTo("list") { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
    Scaffold(
        // The screens inside handle the status bar themselves.
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            if (onTab) {
                NavigationBar {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = route == tab.route,
                            onClick = {
                                // Tapping "Neu" again leads back from Gemerkt or Archiv to the new episodes.
                                if (tab.route == "list" && route == "list") onShowScope(MainViewModel.ListScope.All)
                                go(tab.route)
                            },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { TabLabel(tab.label) },
                        )
                    }
                    // What is used less often, in one menu instead of scattered over the side menu.
                    NavigationBarItem(
                        selected = false,
                        onClick = { moreOpen = true },
                        icon = {
                            Box {
                                Icon(Icons.Default.MoreVert, contentDescription = "Mehr")
                                DropdownMenu(expanded = moreOpen, onDismissRequest = { moreOpen = false }) {
                                    DropdownMenuItem(
                                        text = { Text("Einstellungen") },
                                        leadingIcon = { Icon(Icons.Default.Settings, contentDescription = null) },
                                        onClick = { moreOpen = false; nav.navigate("settings") },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Gemerkt") },
                                        leadingIcon = { Icon(Icons.Default.Star, contentDescription = null) },
                                        onClick = { moreOpen = false; onShowScope(MainViewModel.ListScope.Starred); go("list") },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Archiv") },
                                        leadingIcon = { Icon(AppIcons.Archive, contentDescription = null) },
                                        onClick = { moreOpen = false; onShowScope(MainViewModel.ListScope.Archive); go("list") },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Statistik") },
                                        leadingIcon = { Icon(AppIcons.Stats, contentDescription = null) },
                                        onClick = { moreOpen = false; nav.navigate("stats") },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Podcasts entdecken") },
                                        leadingIcon = { Icon(AppIcons.Explore, contentDescription = null) },
                                        onClick = { moreOpen = false; nav.navigate("discover") },
                                    )
                                }
                            }
                        },
                        label = { TabLabel("Mehr") },
                    )
                }
            }
        },
    ) { padding ->
        // The bar takes the navigation bar's space; the screens must not add it again.
        content(Modifier.padding(padding).consumeWindowInsets(padding))
    }
}

/** Five tabs share a phone's width: a little smaller and tighter, so "Warteschlange" fits. */
@Composable
private fun TabLabel(text: String) {
    Text(
        text,
        maxLines = 1,
        softWrap = false,
        style = androidx.compose.material3.MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.sp),
        // May reach a little into the neighbours' space instead of being cut; their labels are short.
        modifier = Modifier.wrapContentWidth(unbounded = true),
    )
}

@Composable
fun NewsTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = colors, content = content)
}

val Context.container: AppContainer get() = (applicationContext as NewsApp).container

val Context.features: AppFeatures get() = container.features

fun Context.openInBrowser(url: String) {
    try {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(this, "Keine App zum Öffnen des Links gefunden", Toast.LENGTH_SHORT).show()
    }
}

/** Icons that the core Material icon set lacks. */
object AppIcons {
    private fun icon(name: String, path: String): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
            .addPath(pathData = addPathNodes(path), fill = SolidColor(Color.Black))
            .build()

    val StarBorder: ImageVector by lazy {
        icon(
            "StarBorder",
            "M22,9.24l-7.19,-0.62L12,2 9.19,8.63 2,9.24l5.46,4.73L5.82,21 12,16.77 18.18,21l-1.63,-7.03L22,9.24z" +
                "M12,15.4l-3.76,2.27 1,-4.28 -3.32,-2.88 4.38,-0.38L12,6.1l1.71,4.04 4.38,0.38 -3.32,2.88 1,4.28L12,15.4z",
        )
    }

    /** Lines of text: load or show the full article. */
    val FullText: ImageVector by lazy {
        icon("FullText", "M14,17L4,17v2h10v-2zM20,9L4,9v2h16L20,9zM4,15h16v-2L4,13v2zM4,5v2h16L20,5L4,5z")
    }

    /** Closed envelope: mark as unread. */
    val MarkUnread: ImageVector by lazy {
        icon(
            "MarkUnread",
            "M20,4L4,4c-1.1,0 -1.99,0.9 -1.99,2L2,18c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2L22,6c0,-1.1 -0.9,-2 -2,-2z" +
                "M20,8l-8,5 -8,-5L4,6l8,5 8,-5v2z",
        )
    }

    val QrCode: ImageVector by lazy {
        icon(
            "QrCode",
            "M9.5,6.5v3h-3v-3H9.5M11,5H5v6h6V5L11,5zM9.5,14.5v3h-3v-3H9.5M11,13H5v6h6V13L11,13z" +
                "M17.5,6.5v3h-3v-3H17.5M19,5h-6v6h6V5L19,5zM13,13h1.5v1.5H13V13zM14.5,14.5H16V16h-1.5V14.5z" +
                "M16,13h1.5v1.5H16V13zM13,16h1.5v1.5H13V16zM14.5,17.5H16V19h-1.5V17.5zM16,16h1.5v1.5H16V16z" +
                "M17.5,14.5H19V16h-1.5V14.5zM17.5,17.5H19V19h-1.5V17.5zM22,7h-2V4h-3V2h5V7zM22,22v-5h-2v3h-3v2H22z" +
                "M2,22h5v-2H4v-3H2V22zM2,2v5h2V4h3V2H2z",
        )
    }

    val Pause: ImageVector by lazy { icon("Pause", "M6,19h4L10,5L6,5v14zM14,5v14h4L18,5h-4z") }

    /** Circular arrow back; the player writes the seconds into it. */
    val Replay: ImageVector by lazy {
        icon(
            "Replay",
            "M12,5V1L7,6l5,5V7c3.31,0 6,2.69 6,6s-2.69,6 -6,6 -6,-2.69 -6,-6H4c0,4.42 3.58,8 8,8s8,-3.58 8,-8 -3.58,-8 -8,-8z",
        )
    }

    /** Circular arrow forward. */
    val Forward: ImageVector by lazy {
        icon(
            "Forward",
            "M12,5V1l5,5 -5,5V7c-3.31,0 -6,2.69 -6,6s2.69,6 6,6 6,-2.69 6,-6h2c0,4.42 -3.58,8 -8,8s-8,-3.58 -8,-8 3.58,-8 8,-8z",
        )
    }

    /** Headphones: an episode was played to the end. */
    val Played: ImageVector by lazy {
        icon(
            "Played",
            "M12,3a9,9 0,0 0,-9 9v7c0,1.1 0.9,2 2,2h3v-8H5v-1a7,7 0,0 1,14 0v1h-3v8h3c1.1,0 2,-0.9 2,-2v-7a9,9 0,0 0,-9 -9z",
        )
    }

    val Download: ImageVector by lazy { icon("Download", "M19,9h-4V3H9v6H5l7,7 7,-7zM5,18v2h14v-2H5z") }

    val DownloadDone: ImageVector by lazy {
        icon("DownloadDone", "M5,18h14v2H5v-2zM9.6,15.3L5,10.7l2,-1.9 2.6,2.6L17,4l2,2 -9.4,9.3z")
    }

    val Archive: ImageVector by lazy {
        icon(
            "Archive",
            "M20.54,5.23l-1.39,-1.68C18.88,3.21 18.47,3 18,3H6c-0.47,0 -0.88,0.21 -1.16,0.55L3.46,5.23C3.17,5.57 3,6.02 3,6.5V19" +
                "c0,1.1 0.9,2 2,2h14c1.1,0 2,-0.9 2,-2V6.5c0,-0.48 -0.17,-0.93 -0.46,-1.27zM12,17.5L6.5,12H10v-2h4v2h3.5L12,17.5z" +
                "M5.12,5l0.81,-1h12l0.94,1H5.12z",
        )
    }

    val Unarchive: ImageVector by lazy {
        icon(
            "Unarchive",
            "M20.55,5.22l-1.39,-1.68C18.88,3.21 18.47,3 18,3H6c-0.47,0 -0.88,0.21 -1.15,0.55L3.46,5.22C3.17,5.57 3,6.01 3,6.5V19" +
                "c0,1.1 0.89,2 2,2h14c1.1,0 2,-0.9 2,-2V6.5c0,-0.49 -0.17,-0.93 -0.45,-1.28zM12,9.5l5.5,5.5H14v2h-4v-2H6.5L12,9.5z" +
                "M5.12,5l0.82,-1h12l0.93,1H5.12z",
        )
    }

    /** List with a plus: add to the queue. */
    val QueueAdd: ImageVector by lazy {
        icon("QueueAdd", "M14,10H3v2h11V10zM14,6H3v2h11V6zM18,14v-4h-2v4h-4v2h4v4h2v-4h4v-2H18zM3,16h7v-2H3V16z")
    }

    /** List with a minus: take out of the queue. */
    val QueueRemove: ImageVector by lazy {
        icon("QueueRemove", "M14,10H3v2h11V10zM14,6H3v2h11V6zM3,16h7v-2H3V16zM12,14v2h10v-2H12z")
    }

    /** Tray: the inbox of new episodes. */
    val Inbox: ImageVector by lazy {
        icon(
            "Inbox",
            "M19,3H4.99C3.88,3 3.01,3.89 3.01,5L3,19c0,1.1 0.88,2 1.99,2H19c1.1,0 2,-0.9 2,-2V5c0,-1.11 -0.9,-2 -2,-2z" +
                "M19,15h-4c0,1.66 -1.35,3 -3,3s-3,-1.34 -3,-3H4.99V5H19v10z",
        )
    }

    /** Four squares: the podcasts as a grid of covers. */
    val Grid: ImageVector by lazy {
        icon("Grid", "M3,3h8v8H3V3zM13,3h8v8h-8V3zM3,13h8v8H3v-8zM13,13h8v8h-8v-8z")
    }

    /** Bar chart: statistics. */
    val Stats: ImageVector by lazy {
        icon("Stats", "M5,9.2h3V19H5V9.2zM10.6,5h2.8v14h-2.8V5zM16.2,13H19v6h-2.8V13z")
    }

    /** Compass: discover new podcasts. */
    val Explore: ImageVector by lazy {
        icon(
            "Explore",
            "M12,10.9c-0.61,0 -1.1,0.49 -1.1,1.1s0.49,1.1 1.1,1.1c0.61,0 1.1,-0.49 1.1,-1.1s-0.49,-1.1 -1.1,-1.1z" +
                "M12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2zM14.19,14.19L6,18l3.81,-8.19L18,6l-3.81,8.19z",
        )
    }

    val DoneAll: ImageVector by lazy {
        icon(
            "DoneAll",
            "M18,7l-1.41,-1.41 -6.34,6.34 1.41,1.41L18,7z" +
                "M22.24,5.59L11.66,16.17 7.48,12l-1.41,1.41L11.66,19l12,-12 -1.42,-1.41z" +
                "M0.41,13.41L6,19l1.41,-1.41L1.83,12 0.41,13.41z",
        )
    }
}
