package io.github.mohnstollen.news

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.mohnstollen.news.data.AppDatabase
import io.github.mohnstollen.news.data.Article
import io.github.mohnstollen.news.data.Feed
import io.github.mohnstollen.news.data.FeedFetcher
import io.github.mohnstollen.news.data.FullTextExtractor
import io.github.mohnstollen.news.data.Keywords
import io.github.mohnstollen.news.data.Recommender
import io.github.mohnstollen.news.data.Repository
import io.github.mohnstollen.news.data.SettingsStore
import io.github.mohnstollen.news.data.newHttpClient
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(AndroidJUnit4::class)
@Config(application = TestApp::class, sdk = [34])
class RecommendationsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val server = MockWebServer()
    private val terms = CopyOnWriteArrayList<String>()
    private val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()

    @After
    fun tearDown() {
        db.close()
        server.shutdown()
    }

    private fun podcast(title: String, feed: String, genreId: String, genre: String) =
        """{"collectionName":"$title","artistName":"Sender","feedUrl":"$feed","primaryGenreName":"$genre",""" +
            """"genreIds":["$genreId","26"],"artworkUrl600":"https://img.example.org/$genreId.jpg"}"""

    private fun results(vararg items: String) = """{"resultCount":${items.size},"results":[${items.joinToString(",")}]}"""

    @Test
    fun keywordsAreTheWordsTitlesShare() {
        val titles = listOf(
            "Bundesliga: Bayern gewinnt das Topspiel",
            "Die Bundesliga am Wochenende",
            "Bayern und Dortmund im Pokal",
            "Folge 12: Sportschau Spezial",
            "Formel 1 in Monza",
        )
        assertEquals(listOf("Bayern", "Bundesliga"), Keywords.of(titles, exclude = "Sportschau"))
        assertTrue(Keywords.of(listOf("Nur ein Titel")).isEmpty())
    }

    @Test
    fun suggestsPodcastsOfTheSameCategoryAboutTheSameTopics() = runBlocking {
        val sportschau = "https://example.org/sportschau.xml"
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val term = request.requestUrl!!.queryParameter("term").orEmpty()
                terms += term
                assertEquals("podcast", request.requestUrl!!.queryParameter("media"))
                val body = when (term) {
                    "Sportschau" -> results(podcast("Sportschau", sportschau, "1545", "Sport"))
                    "Bundesliga" -> results(
                        podcast("FC Bayern Podcast", "https://example.org/fcb.xml", "1545", "Sport"),
                        podcast("Bundesliga Talk", "https://example.org/talk.xml", "1545", "Sport"),
                        podcast("Bundesliga-Aktien", "https://example.org/boerse.xml", "1321", "Wirtschaft"),
                    )
                    "Bayern" -> results(
                        podcast("FC Bayern Podcast", "https://example.org/fcb.xml", "1545", "Sport"),
                        podcast("Sportschau", "http://www.example.org/sportschau.xml/", "1545", "Sport"),
                    )
                    else -> results()
                }
                return MockResponse().setHeader("Content-Type", "application/json").setBody(body)
            }
        }
        server.start()

        val http = newHttpClient()
        val repo = Repository(db, FeedFetcher(http), FullTextExtractor(http), SettingsStore(context))
        val feedId = repo.feedDao.insert(Feed(url = sportschau, title = "Sportschau"))
        // A news feed without audio is no podcast and asks nothing.
        repo.feedDao.insert(Feed(url = "https://example.org/news.xml", title = "Nachrichten"))
        val titles = listOf("Bundesliga: Bayern gewinnt", "Die Bundesliga kompakt", "Bayern im Pokal", "Tennis heute")
        repo.articleDao.insertAll(
            titles.mapIndexed { i, t ->
                Article(
                    feedId = feedId, guid = "g$i", title = t, pubDate = 1000L - i, fetchedAt = 0,
                    enclosureUrl = "https://example.org/$i.mp3",
                )
            },
        )

        val recommender = Recommender(http, repo, baseUrl = server.url("/"), country = "DE")
        val suggestions = recommender.recommend()
        assertEquals(listOf("FC Bayern Podcast", "Bundesliga Talk"), suggestions.map { it.podcast.title })
        assertTrue(suggestions.all { it.because == "Sportschau" })
        assertEquals("found for both words", "Bayern", suggestions.first().keyword)
        assertEquals("https://img.example.org/1545.jpg", suggestions.first().podcast.artworkUrl)
        assertTrue("other categories are left out", suggestions.none { it.podcast.genre == "Wirtschaft" })
        assertTrue("only podcast titles and keywords go out", terms.toSet() == setOf("Sportschau", "Bayern", "Bundesliga"))

        assertEquals(listOf("Bundesliga Talk"), recommender.search("Bundesliga").map { it.title }.filter { it.endsWith("Talk") })
    }
}
