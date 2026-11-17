package io.github.mohnstollen.news

import android.app.Application
import androidx.room.Room
import io.github.mohnstollen.news.data.AppDatabase
import io.github.mohnstollen.news.data.Migrations
import io.github.mohnstollen.news.data.FeedFetcher
import io.github.mohnstollen.news.data.FullTextExtractor
import io.github.mohnstollen.news.data.Repository
import io.github.mohnstollen.news.data.SettingsStore
import io.github.mohnstollen.news.data.SyncManager
import io.github.mohnstollen.news.data.newHttpClient
import io.github.mohnstollen.news.work.RefreshWorker

class AppContainer(app: Application) {
    private val http = newHttpClient()
    private val db = Room.databaseBuilder(app, AppDatabase::class.java, "news.db")
        .addMigrations(*Migrations.ALL)
        .build()
    val settings = SettingsStore(app)
    val repository = Repository(db, FeedFetcher(http), FullTextExtractor(http), settings)
    val sync = SyncManager(http, settings, repository)
}

open class NewsApp : Application() {
    val container: AppContainer by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        RefreshWorker.schedule(this, container.settings.current)
    }
}
