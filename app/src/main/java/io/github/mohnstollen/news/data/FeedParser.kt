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
)

data class ParsedItem(
    val guid: String,
    val title: String,
    val link: String?,
    val author: String?,
    val contentHtml: String?,
    val pubDate: Long?,
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
            buildItem(
                guid = item.childText("guid") ?: item.attr("rdf:about").takeIf { it.isNotBlank() },
                title = title,
                link = link,
                author = item.childText("dc:creator") ?: item.childText("author"),
                html = html,
                date = date,
            )
        }
        return ParsedFeed(
            title = channel.childText("title")?.let(::plainText).orEmpty(),
            siteUrl = siteUrl,
            items = items,
        )
    }

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
            )
        }
        return ParsedFeed(
            title = feed.childText("title")?.let(::plainText).orEmpty(),
            siteUrl = atomLink(feed, baseUrl),
            items = items,
        )
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
