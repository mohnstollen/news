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

    @Test
    fun releaseRhythm() {
        val day = 24 * 60 * 60 * 1000L
        fun every(days: Double, n: Int = 10) = (0 until n).map { (it * days * day).toLong() }
        assertEquals("etwa täglich", io.github.mohnstollen.news.data.Rhythm.describe(every(1.0)))
        assertEquals("mehrmals pro Woche", io.github.mohnstollen.news.data.Rhythm.describe(every(3.0)))
        assertEquals("etwa wöchentlich", io.github.mohnstollen.news.data.Rhythm.describe(every(7.0)))
        assertEquals("etwa alle zwei Wochen", io.github.mohnstollen.news.data.Rhythm.describe(every(14.0)))
        assertEquals("etwa monatlich", io.github.mohnstollen.news.data.Rhythm.describe(every(30.0)))
        assertEquals("selten", io.github.mohnstollen.news.data.Rhythm.describe(every(90.0)))
        assertEquals(null, io.github.mohnstollen.news.data.Rhythm.describe(every(7.0, n = 2)))
        // One long summer break does not change a weekly show.
        assertEquals("etwa wöchentlich", io.github.mohnstollen.news.data.Rhythm.describe(every(7.0, 8) + listOf(-60 * day)))
    }

    @Test
    fun previewAndSuggestionsForOnePodcast() = runBlocking {
        val format = java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME.withLocale(java.util.Locale.ENGLISH)
        fun date(daysAgo: Long) = format.format(java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC).minusDays(daysAgo))
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.requestUrl!!.encodedPath
                if (path == "/neu.xml") {
                    val items = (0 until 12).joinToString("") {
                        "<item><title>Folge ${12 - it}</title><pubDate>${date(it * 7L + 2)}</pubDate>" +
                            "<itunes:duration>45:00</itunes:duration></item>"
                    }
                    return MockResponse().setBody(
                        "<rss version=\"2.0\" xmlns:itunes=\"http://www.itunes.com/dtds/podcast-1.0.dtd\"><channel>" +
                            "<title>Neuer Podcast</title>$items</channel></rss>",
                    )
                }
                val term = request.requestUrl!!.queryParameter("term").orEmpty()
                terms += term
                return MockResponse().setBody(
                    when (term) {
                        "Zweiter" -> results(podcast("Zweiter", "https://example.org/zwei.xml", "1310", "Music"))
                        "Konzert" -> results(podcast("Konzert-Talk", server.url("/neu.xml").toString(), "1310", "Music"))
                        else -> results()
                    },
                )
            }
        }
        server.start()
        val http = newHttpClient()
        val repo = Repository(db, FeedFetcher(http), FullTextExtractor(http), SettingsStore(context))
        val first = repo.feedDao.insert(Feed(url = "https://example.org/eins.xml", title = "Erster"))
        val second = repo.feedDao.insert(Feed(url = "https://example.org/zwei.xml", title = "Zweiter"))
        for ((feedId, word) in listOf(first to "Sport", second to "Konzert")) {
            repo.articleDao.insertAll(
                (1..3).map {
                    Article(feedId = feedId, guid = "$word$it", title = "$word Nummer $it", pubDate = it.toLong(), fetchedAt = 0,
                        enclosureUrl = "https://example.org/$word$it.mp3")
                },
            )
        }
        val recommender = Recommender(http, repo, baseUrl = server.url("/"), country = "DE")
        assertEquals(setOf("Erster", "Zweiter"), recommender.seeds().map { it.title }.toSet())

        // Only the chosen podcast is looked up.
        val suggestions = recommender.recommend(seedFeedId = second)
        assertEquals(listOf("Konzert-Talk"), suggestions.map { it.podcast.title })
        assertTrue(terms.none { it == "Erster" || it == "Sport" })

        val preview = recommender.preview(suggestions.single().podcast)
        assertEquals("Neuer Podcast", preview.title)
        assertEquals(12, preview.episodeCount)
        assertEquals("etwa wöchentlich", preview.rhythm)
        assertEquals(10, preview.latest.size)
        assertEquals("Folge 12", preview.latest.first().title)
        assertEquals(2700L, preview.latest.first().durationSeconds)
        assertEquals("vor 2 Tagen", io.github.mohnstollen.news.ui.daysAgo(preview.latest.first().pubDate))
    }
}
