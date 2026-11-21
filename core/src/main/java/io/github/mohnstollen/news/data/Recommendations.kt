package io.github.mohnstollen.news.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.Locale

/** A podcast from Apple's public catalogue. */
data class CatalogPodcast(
    val title: String,
    val author: String,
    val feedUrl: String,
    val artworkUrl: String?,
    val genre: String?,
    val genreId: String?,
)

/** A suggestion and why: [because] is the subscribed podcast, [keyword] what they share. */
data class Recommendation(
    val podcast: CatalogPodcast,
    val because: String,
    val keyword: String?,
)

/**
 * Suggests podcasts similar to the subscribed ones, using the iTunes Search API (no account or key).
 * For each subscribed podcast it looks up the category, takes the words its recent episode titles
 * use most, and searches the catalogue for podcasts of the same category about those words.
 * Only podcast titles and these words are sent to Apple.
 */
class Recommender(
    private val http: OkHttpClient,
    private val repo: Repository,
    private val baseUrl: HttpUrl = "https://itunes.apple.com/".toHttpUrl(),
    private val country: String = Locale.getDefault().country.ifBlank { "DE" },
) {
    /** Searches the catalogue, e.g. for a podcast by name. */
    suspend fun search(term: String, limit: Int = 25): List<CatalogPodcast> = withContext(Dispatchers.IO) {
        if (term.isBlank()) return@withContext emptyList()
        val url = baseUrl.newBuilder().addPathSegment("search")
            .addQueryParameter("media", "podcast")
            .addQueryParameter("entity", "podcast")
            .addQueryParameter("country", country)
            .addQueryParameter("limit", limit.toString())
            .addQueryParameter("term", term.trim())
            .build()
        http.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Katalog nicht erreichbar (HTTP ${response.code})")
            val results = JSONObject(response.body?.string().orEmpty()).optJSONArray("results")
                ?: return@withContext emptyList()
            (0 until results.length()).mapNotNull { i ->
                val o = results.getJSONObject(i)
                val feedUrl = o.optString("feedUrl").takeIf { it.startsWith("http") } ?: return@mapNotNull null
                val genreIds = o.optJSONArray("genreIds")
                CatalogPodcast(
                    title = o.optString("collectionName").ifBlank { o.optString("trackName") },
                    author = o.optString("artistName"),
                    feedUrl = feedUrl,
                    artworkUrl = o.optString("artworkUrl600").ifBlank { o.optString("artworkUrl100") }.ifBlank { null },
                    genre = o.optString("primaryGenreName").ifBlank { null },
                    // The first id that is not the general "Podcasts" (26).
                    genreId = genreIds?.let { ids -> (0 until ids.length()).map { ids.getString(it) }.firstOrNull { it != "26" } },
                )
            }
        }
    }

    /** The subscribed podcasts suggestions can start from, the most listened first. */
    suspend fun seeds(): List<Feed> = withContext(Dispatchers.IO) {
        val listened = repo.listenDao.feedUrls().map(::normalize).toSet()
        repo.feedDao.all().filter { repo.articleDao.hasEpisodes(it.id) }
            .sortedWith(compareByDescending<Feed> { normalize(it.url) in listened }.thenBy { it.title.lowercase() })
    }

    /**
     * Suggestions for the podcast [seedFeedId], or for the first [maxSeeds] subscribed podcasts
     * (most listened first) when it is null.
     */
    /** Loads a catalogue podcast's feed for a look before subscribing. */
    suspend fun preview(podcast: CatalogPodcast): PodcastPreview = withContext(Dispatchers.IO) {
        val feed = repo.previewFeed(podcast.feedUrl)
        val episodes = feed.items.filter { it.pubDate != null }.sortedByDescending { it.pubDate }
        PodcastPreview(
            title = feed.title.ifBlank { podcast.title },
            episodeCount = feed.items.size,
            rhythm = Rhythm.describe(episodes.mapNotNull { it.pubDate }),
            latest = episodes.take(PREVIEW_EPISODES).map { PreviewEpisode(it.title, it.pubDate!!, it.durationSeconds) },
        )
    }

    suspend fun recommend(
        seedFeedId: Long? = null,
        maxSeeds: Int = 5,
        perSeed: Int = if (seedFeedId != null) 12 else 4,
    ): List<Recommendation> = withContext(Dispatchers.IO) {
        val feeds = repo.feedDao.all()
        val subscribed = feeds.map { normalize(it.url) }.toSet()
        val podcasts = seeds().filter { seedFeedId == null || it.id == seedFeedId }.take(maxSeeds)
        val seen = mutableSetOf<String>()
        val out = mutableListOf<Recommendation>()
        for (feed in podcasts) {
            val found = search(feed.title, 10)
            val self = found.firstOrNull { normalize(it.feedUrl) == normalize(feed.url) }
                ?: found.firstOrNull { it.title.equals(feed.title, ignoreCase = true) }
            val titles = repo.articleDao.recentTitles(feed.id, 60)
            val keywords = Keywords.of(titles, exclude = feed.title).take(3)
            val candidates = mutableListOf<Pair<CatalogPodcast, String?>>()
            for (word in keywords) search(word).forEach { candidates += it to word }
            // Without telling words, the category itself.
            if (candidates.isEmpty() && self?.genre != null) search(self.genre).forEach { candidates += it to null }

            val picks = candidates
                .filter { (p, _) -> normalize(p.feedUrl) !in subscribed && normalize(p.feedUrl) !in seen }
                .filter { (p, _) -> self?.genreId == null || p.genreId == self.genreId }
                // Podcasts found for several words fit best.
                .groupBy { normalize(it.first.feedUrl) }
                .values.sortedByDescending { it.size }
                .take(perSeed)
                .map { group -> group.first() }
            for ((podcast, word) in picks) {
                seen += normalize(podcast.feedUrl)
                out += Recommendation(podcast, because = feed.title, keyword = word)
            }
        }
        out
    }

    private fun normalize(url: String) =
        url.trim().removePrefix("https://").removePrefix("http://").removePrefix("www.").trimEnd('/').lowercase()
}

