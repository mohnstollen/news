package io.github.mohnstollen.news.work

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.mohnstollen.news.R
import io.github.mohnstollen.news.data.ArticleListItem
import io.github.mohnstollen.news.playback.RemotePlayback
import io.github.mohnstollen.news.ui.MainActivity
import io.github.mohnstollen.news.ui.container
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** The "new articles" notification shown after a background update. */
object Notifier {
    const val EXTRA_ARTICLE_ID = "articleId"
    private const val NO_ARTICLE = 0L
    private const val CHANNEL = "new_articles"
    private const val NOTIFICATION_ID = 1
    private const val MAX_LINES = 5

    /** False if the user has not granted the permission or has blocked the app's notifications. */
    fun canNotify(context: Context): Boolean {
        val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        return granted && NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    /**
     * Replaces an earlier notification. [articles] are expected newest first. Returns false if not
     * shown. In Podfang it offers to play or queue the episodes; [cover] is the single episode's.
     */
    fun showNewArticles(context: Context, articles: List<ArticleListItem>, cover: Bitmap? = null): Boolean {
        if (articles.isEmpty() || !canNotify(context)) return false
        val words = (context.applicationContext as? io.github.mohnstollen.news.NewsApp)?.features
            ?: io.github.mohnstollen.news.AppFeatures.NEWS
        val manager = NotificationManagerCompat.from(context)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, words.pick("Neue Artikel", "Neue Folgen"), NotificationManager.IMPORTANCE_DEFAULT),
        )

        val single = articles.singleOrNull()
        val open = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        // One article opens directly; several open the list. Always set, so that the value of
        // an earlier notification cannot linger in the reused PendingIntent.
        open.putExtra(EXTRA_ARTICLE_ID, single?.id ?: NO_ARTICLE)
        val pending = PendingIntent.getActivity(
            context, 0, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setNumber(articles.size)
        if (single != null) {
            builder.setContentTitle(single.title).setContentText(single.feedTitle)
                .setStyle(NotificationCompat.BigTextStyle().bigText(single.summary.ifBlank { single.feedTitle }))
            if (cover != null) builder.setLargeIcon(cover)
        } else {
            val feeds = articles.map { it.feedTitle }.distinct()
            val style = NotificationCompat.InboxStyle()
            articles.take(MAX_LINES).forEach { style.addLine("${it.feedTitle}: ${it.title}") }
            if (articles.size > MAX_LINES) style.setSummaryText("und ${articles.size - MAX_LINES} weitere")
            builder.setContentTitle("${articles.size} " + words.pick("neue Artikel", "neue Folgen"))
                .setContentText(feeds.take(3).joinToString(", ") + if (feeds.size > 3) " …" else "")
                .setStyle(style)
        }
        if (words.podcasts) {
            val ids = articles.map { it.id }.toLongArray()
            if (single != null) {
                builder.addAction(0, "Abspielen", EpisodeActions.intent(context, EpisodeActions.ACTION_PLAY, ids))
                builder.addAction(0, "Einreihen", EpisodeActions.intent(context, EpisodeActions.ACTION_ENQUEUE, ids))
            } else {
                builder.addAction(0, "Alle einreihen", EpisodeActions.intent(context, EpisodeActions.ACTION_ENQUEUE, ids))
            }
        }
        try {
            manager.notify(NOTIFICATION_ID, builder.build())
        } catch (e: SecurityException) {
            return false
        }
        return true
    }

    fun cancel(context: Context) = NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
}

/** The buttons of the "new episodes" notification (Podfang). */
class EpisodeActions : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val ids = intent.getLongArrayExtra(EXTRA_IDS)?.toList().orEmpty()
        if (ids.isEmpty()) return
        val app = context.applicationContext
        val repo = app.container.repository
        Notifier.cancel(app)
        val pending = goAsync()
        CoroutineScope(Dispatchers.Main.immediate).launch {
            try {
                when (intent.action) {
                    // Queued like a swipe in "Neu": downloaded on Wi-Fi if so set.
                    ACTION_ENQUEUE -> ids.asReversed().forEach { repo.enqueue(it) }
                    ACTION_PLAY -> withTimeoutOrNull(TIMEOUT_MS) { RemotePlayback.play(app, ids.first()) }
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_PLAY = "io.github.mohnstollen.news.work.PLAY_EPISODE"
        const val ACTION_ENQUEUE = "io.github.mohnstollen.news.work.ENQUEUE_EPISODES"
        private const val EXTRA_IDS = "ids"
        private const val TIMEOUT_MS = 8_000L

        fun intent(context: Context, action: String, ids: LongArray): PendingIntent {
            val intent = Intent(context, EpisodeActions::class.java).setAction(action).putExtra(EXTRA_IDS, ids)
            return PendingIntent.getBroadcast(
                context, action.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
    }
}
