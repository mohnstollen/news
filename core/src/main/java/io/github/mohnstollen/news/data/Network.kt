package io.github.mohnstollen.news.data

import net.dankito.readability4j.Readability4J
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import org.jsoup.safety.Safelist
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

const val USER_AGENT = "Mozilla/5.0 (Linux; Android) NewsReader/1.0"
private const val MAX_BODY_BYTES = 8 * 1024 * 1024

/**
 * Feeds of long-running podcasts list every episode with long show notes and can be much larger
 * than a web page. They are read up to this size; the newest entries come first.
 */
private const val MAX_FEED_BYTES = 10 * 1024 * 1024

fun newHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .callTimeout(90, TimeUnit.SECONDS)
    .build()

/** Error with a message that can be shown to the user as is. */
class FeedException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** [truncated]: the response was longer than allowed and only its beginning was read. */
private class Body(val bytes: ByteArray, val charset: String?, val truncated: Boolean = false)

/**
 * Reads at most [limit] bytes. Beyond that, a page is refused; a feed ([cut] = true) is cut off,
 * as its beginning still holds the newest entries.
 */
private fun Response.readLimited(limit: Int = MAX_BODY_BYTES, cut: Boolean = false): Body {
    val body = body ?: throw IOException("Leere Antwort")
    val charset = body.contentType()?.charset()?.name()
    val out = ByteArrayOutputStream()
    body.byteStream().use { input ->
        val buf = ByteArray(16 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > limit) {
                if (cut) return Body(out.toByteArray(), charset, truncated = true)
                throw IOException("Die Seite ist größer als ${limit / (1024 * 1024)} MB")
            }
        }
    }
    return Body(out.toByteArray(), charset)
}

fun normalizeUrl(input: String): String {
    var url = input.trim()
    if (url.startsWith("feed://", ignoreCase = true)) url = "https://" + url.substring(7)
    if (url.startsWith("feed:", ignoreCase = true)) url = url.substring(5)
    if (!url.contains("://")) url = "https://$url"
    return url
}

class FeedFetcher(private val client: OkHttpClient) {

    sealed interface Result {
        data object NotModified : Result
        data class Fetched(val feed: ParsedFeed, val etag: String?, val lastModified: String?) : Result
    }

    data class Discovered(val url: String, val feed: ParsedFeed)

    /** Blocking. Uses a conditional request when cache headers are known. */
    fun fetch(url: String, etag: String? = null, lastModified: String? = null): Result {
        val request = Request.Builder().url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/rss+xml, application/atom+xml, application/xml;q=0.9, text/xml;q=0.8, */*;q=0.5")
            .apply {
                if (etag != null) header("If-None-Match", etag)
                if (lastModified != null) header("If-Modified-Since", lastModified)
            }
            .build()
        client.newCall(request).execute().use { response ->
            if (response.code == 304) return Result.NotModified
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val body = response.readLimited(MAX_FEED_BYTES, cut = true)
            val feed = parseFeed(body, response.request.url.toString())
            return Result.Fetched(feed, response.header("ETag"), response.header("Last-Modified"))
        }
    }

    private fun parseFeed(body: Body, baseUrl: String): ParsedFeed {
        val doc = Jsoup.parse(ByteArrayInputStream(body.bytes), body.charset, baseUrl, Parser.xmlParser())
        val feed = FeedParser.parse(doc, baseUrl)
        // The parser closes what the cut left open; the last entry is incomplete, so it goes.
        return if (body.truncated) feed.copy(items = feed.items.dropLast(1)) else feed
    }

    /**
     * Blocking. Checks that [input] is a feed. If it is a web page instead, follows the page's
     * feed links and a few conventional paths. Throws [FeedException] if nothing is found.
     */
    fun discover(input: String): Discovered {
        val url = normalizeUrl(input)
        val request = try {
            Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        } catch (e: IllegalArgumentException) {
            throw FeedException("Ungültige Adresse")
        }
        val (body, finalUrl) = try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw FeedException("Seite nicht erreichbar (HTTP ${response.code})")
                // Long enough for a large feed; a web page that big is refused below anyway.
                response.readLimited(MAX_FEED_BYTES, cut = true) to response.request.url.toString()
            }
        } catch (e: IOException) {
            throw FeedException("Seite nicht erreichbar: ${e.message ?: e.javaClass.simpleName}", e)
        }

        try {
            // Keep the address the user gave: a redirect target may be temporary.
            return Discovered(url, parseFeed(body, finalUrl))
        } catch (e: NotAFeedException) {
            // fall through: maybe an HTML page that links to its feed
        }

        val page = Jsoup.parse(ByteArrayInputStream(body.bytes), body.charset, finalUrl)
        val linked = page.select("link[rel~=(?i)alternate][href]")
            .filter { it.attr("type").contains(Regex("rss|atom|rdf|xml", RegexOption.IGNORE_CASE)) }
            .map { it.absUrl("href") }
        val guesses = listOf("/feed", "/rss", "/feed.xml", "/rss.xml", "/atom.xml", "/index.xml")
            .mapNotNull { runCatching { java.net.URI(finalUrl).resolve(it).toString() }.getOrNull() }
        val candidates = (linked + guesses).filter { it.startsWith("http") }.distinct().take(8)
        for (candidate in candidates) {
            val result = runCatching { fetch(candidate) }.getOrNull()
            if (result is Result.Fetched) return Discovered(candidate, result.feed)
        }
        throw FeedException("Unter dieser Adresse wurde kein RSS- oder Atom-Feed gefunden")
    }
}

/** Downloads a web page and reduces it to the readable article body. */
class FullTextExtractor(private val client: OkHttpClient) {

    /** Blocking. Returns sanitized HTML, or null if no article text could be found. */
    fun extract(url: String): String? {
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        val (body, finalUrl) = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            response.readLimited() to response.request.url.toString()
        }
        val doc = Jsoup.parse(ByteArrayInputStream(body.bytes), body.charset, finalUrl)
        // Lazily loaded pictures keep their real address in data-src.
        for (img in doc.select("img[data-src]")) {
            if (!img.hasAttr("src") || img.attr("src").startsWith("data:")) img.attr("src", img.attr("data-src"))
        }
        // Resolve addresses here: Readability4J loses the port when it does so itself.
        for (attribute in listOf("src", "href")) {
            for (element in doc.select("[$attribute]")) {
                element.absUrl(attribute).takeIf { it.isNotEmpty() }?.let { element.attr(attribute, it) }
            }
        }
        val article = Readability4J(finalUrl, doc).parse()
        val html = article.content ?: return null
        if ((article.textContent?.trim()?.length ?: 0) < 250) return null
        return sanitize(html, finalUrl)
    }

    companion object {
        private val safelist: Safelist = Safelist.relaxed()
            .addTags("figure", "figcaption", "hr")

        /** Removes scripts, styles and tracking attributes and makes links absolute. */
        fun sanitize(html: String, baseUrl: String?): String =
            Jsoup.clean(html, baseUrl.orEmpty(), safelist)
    }
}
