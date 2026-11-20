package io.github.mohnstollen.news.data

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.util.Locale

data class ParsedFeed(
    val title: String,
    val siteUrl: String?,
    val items: List<ParsedItem>,
    /** Cover art of the feed (podcast artwork or RSS channel image). */
    val imageUrl: String? = null,
)

data class ParsedItem(
    val guid: String,
    val title: String,
    val link: String?,
    val author: String?,
    val contentHtml: String?,
    val pubDate: Long?,
    /** Attached audio or video file, e.g. a podcast episode. Images and other files are ignored. */
    val enclosure: ParsedEnclosure? = null,
    /** Playing time in seconds, if the feed states it. */
    val durationSeconds: Long? = null,
    /** Artwork of this entry, if it has its own. */
    val imageUrl: String? = null,
)

data class ParsedEnclosure(
    val url: String,
    /** MIME type as given by the feed, or guessed from the file extension. */
    val type: String?,
    /** File size in bytes; null if unknown. */
    val length: Long?,
)

class NotAFeedException : Exception("Kein RSS- oder Atom-Feed")

/** Parses RSS 0.9x/2.0, RSS 1.0 (RDF) and Atom from a document read with Jsoup's XML parser. */
object FeedParser {

    fun parse(doc: Document, baseUrl: String): ParsedFeed {
        val root = doc.children().firstOrNull() ?: throw NotAFeedException()
        return when (root.tagName().lowercase()) {
            "rss" -> parseRss(root.child("channel") ?: throw NotAFeedException(), null, baseUrl)
            "rdf:rdf" -> parseRss(root.child("channel") ?: throw NotAFeedException(), root, baseUrl)
            "feed" -> parseAtom(root, baseUrl)
            else -> throw NotAFeedException()
        }
    }

    private fun parseRss(channel: Element, rdfRoot: Element?, baseUrl: String): ParsedFeed {
        val siteUrl = channel.childText("link")?.let { resolve(baseUrl, it) }
        val itemParent = rdfRoot ?: channel
        val items = itemParent.children("item").mapNotNull { item ->
            val link = item.childText("link")?.let { resolve(baseUrl, it) }
                ?: item.child("guid")
                    ?.takeIf { !it.attr("isPermaLink").equals("false", ignoreCase = true) }
                    ?.text()?.trim()?.takeIf { it.startsWith("http") }
            val html = item.child("content:encoded")?.htmlValue()?.takeIf { it.isNotBlank() }
                ?: item.child("description")?.htmlValue()
            val date = (item.childText("pubDate") ?: item.childText("dc:date") ?: item.childText("date"))
                ?.let(DateParser::parse)
            val title = item.childText("title")?.let(::plainText)
            val mediaContents = item.mediaContents()
            buildItem(
                guid = item.childText("guid") ?: item.attr("rdf:about").takeIf { it.isNotBlank() },
                title = title,
                link = link,
                author = item.childText("dc:creator") ?: item.childText("author") ?: item.childText("itunes:author"),
                html = html,
                date = date,
                enclosure = rssEnclosure(item, mediaContents, baseUrl),
                duration = item.childText("itunes:duration")?.let(DurationParser::parse)
                    ?: mediaContents.firstNotNullOfOrNull { DurationParser.parse(it.attr("duration")) },
                image = rssItemImage(item, mediaContents, baseUrl),
            )
        }
        return ParsedFeed(
            title = channel.childText("title")?.let(::plainText).orEmpty(),
            siteUrl = siteUrl,
            items = items,
            imageUrl = listOf(
                channel.child("itunes:image")?.attr("href"),
                channel.child("image")?.childText("url"),
                rdfRoot?.child("image")?.let { it.childText("url") ?: it.attr("rdf:about") },
            ).firstNotNullOfOrNull { it?.let { url -> httpUrl(baseUrl, url) } },
        )
    }

    /** First playable `<enclosure>`, else the first playable Media RSS `<media:content>`. */
    private fun rssEnclosure(item: Element, mediaContents: List<Element>, baseUrl: String): ParsedEnclosure? =
        item.children("enclosure").firstNotNullOfOrNull {
            playable(baseUrl, it.attr("url"), it.attr("type"), it.attr("length"))
        } ?: mediaContents
            .filter { it.attr("medium").let { m -> m.isEmpty() || m == "audio" || m == "video" } }
            .firstNotNullOfOrNull { playable(baseUrl, it.attr("url"), it.attr("type"), it.attr("fileSize")) }

