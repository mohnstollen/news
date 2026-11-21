package io.github.mohnstollen.news

import android.app.Application
import androidx.room.Room
import io.github.mohnstollen.news.data.AppDatabase
import io.github.mohnstollen.news.data.FeedFetcher
import io.github.mohnstollen.news.data.FullTextExtractor
import io.github.mohnstollen.news.data.NcLoginFlow
import io.github.mohnstollen.news.data.Prefs
import io.github.mohnstollen.news.data.Recommender
import io.github.mohnstollen.news.data.Repository
import io.github.mohnstollen.news.data.SettingsStore
import io.github.mohnstollen.news.data.SyncManager
import io.github.mohnstollen.news.data.newHttpClient
import io.github.mohnstollen.news.playback.Downloads
import io.github.mohnstollen.news.playback.SleepTimer
import io.github.mohnstollen.news.work.RefreshWorker
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.launch

class AppContainer(app: Application, val features: AppFeatures) {
    val http = newHttpClient()
    /** For episodes: streams and downloads run for a long time, unlike feed requests. */
    val mediaHttp = http.newBuilder().callTimeout(0, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    private val db = Room.databaseBuilder(app, AppDatabase::class.java, "news.db")
        .addMigrations(*AppDatabase.MIGRATIONS)
        .build()
    val settings = SettingsStore(app, Prefs(ncFolder = features.syncFolder))
    val repository = Repository(db, FeedFetcher(http), FullTextExtractor(http), settings)
    val sync = SyncManager(http, settings, repository)
    val loginFlow = NcLoginFlow(http, settings, sync, repository, clientName = features.clientName)
    val downloads = Downloads(app, repository, settings)
    val sleepTimer = SleepTimer()
    val recommender = Recommender(http, repository)

    init {
        if (features.podcasts) repository.onNewEpisodes = downloads::enqueueNewEpisodes
        repository.onFeedDeleting = downloads::deleteFiles
    }
}

/** Base of both apps; the app modules subclass it and choose their [features]. */
open class NewsApp : Application() {
    open val features: AppFeatures = AppFeatures.NEWS
    val container: AppContainer by lazy { AppContainer(this, features) }

    override fun onCreate() {
        super.onCreate()
        RefreshWorker.schedule(this, container.settings.current)
        // Episodes downloaded while this app still played podcasts cannot be used any more.
        if (!features.podcasts) {
            container.repository.scope.launch {
                if (container.repository.articleDao.downloads().isNotEmpty()) container.downloads.deleteAll()
            }
        }
    }
}
