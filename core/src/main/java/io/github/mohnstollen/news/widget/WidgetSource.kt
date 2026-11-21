package io.github.mohnstollen.news.widget

import android.content.Context
import io.github.mohnstollen.news.data.Repository

/** What a widget lists: unread articles of all feeds, of one folder, or of one feed. */
sealed interface WidgetSource {
    data object All : WidgetSource
    data class Folder(val name: String) : WidgetSource
    data class Feed(val id: Long) : WidgetSource

    fun encode(): String = when (this) {
        All -> "all"
        is Folder -> "folder:$name"
        is Feed -> "feed:$id"
    }

    companion object {
        /** Anything unknown, including widgets placed before there was a choice, shows all feeds. */
        fun decode(text: String?): WidgetSource = when {
            text == null -> All
            text.startsWith("feed:") -> text.removePrefix("feed:").toLongOrNull()?.let(::Feed) ?: All
            text.startsWith("folder:") -> text.removePrefix("folder:").takeIf { it.isNotEmpty() }?.let(::Folder) ?: All
            else -> All
        }
    }
}

/** The choice of each placed widget, by widget id. */
object WidgetSources {
    private fun prefs(context: Context) = context.getSharedPreferences("widgets", Context.MODE_PRIVATE)

    fun get(context: Context, widgetId: Int): WidgetSource =
        WidgetSource.decode(prefs(context).getString("source_$widgetId", null))

    fun set(context: Context, widgetId: Int, source: WidgetSource) =
        prefs(context).edit().putString("source_$widgetId", source.encode()).apply()

    fun remove(context: Context, widgetIds: IntArray) =
        prefs(context).edit().apply { widgetIds.forEach { remove("source_$it") } }.apply()
}

/** A source as a widget shows it; [missing] explains a feed or folder that is gone. */
data class ResolvedSource(val title: String, val feedId: Long?, val folder: String?, val missing: String? = null)

suspend fun Repository.resolve(source: WidgetSource, appName: String): ResolvedSource = when (source) {
    WidgetSource.All -> ResolvedSource(appName, null, null)
    is WidgetSource.Folder ->
        if (feedDao.all().any { it.category == source.name }) ResolvedSource(source.name, null, source.name)
        else ResolvedSource(source.name, null, source.name, missing = "Diesen Ordner gibt es nicht mehr")
    is WidgetSource.Feed ->
        feedDao.byId(source.id)?.let { ResolvedSource(it.title, it.id, null) }
            ?: ResolvedSource(appName, source.id, null, missing = "Diesen Feed gibt es nicht mehr")
}
