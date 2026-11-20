package io.github.mohnstollen.news.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mohnstollen.news.data.FeedWithUnread
import io.github.mohnstollen.news.ui.NewsTheme
import io.github.mohnstollen.news.ui.container
import kotlinx.coroutines.launch

/**
 * Chooses what a widget lists. Opened by the launcher when a widget is placed (and, where the
 * launcher supports it, to change it), and by the widget's own settings button.
 */
class WidgetConfigActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val widgetId = intent?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        // Leaving without a choice does not place the widget.
        setResult(RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }
        val feeds = container.repository.feedDao.observeWithUnread()
        setContent {
            NewsTheme {
                val list by remember { feeds }.collectAsStateWithLifecycle(emptyList())
                WidgetConfigScreen(
                    feeds = list,
                    current = WidgetSources.get(this, widgetId),
                    onChoose = { choose(widgetId, it) },
                    onCancel = ::finish,
                )
            }
        }
    }

    private fun choose(widgetId: Int, source: WidgetSource) {
        WidgetSources.set(this, widgetId, source)
        val app = applicationContext
        // Draws in the app's scope, so it finishes after this screen is gone.
        container.repository.scope.launch { NewsWidget.updateAll(app) }
        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        finish()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WidgetConfigScreen(
    feeds: List<FeedWithUnread>,
    current: WidgetSource,
    onChoose: (WidgetSource) -> Unit,
    onCancel: () -> Unit,
) {
    val folders = remember(feeds) {
        feeds.filter { it.feed.category != null }
            .groupBy { it.feed.category!! }
            .mapValues { (_, members) -> members.sumOf { it.unread } }
            .toSortedMap(String.CASE_INSENSITIVE_ORDER)
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Widget: Was anzeigen?") },
                navigationIcon = {
                    IconButton(onClick = onCancel) { Icon(Icons.Default.Close, contentDescription = "Abbrechen") }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            item {
                Choice(
                    title = "Alle Feeds",
                    subtitle = unreadText(feeds.sumOf { it.unread }),
                    selected = current == WidgetSource.All,
                    onClick = { onChoose(WidgetSource.All) },
                )
            }
            if (folders.isNotEmpty()) {
                item { Heading("Ordner") }
                items(folders.entries.toList(), key = { "folder:${it.key}" }) { (name, unread) ->
                    Choice(
                        title = name,
                        subtitle = unreadText(unread),
                        selected = current == WidgetSource.Folder(name),
                        onClick = { onChoose(WidgetSource.Folder(name)) },
                    )
                }
            }
            if (feeds.isNotEmpty()) {
                item { Heading("Einzelne Feeds") }
                items(feeds, key = { "feed:${it.feed.id}" }) { entry ->
                    Choice(
                        title = entry.feed.title,
                        subtitle = listOfNotNull(entry.feed.category, unreadText(entry.unread)).joinToString(" · "),
                        selected = current == WidgetSource.Feed(entry.feed.id),
                        onClick = { onChoose(WidgetSource.Feed(entry.feed.id)) },
                    )
                }
            }
        }
    }
}

private fun unreadText(count: Int) = if (count == 1) "1 ungelesen" else "$count ungelesen"

@Composable
private fun Heading(text: String) {
    Column {
        HorizontalDivider(Modifier.padding(top = 8.dp))
        Text(
            text,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
        )
    }
}

@Composable
private fun Choice(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.padding(start = 16.dp).weight(1f)) {
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
