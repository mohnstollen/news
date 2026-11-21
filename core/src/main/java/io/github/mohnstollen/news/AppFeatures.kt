package io.github.mohnstollen.news

/**
 * What sets the two apps apart. Both are built from this module; each app's application class
 * picks one of these.
 */
data class AppFeatures(
    val appName: String,
    /** Podcast playback, downloads, queue, statistics and podcast discovery. */
    val podcasts: Boolean,
    /** Default Nextcloud folder; different per app, so the two do not mix their subscriptions. */
    val syncFolder: String,
    val allItems: String,
    val manageFeeds: String,
    val addFeed: String,
) {
    /** Name the app gives itself towards Nextcloud, e.g. for the app password. */
    val clientName: String get() = "$appName (Android)"

    companion object {
        val NEWS = AppFeatures(
            appName = "News",
            podcasts = false,
            syncFolder = "News",
            allItems = "Alle Artikel",
            manageFeeds = "Feeds verwalten",
            addFeed = "Feed hinzufügen",
        )

        val PODFANG = AppFeatures(
            appName = "Podfang",
            podcasts = true,
            syncFolder = "Podfang",
            allItems = "Alle Folgen",
            manageFeeds = "Podcasts verwalten",
            addFeed = "Podcast hinzufügen",
        )
    }
}