data class PreviewEpisode(val title: String, val pubDate: Long, val durationSeconds: Long?)

data class PodcastPreview(
    val title: String,
    val episodeCount: Int,
    /** E.g. "etwa wöchentlich"; null with too few dated episodes. */
    val rhythm: String?,
    val latest: List<PreviewEpisode>,
)

private const val PREVIEW_EPISODES = 10

/** How often a podcast publishes, from its episodes' dates. */
object Rhythm {
    private const val DAY = 24 * 60 * 60 * 1000.0

    /** [dates] in epoch milliseconds, any order. Looks at the newest ten episodes. */
    fun describe(dates: List<Long>): String? {
        val newest = dates.sortedDescending().take(10)
        if (newest.size < 3) return null
        val gaps = newest.zipWithNext { a, b -> (a - b) / DAY }.sorted()
        val median = gaps[gaps.size / 2]
        return when {
            median < 1.5 -> "etwa täglich"
            median < 5 -> "mehrmals pro Woche"
            median < 10 -> "etwa wöchentlich"
            median < 20 -> "etwa alle zwei Wochen"
            median < 45 -> "etwa monatlich"
            else -> "selten"
        }
    }
}

/** The words that stand out in a podcast's episode titles. */
object Keywords {
    private val stopwords = setOf(
        // German
        "aber", "alle", "allem", "also", "auch", "auf", "aus", "bei", "beim", "bis", "das", "dass", "dem", "den",
        "der", "des", "die", "dies", "diese", "dieser", "doch", "ein", "eine", "einem", "einen", "einer", "eines",
        "für", "gegen", "geht", "gibt", "hat", "haben", "heute", "ihr", "ist", "jetzt", "kann", "kein", "keine",
        "mehr", "mit", "nach", "nicht", "noch", "nur", "oder", "ohne", "sich", "sie", "sind", "so", "über", "um",
        "und", "uns", "unter", "vom", "von", "vor", "was", "wenn", "wer", "wie", "wir", "wird", "zum", "zur",
        "zwischen", "folge", "teil", "spezial", "special", "interview", "gast", "gäste", "woche", "warum",
        // English
        "about", "after", "and", "are", "episode", "for", "from", "have", "how", "into", "its", "not", "our",
        "part", "the", "this", "what", "when", "why", "with", "you", "your",
    )
    private val separators = Regex("[^\\p{L}\\p{N}]+")

    /** Words used in at least two titles, most frequent first; words of [exclude] left out. */
    fun of(titles: List<String>, exclude: String = ""): List<String> {
        val skip = exclude.lowercase().split(separators).toSet()
        val counts = mutableMapOf<String, Int>()
        val shown = mutableMapOf<String, String>()
        for (title in titles) {
            for (word in title.split(separators).toSet()) {
                val key = word.lowercase()
                if (key.length < 4 || key in stopwords || key in skip || key.all { it.isDigit() }) continue
                counts[key] = (counts[key] ?: 0) + 1
                // Keep the spelling with a capital letter, as in names.
                if (shown[key]?.first()?.isUpperCase() != true) shown[key] = word
            }
        }
        return counts.filter { it.value >= 2 }.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .map { shown.getValue(it.key) }
    }
}
