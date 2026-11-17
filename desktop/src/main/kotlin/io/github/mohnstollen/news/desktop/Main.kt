package io.github.mohnstollen.news.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberTrayState
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import androidx.compose.foundation.isSystemInDarkTheme
import io.github.mohnstollen.news.data.Theme
import java.awt.SystemTray

private enum class Screen { Main, Feeds, Settings }

fun main(args: Array<String>) {
    // Use the desktop's font smoothing for Swing parts such as menus and dialogs.
    System.setProperty("awt.useSystemAAFontSettings", "lcd")

    val container = AppContainer()
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    val updater = Updater(container, appScope)
    val startHidden = "--hidden" in args

    application {
        val state = remember { MainState(container, appScope) }
        val trayAvailable = remember { runCatching { SystemTray.isSupported() }.getOrDefault(false) }
        // Without a tray there would be no way back to a hidden window.
        var windowOpen by remember { mutableStateOf(!startHidden || !trayAvailable) }
        val prefs by container.settings.flow.collectAsState()
        val trayState = rememberTrayState()
        val unread by state.unreadTotal.collectAsState()

        LaunchedEffect(Unit) {
            updater.fallbackNotifier = { title, body -> trayState.sendNotification(androidx.compose.ui.window.Notification(title, body)) }
            updater.start()
        }

        fun quit() {
            container.db.close()
            exitApplication()
        }

        if (trayAvailable) {
            Tray(
                icon = rememberVectorPainter(AppIcons.Logo),
                state = trayState,
                tooltip = if (unread > 0) "News · $unread ungelesen" else "News",
                onAction = { windowOpen = true },
                menu = {
                    Item("News öffnen", onClick = { windowOpen = true })
                    Item("Jetzt aktualisieren", onClick = { appScope.launch { updater.runOnce(manual = true) } })
                    Separator()
                    Item("Beenden", onClick = ::quit)
                },
            )
        }

        if (windowOpen) {
            MainWindow(
                container = container,
                state = state,
                onClose = {
                    if (prefs.keepInTray && trayAvailable) windowOpen = false else quit()
                },
                onQuit = ::quit,
            )
        } else if (!trayAvailable) {
            // Nothing to come back to without a tray.
            quit()
        }
    }
}

@Composable
private fun ApplicationScope.MainWindow(
    container: AppContainer,
    state: MainState,
    onClose: () -> Unit,
    onQuit: () -> Unit,
) {
    val windowState = rememberWindowState(size = DpSize(1360.dp, 860.dp), position = WindowPosition(Alignment.Center))
    var screen by remember { mutableStateOf(Screen.Main) }
    var sidebarVisible by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()

    Window(
        onCloseRequest = onClose,
        state = windowState,
        title = "News",
        icon = rememberVectorPainter(AppIcons.Logo),
        onKeyEvent = { event ->
            val action = windowShortcut(event)
            if (action == null || action == Shortcut.Back && screen == Screen.Main) {
                screen == Screen.Main && handleMainKey(state, event)
            } else {
                when (action) {
                    Shortcut.Close -> onClose()
                    Shortcut.Quit -> onQuit()
                    Shortcut.Settings -> screen = Screen.Settings
                    Shortcut.Sidebar -> { screen = Screen.Main; sidebarVisible = !sidebarVisible }
                    Shortcut.All -> { screen = Screen.Main; state.select(MainState.ListScope.All) }
                    Shortcut.Starred -> { screen = Screen.Main; state.select(MainState.ListScope.Starred) }
                    Shortcut.Back -> screen = Screen.Main
                }
                true
            }
        },
    ) {
        window.minimumSize = java.awt.Dimension(560, 480)

        val prefs by container.settings.flow.collectAsState()
        // The desktop setting can change while the app runs; look again now and then.
        var systemDark by remember { mutableStateOf<Boolean?>(null) }
        LaunchedEffect(prefs.theme) {
            while (prefs.theme == Theme.SYSTEM) {
                systemDark = withContext(Dispatchers.IO) { SystemTheme.isDark() }
                delay(10_000)
            }
        }
        val dark = when (prefs.theme) {
            Theme.DARK -> true
            Theme.LIGHT -> false
            else -> systemDark ?: isSystemInDarkTheme()
        }
        NewsTheme(dark) {
            Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
                val snackbar = remember { SnackbarHostState() }
                LaunchedEffect(Unit) { state.messages.collect { scope.launch { snackbar.showSnackbar(it) } } }
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    when (screen) {
                        Screen.Main -> MainScreen(
                            state = state,
                            images = container.images,
                            wide = maxWidth >= 1000.dp,
                            showSidebar = sidebarVisible && maxWidth >= 760.dp,
                            onOpenFeeds = { screen = Screen.Feeds },
                            onOpenSettings = { screen = Screen.Settings },
                        )
                        Screen.Feeds -> FeedsScreen(container.repository, onBack = { screen = Screen.Main })
                        Screen.Settings -> SettingsScreen(container, onBack = { screen = Screen.Main })
                    }
                    Box(Modifier.align(Alignment.BottomCenter).padding(16.dp)) { SnackbarHost(snackbar) }
                }
            }
        }
    }
}

private enum class Shortcut { Close, Quit, Settings, Sidebar, All, Starred, Back }

/** Window-wide shortcuts. The app draws no menu bar, so that it can be dark like the rest of the window. */
private fun windowShortcut(event: androidx.compose.ui.input.key.KeyEvent): Shortcut? {
    if (event.type != KeyEventType.KeyDown) return null
    if (!event.isCtrlPressed) return if (event.key == Key.Escape) Shortcut.Back else null
    return when (event.key) {
        Key.W -> Shortcut.Close
        Key.Q -> Shortcut.Quit
        Key.Comma -> Shortcut.Settings
        Key.B -> Shortcut.Sidebar
        Key.One -> Shortcut.All
        Key.Two -> Shortcut.Starred
        else -> null
    }
}
