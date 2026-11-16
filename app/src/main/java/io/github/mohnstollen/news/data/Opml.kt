package io.github.mohnstollen.news.data

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.io.InputStream

data class OpmlEntry(
    val url: String,
    val title: String,
    val siteUrl: String? = null,
    val category: String? = null,
)

object Opml {

    fun parse(input: InputStream): List<OpmlEntry> {
        val doc = Jsoup.parse(input, null, "", Parser.xmlParser())
        val body = doc.select("opml > body").firstOrNull()
            ?: throw FeedException("Die Datei ist keine OPML-Datei")
        val result = LinkedHashMap<String, OpmlEntry>()
        collect(body, null, result)
        return result.values.toList()
    }

    private fun collect(parent: Element, category: String?, into: MutableMap<String, OpmlEntry>) {
        for (outline in parent.children()) {
            if (!outline.tagName().equals("outline", ignoreCase = true)) continue
            val name = outline.attrIgnoreCase("title") ?: outline.attrIgnoreCase("text")
            val xmlUrl = outline.attrIgnoreCase("xmlUrl")
            if (xmlUrl != null) {
                val url = normalizeUrl(xmlUrl)
                into.putIfAbsent(
                    url,
                    OpmlEntry(
                        url = url,
                        title = name ?: url,
                        siteUrl = outline.attrIgnoreCase("htmlUrl"),
                        category = category,
                    ),
                )
            } else {
                // A folder. Nested folders are flattened to "Outer / Inner".
                val path = listOfNotNull(category, name).joinToString(" / ").ifBlank { null }
                collect(outline, path, into)
            }
        }
    }

    private fun Element.attrIgnoreCase(name: String): String? =
        attributes().firstOrNull { it.key.equals(name, ignoreCase = true) }
            ?.value?.trim()?.takeIf { it.isNotEmpty() }

    fun write(entries: List<OpmlEntry>): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<opml version=\"2.0\">\n  <head>\n    <title>News</title>\n  </head>\n  <body>\n")
        val (plain, grouped) = entries.partition { it.category.isNullOrBlank() }
        plain.forEach { sb.appendOutline(it, "    ") }
        grouped.groupBy { it.category!! }.toSortedMap(String.CASE_INSENSITIVE_ORDER).forEach { (category, feeds) ->
            sb.append("    <outline text=\"${esc(category)}\" title=\"${esc(category)}\">\n")
            feeds.forEach { sb.appendOutline(it, "      ") }
            sb.append("    </outline>\n")
        }
        sb.append("  </body>\n</opml>\n")
        return sb.toString()
    }

    private fun StringBuilder.appendOutline(e: OpmlEntry, indent: String) {
        append(indent)
        append("<outline type=\"rss\" text=\"${esc(e.title)}\" title=\"${esc(e.title)}\" xmlUrl=\"${esc(e.url)}\"")
        if (!e.siteUrl.isNullOrBlank()) append(" htmlUrl=\"${esc(e.siteUrl)}\"")
        append("/>\n")
    }

    private fun esc(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
}
