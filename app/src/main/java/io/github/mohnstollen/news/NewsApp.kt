package io.github.mohnstollen.news

import android.app.Application
import androidx.room.Room
import io.github.mohnstollen.news.data.AppDatabase
import io.github.mohnstollen.news.data.FeedFetcher
import io.github.mohnstollen.news.data.FullTextExtractor
import io.github.mohnstollen.news.data.NcLoginFlow
import io.github.mohnstollen.news.data.Recommender
import io.github.mohnstollen.news.data.Repository
import io.github.mohnstollen.news.data.SettingsStore
import io.github.mohnstollen.news.data.SyncManager
import io.github.mohnstollen.news.data.newHttpClient
import io.github.mohnstollen.news.playback.Downloads
import io.github.mohnstollen.news.playback.SleepTimer
import io.github.mohnstollen.news.work.RefreshWorker
import java.util.concurrent.TimeUnit

class AppContainer(app: Application) {
    val http = newHttpClient()
    /** For episodes: streams and downloads run for a long time, unlike feed requests. */
    val mediaHttp = http.newBuilder().callTimeout(0, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    private val db = Room.databaseBuilder(app, AppDatabase::class.java, "news.db")
        .addMigrations(*AppDatabase.MIGRATIONS)
        .build()
    val settings = SettingsStore(app)
    val repository = Repository(db, FeedFetcher(http), FullTextExtractor(http), settings)
    val sync = SyncManager(http, settings, repository)
    val loginFlow = NcLoginFlow(http, settings, sync, repository)
    val downloads = Downloads(app, repository, settings)
    val sleepTimer = SleepTimer()
    val recommender = Recommender(http, repository)

    init {
        repository.onNewEpisodes = downloads::enqueueNewEpisodes
        repository.onFeedDeleting = downloads::deleteFiles
    }
}

open class NewsApp : Application() {
    val container: AppContainer by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        RefreshWorker.schedule(this, container.settings.current)
    }
}
