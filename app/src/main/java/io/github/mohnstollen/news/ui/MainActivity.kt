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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import io.github.mohnstollen.news.AppContainer
import io.github.mohnstollen.news.NewsApp
import io.github.mohnstollen.news.work.Notifier

class MainActivity : ComponentActivity() {
    /** Article to open, as requested by a tap on the notification. */
    private val requestedArticle = mutableStateOf<Long?>(null)

    private fun readIntent(intent: Intent?) {
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
                val nav = rememberNavController()
                LaunchedEffect(requestedArticle.value) {
                    val id = requestedArticle.value ?: return@LaunchedEffect
                    requestedArticle.value = null
                    nav.navigate("reader/$id") { launchSingleTop = true }
                }
                // Shared by all destinations, so list scope and filters survive navigation.
                val mainViewModel: MainViewModel = viewModel()
                NavHost(nav, startDestination = "list") {
                    composable("list") {
                        ArticleListScreen(
                            vm = mainViewModel,
                            onOpenArticle = { nav.navigate("reader/$it") },
                            onOpenFeeds = { nav.navigate("feeds") },
                            onOpenSettings = { nav.navigate("settings") },
                        )
                    }
                    composable("reader/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) {
                        ReaderScreen(onBack = { nav.popBackStack() })
                    }
                    composable("feeds") { FeedsScreen(onBack = { nav.popBackStack() }) }
                    composable("settings") { SettingsScreen(onBack = { nav.popBackStack() }) }
                }
            }
        }
    }
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

    val DoneAll: ImageVector by lazy {
        icon(
            "DoneAll",
            "M18,7l-1.41,-1.41 -6.34,6.34 1.41,1.41L18,7z" +
                "M22.24,5.59L11.66,16.17 7.48,12l-1.41,1.41L11.66,19l12,-12 -1.42,-1.41z" +
                "M0.41,13.41L6,19l1.41,-1.41L1.83,12 0.41,13.41z",
        )
    }
}