    private fun rssItemImage(item: Element, mediaContents: List<Element>, baseUrl: String): String? {
        val candidates = sequence {
            yield(item.child("itunes:image")?.attr("href"))
            yield(item.child("media:thumbnail")?.attr("url"))
            yield(item.child("media:group")?.child("media:thumbnail")?.attr("url"))
            yieldAll(
                mediaContents.filter { it.attr("medium") == "image" || it.attr("type").startsWith("image/") }
                    .map { it.attr("url") },
            )
        }
        return candidates.firstNotNullOfOrNull { it?.let { url -> httpUrl(baseUrl, url) } }
    }

    /** `<media:content>` elements, directly in the item or inside a `<media:group>`. */
    private fun Element.mediaContents(): List<Element> =
        children("media:content") + children("media:group").flatMap { it.children("media:content") }

    private fun parseAtom(feed: Element, baseUrl: String): ParsedFeed {
        val items = feed.children("entry").mapNotNull { entry ->
            val contentEl = entry.child("content") ?: entry.child("summary")
            val html = contentEl?.let {
                if (it.attr("type").equals("xhtml", ignoreCase = true)) it.html() else it.htmlValue()
            }
            val date = (entry.childText("published") ?: entry.childText("updated") ?: entry.childText("issued"))
                ?.let(DateParser::parse)
            buildItem(
                guid = entry.childText("id"),
                title = entry.childText("title")?.let(::plainText),
                link = atomLink(entry, baseUrl),
                author = entry.child("author")?.childText("name"),
                html = html,
                date = date,
                enclosure = entry.children("link")
                    .filter { it.attr("rel").equals("enclosure", ignoreCase = true) }
                    .firstNotNullOfOrNull { playable(baseUrl, it.attr("href"), it.attr("type"), it.attr("length")) },
                duration = entry.childText("itunes:duration")?.let(DurationParser::parse),
                image = entry.child("itunes:image")?.attr("href")?.let { httpUrl(baseUrl, it) },
            )
        }
        return ParsedFeed(
            title = feed.childText("title")?.let(::plainText).orEmpty(),
            siteUrl = atomLink(feed, baseUrl),
            items = items,
            imageUrl = listOf(feed.child("itunes:image")?.attr("href"), feed.childText("logo"), feed.childText("icon"))
                .firstNotNullOfOrNull { it?.let { url -> httpUrl(baseUrl, url) } },
        )
    }

    /** Returns the attachment if it is audio or video at an http(s) address, else null. */
    private fun playable(baseUrl: String, href: String, type: String, length: String): ParsedEnclosure? {
        val url = httpUrl(baseUrl, href) ?: return null
        val givenType = type.trim().lowercase().substringBefore(';').trim().takeIf { it.isNotEmpty() }
        val finalType = when {
            givenType == null || givenType == "application/octet-stream" -> MediaTypes.fromUrl(url) ?: return null
            MediaTypes.isPlayable(givenType) -> givenType
            else -> return null
        }
        return ParsedEnclosure(url, finalType, length.trim().toLongOrNull()?.takeIf { it > 0 })
    }

    /** Resolves [href] against [base]; null unless the result is an http(s) address. */
    private fun httpUrl(base: String, href: String): String? {
        if (href.isBlank()) return null
        return resolve(base, href).takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) }
    }

    private fun atomLink(parent: Element, baseUrl: String): String? {
        val links = parent.children("link")
        val best = links.firstOrNull { it.attr("rel").equals("alternate", ignoreCase = true) }
            ?: links.firstOrNull { !it.hasAttr("rel") }
        return best?.attr("href")?.takeIf { it.isNotBlank() }?.let { resolve(baseUrl, it) }
    }

    private fun buildItem(
        guid: String?,
        title: String?,
        link: String?,
        author: String?,
        html: String?,
        date: Long?,
        enclosure: ParsedEnclosure? = null,
        duration: Long? = null,
        image: String? = null,
    ): ParsedItem? {
        val cleanHtml = html?.trim()?.takeIf { it.isNotEmpty() }
        val finalTitle = title?.takeIf { it.isNotBlank() }
            ?: cleanHtml?.let { plainText(it).take(80) }?.takeIf { it.isNotBlank() }
            ?: return null
        val finalGuid = guid?.takeIf { it.isNotBlank() } ?: link ?: "$finalTitle|${date ?: 0}"
        return ParsedItem(
            guid = finalGuid,
            title = finalTitle,
            link = link,
            author = author?.takeIf { it.isNotBlank() },
            contentHtml = cleanHtml,
            pubDate = date,
            enclosure = enclosure,
            durationSeconds = duration,
            imageUrl = image,
        )
    }

    fun plainText(html: String): String = Jsoup.parse(html).text().trim()

    private fun resolve(base: String, href: String): String =
        try {
            URI(base).resolve(href.trim()).toString()
        } catch (e: Exception) {
            href.trim()
        }

    private fun Element.children(name: String): List<Element> =
        children().filter { it.tagName().equals(name, ignoreCase = true) }

    private fun Element.child(name: String): Element? =
        children().firstOrNull { it.tagName().equals(name, ignoreCase = true) }

    private fun Element.childText(name: String): String? =
        child(name)?.text()?.trim()?.takeIf { it.isNotEmpty() }

    /** Escaped or CDATA markup comes back as text; unescaped inline markup as child elements. */
    private fun Element.htmlValue(): String =
        if (children().isNotEmpty()) html() else wholeText()
}

