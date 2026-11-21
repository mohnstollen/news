package io.github.mohnstollen.news.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.format.DateFormat
import android.text.format.DateUtils
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import io.github.mohnstollen.news.NewsApp
import io.github.mohnstollen.news.R
import io.github.mohnstollen.news.data.ArticleListItem
import io.github.mohnstollen.news.ui.MainActivity
import io.github.mohnstollen.news.work.Notifier
import io.github.mohnstollen.news.work.RefreshWorker
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.Date

/** Home screen widget with the newest unread articles of all feeds, one folder or one feed. */
class NewsWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = redraw(context)

    /** First widget placed: start the widget's own update schedule. */
    override fun onEnabled(context: Context) = reschedule(context)

    /** Last widget removed: stop it again. */
    override fun onDisabled(context: Context) = reschedule(context)

    override fun onDeleted(context: Context, appWidgetIds: IntArray) = WidgetSources.remove(context, appWidgetIds)

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_REFRESH) RefreshWorker.refreshNow(context) else super.onReceive(context, intent)
    }

    private fun reschedule(context: Context) {
        val app = context.applicationContext as NewsApp
        RefreshWorker.schedule(app, app.container.settings.current)
    }

    private fun redraw(context: Context) {
        val app = context.applicationContext as NewsApp
        // Keeps the process alive until the widget is drawn; null when not called from a broadcast.
        val pending: PendingResult? = goAsync()
        app.container.repository.scope.launch {
            try {
                NewsWidget.updateAll(app)
            } finally {
                pending?.finish()
            }
        }
    }

    companion object {
        const val ACTION_REFRESH = "io.github.mohnstollen.news.widget.REFRESH"
    }
}

object NewsWidget {
    const val MAX_ITEMS = 25

    fun ids(context: Context): IntArray = AppWidgetManager.getInstance(context)
        .getAppWidgetIds(ComponentName(context, NewsWidgetProvider::class.java))

    fun isPlaced(context: Context): Boolean = ids(context).isNotEmpty()

    /** Opens the app on what a widget shows; value: [WidgetSource.encode]. */
    const val EXTRA_SOURCE = "widgetSource"

    /** Redraws every placed widget from the database, each with its own choice. Cheap when none is placed. */
    suspend fun updateAll(context: Context) {
        val ids = ids(context)
        if (ids.isEmpty()) return
        val repo = (context.applicationContext as NewsApp).container.repository
        val lastFetched = repo.feedDao.lastFetched() ?: 0
        val manager = AppWidgetManager.getInstance(context)
        for (id in ids) {
            val source = WidgetSources.get(context, id)
            val resolved = repo.resolve(source, context.getString(R.string.app_name))
            val status = resolved.missing?.let { "$it – über ⚙ neu wählen" }
                ?: statusText(context, repo.articleDao.countUnread(resolved.feedId, resolved.folder), lastFetched)
            manager.updateAppWidget(id, views(context, id, source, resolved.title, status))
        }
        manager.notifyAppWidgetViewDataChanged(ids, R.id.widget_list)
    }

    fun statusText(context: Context, unread: Int, lastFetched: Long): String {
        val count = if (unread == 0) "Alles gelesen" else "$unread ungelesen"
        if (lastFetched <= 0) return count
        val time = if (DateUtils.isToday(lastFetched)) {
            DateFormat.getTimeFormat(context).format(Date(lastFetched))
        } else {
            DateFormat.getDateFormat(context).format(Date(lastFetched))
        }
        return "$count · Stand $time"
    }

    /** Makes an intent distinct per widget, so that the widgets' PendingIntents do not replace each other. */
    private fun Intent.forWidget(widgetId: Int, purpose: String): Intent =
        setData(Uri.parse("news-widget://$widgetId/$purpose"))

    private fun views(context: Context, widgetId: Int, source: WidgetSource, title: String, status: String): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget)
        views.setTextViewText(R.id.widget_title, title)
        views.setTextViewText(R.id.widget_status, status)
        // The rows come from a factory for this widget's choice.
        val rows = Intent(context, WidgetService::class.java)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
            .forWidget(widgetId, "rows")
        views.setRemoteAdapter(R.id.widget_list, rows)
        views.setEmptyView(R.id.widget_list, R.id.widget_empty)

        val immutable = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        // The heading opens the app on the same feed or folder.
        val openApp = Intent(context, MainActivity::class.java).addFlags(OPEN_FLAGS)
            .putExtra(EXTRA_SOURCE, source.encode())
            .forWidget(widgetId, "open")
        val open = PendingIntent.getActivity(context, 0, openApp, immutable)
        views.setOnClickPendingIntent(R.id.widget_header, open)
        views.setOnClickPendingIntent(R.id.widget_empty, open)

        val configure = Intent(context, WidgetConfigActivity::class.java)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
            .forWidget(widgetId, "configure")
        views.setOnClickPendingIntent(R.id.widget_configure, PendingIntent.getActivity(context, 0, configure, immutable))

        val refresh = Intent(context, NewsWidgetProvider::class.java).setAction(NewsWidgetProvider.ACTION_REFRESH)
        views.setOnClickPendingIntent(
            R.id.widget_refresh, PendingIntent.getBroadcast(context, REQUEST_REFRESH, refresh, immutable),
        )

        // Each row adds its article id to this template; that needs a mutable PendingIntent.
        val openArticle = Intent(context, MainActivity::class.java).addFlags(OPEN_FLAGS).forWidget(widgetId, "article")
        views.setPendingIntentTemplate(
            R.id.widget_list,
            PendingIntent.getActivity(context, 0, openArticle, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE),
        )
        return views
    }

    private const val OPEN_FLAGS =
        Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
    private const val REQUEST_REFRESH = 11
}

/** Supplies the rows of the widget's list. */
class WidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = WidgetRows(
        applicationContext,
        intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID),
    )
}

class WidgetRows(private val context: Context, private val widgetId: Int) : RemoteViewsService.RemoteViewsFactory {
    private var items: List<ArticleListItem> = emptyList()
    private var loadedAt = 0L

    override fun onCreate() = Unit

    /** Called on a binder thread, so blocking on the database is fine here. */
    override fun onDataSetChanged() {
        val repo = (context.applicationContext as NewsApp).container.repository
        items = runBlocking {
            val resolved = repo.resolve(WidgetSources.get(context, widgetId), context.getString(R.string.app_name))
            if (resolved.missing != null) emptyList()
            else repo.articleDao.newestUnread(NewsWidget.MAX_ITEMS, resolved.feedId, resolved.folder)
        }
        loadedAt = System.currentTimeMillis()
    }

    override fun onDestroy() {
        items = emptyList()
    }

    override fun getCount(): Int = items.size

    override fun getViewAt(position: Int): RemoteViews? {
        val item = items.getOrNull(position) ?: return null
        val views = RemoteViews(context.packageName, R.layout.widget_item)
        val age = DateUtils.getRelativeTimeSpanString(item.pubDate, loadedAt, DateUtils.MINUTE_IN_MILLIS)
        views.setTextViewText(R.id.widget_item_meta, "${item.feedTitle} · $age")
        views.setTextViewText(R.id.widget_item_title, item.title)
        views.setOnClickFillInIntent(R.id.widget_item, Intent().putExtra(Notifier.EXTRA_ARTICLE_ID, item.id))
        return views
    }

    override fun getLoadingView(): RemoteViews? = null

    override fun getViewTypeCount(): Int = 1

    override fun getItemId(position: Int): Long = items.getOrNull(position)?.id ?: position.toLong()

    override fun hasStableIds(): Boolean = true
}
