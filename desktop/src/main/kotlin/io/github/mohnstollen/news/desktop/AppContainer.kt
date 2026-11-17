package io.github.mohnstollen.news.desktop

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.github.mohnstollen.news.data.AppDatabase
import io.github.mohnstollen.news.data.AppDatabase_Impl
import io.github.mohnstollen.news.data.AppDirs
import io.github.mohnstollen.news.data.DesktopMigrations
import io.github.mohnstollen.news.data.FeedFetcher
import io.github.mohnstollen.news.data.FullTextExtractor
import io.github.mohnstollen.news.data.Repository
import io.github.mohnstollen.news.data.SettingsStore
import io.github.mohnstollen.news.data.SyncManager
import io.github.mohnstollen.news.data.newHttpClient
import kotlinx.coroutines.Dispatchers
import java.io.File

fun openDatabase(file: File?): AppDatabase {
    val builder = if (file == null) {
        Room.inMemoryDatabaseBuilder { AppDatabase_Impl() }
    } else {
        Room.databaseBuilder(name = file.absolutePath) { AppDatabase_Impl() }
    }
    return builder
        .setDriver(BundledSQLiteDriver())
        .addMigrations(*DesktopMigrations.ALL)
        .setQueryCoroutineContext(Dispatchers.IO)
        .build()
}

/** Everything the app shares between window, tray and background updates. */
class AppContainer(
    dbFile: File? = File(AppDirs.data, "news.db"),
    val settings: SettingsStore = SettingsStore(),
) {
    private val http = newHttpClient()
    val db = openDatabase(dbFile)
    val repository = Repository(db, FeedFetcher(http), FullTextExtractor(http), settings)
    val sync = SyncManager(http, settings, repository)
    val images = ImageLoader(http, File(AppDirs.data, "images"))
}