/** Audio and video types a podcast player can handle. */
object MediaTypes {
    private val byExtension = mapOf(
        "mp3" to "audio/mpeg",
        "m4a" to "audio/mp4",
        "m4b" to "audio/mp4",
        "aac" to "audio/aac",
        "ogg" to "audio/ogg",
        "oga" to "audio/ogg",
        "opus" to "audio/opus",
        "flac" to "audio/flac",
        "wav" to "audio/wav",
        "mp4" to "video/mp4",
        "m4v" to "video/mp4",
        "mov" to "video/quicktime",
        "webm" to "video/webm",
    )

    fun isPlayable(type: String): Boolean =
        type.startsWith("audio/") || type.startsWith("video/") || type == "application/ogg"

    /** Guesses the type from the file extension of the address; null if it does not look like media. */
    fun fromUrl(url: String): String? {
        val path = url.substringBefore('#').substringBefore('?')
        val name = path.substringAfterLast('/')
        if (!name.contains('.')) return null
        return byExtension[name.substringAfterLast('.').lowercase()]
    }
}

/** Parses `itunes:duration` and Media RSS durations: "SS", "MM:SS" or "HH:MM:SS", seconds may have decimals. */
object DurationParser {
    /** Returns whole seconds, or null if the text is not a positive duration. */
    fun parse(text: String): Long? {
        val parts = text.trim().split(':')
        if (parts.size > 3) return null
        var total = 0.0
        for (part in parts) {
            val value = part.trim().toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 } ?: return null
            total = total * 60 + value
        }
        return total.toLong().takeIf { it > 0 }
    }
}

object DateParser {
    private fun pattern(p: String): DateTimeFormatter =
        DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern(p).toFormatter(Locale.ENGLISH)

    private val rfc822 = listOf(
        "d MMM yyyy HH:mm:ss Z",
        "d MMM yyyy HH:mm:ss zzz",
        "d MMM yyyy HH:mm Z",
        "d MMM yyyy HH:mm zzz",
        "d MMM yy HH:mm:ss Z",
        "d MMM yy HH:mm:ss zzz",
    ).map(::pattern)

    private val dayPrefix = Regex("^[A-Za-z]{2,9},?\\s+(?=\\d)")

    /** Returns epoch milliseconds, or null if the text is not a recognisable date. */
    fun parse(text: String): Long? {
        val s = text.trim().replace(Regex("\\s+"), " ")
        if (s.isEmpty()) return null

        runCatching { return OffsetDateTime.parse(s).toInstant().toEpochMilli() }
        runCatching { return Instant.parse(s).toEpochMilli() }
        runCatching { return ZonedDateTime.parse(s).toInstant().toEpochMilli() }
        runCatching { return LocalDateTime.parse(s).toInstant(ZoneOffset.UTC).toEpochMilli() }
        runCatching { return LocalDate.parse(s).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli() }

        var r = s.replace(dayPrefix, "")
        r = r.replace(Regex(" (UT|Z)$"), " GMT")
        // "+02:00" -> "+0200"
        r = r.replace(Regex("([+-]\\d{2}):(\\d{2})$"), "$1$2")
        for (f in rfc822) {
            runCatching { return ZonedDateTime.parse(r, f).toInstant().toEpochMilli() }
        }
        return null
    }
}
