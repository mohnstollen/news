package io.github.mohnstollen.news.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.github.mohnstollen.news.R
import io.github.mohnstollen.news.data.AppRelease
import io.github.mohnstollen.news.data.UpdateChecker
import io.github.mohnstollen.news.ui.container

/** Whether a newer version of the app is out, and telling about it. */
object AppUpdates {
    const val INTERVAL_MS = 24L * 60 * 60 * 1000
    private const val CHANNEL = "updates"
    private const val NOTIFICATION_ID = 7

    fun installedVersion(context: Context): String =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "0"

    /** The newer version found earlier, if it is still newer than what is installed. */
    fun available(context: Context): AppRelease? {
        val prefs = context.container.settings.current
        val version = prefs.updateVersion ?: return null
        val url = prefs.updateUrl ?: return null
        if (!UpdateChecker.isNewer(version, installedVersion(context))) return null
        return AppRelease(version, url, url)
    }

    /** Asks GitHub now and notes the outcome; returns the newer release, if any. Throws on network trouble. */
    suspend fun check(context: Context): AppRelease? {
        val container = context.container
        val latest = container.updates.latest(container.features.releasePrefix)
        val newer = latest?.takeIf { UpdateChecker.isNewer(it.version, installedVersion(context)) }
        container.settings.update {
            it.copy(lastUpdateCheck = System.currentTimeMillis(), updateVersion = newer?.version, updateUrl = newer?.downloadUrl)
        }
        return newer
    }

    /** From the background job: at most once a day, and each new version is announced once. */
    suspend fun checkInBackground(context: Context) {
        val prefs = context.container.settings.current
        if (!prefs.updateCheck || System.currentTimeMillis() - prefs.lastUpdateCheck < INTERVAL_MS) return
        val newer = runCatching { check(context) }.getOrNull() ?: return
        if (newer.version == prefs.updateNotified) return
        if (announce(context, newer)) context.container.settings.update { it.copy(updateNotified = newer.version) }
    }

    /** "Podfang 1.2 ist verfügbar"; a tap downloads it. Returns false if not shown. */
    fun announce(context: Context, release: AppRelease): Boolean {
        if (!Notifier.canNotify(context)) return false
        val name = context.container.features.appName
        val manager = NotificationManagerCompat.from(context)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "App-Updates", NotificationManager.IMPORTANCE_LOW))
        val open = Intent(Intent.ACTION_VIEW, Uri.parse(release.downloadUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("$name ${release.version} ist verfügbar")
            .setContentText("Antippen zum Herunterladen, dann installieren. Deine Daten bleiben.")
            .setContentIntent(PendingIntent.getActivity(context, 0, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .setAutoCancel(true)
            .build()
        return try {
            manager.notify(NOTIFICATION_ID, notification)
            true
        } catch (e: SecurityException) {
            false
        }
    }
}
