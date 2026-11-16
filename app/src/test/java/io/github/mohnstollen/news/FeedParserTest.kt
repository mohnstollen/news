package io.github.mohnstollen.news

import io.github.mohnstollen.news.data.DateParser
import io.github.mohnstollen.news.data.FeedParser
import io.github.mohnstollen.news.data.NotAFeedException
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class FeedParserTest {
    private fun parse(xml: String, base: String = "https://example.org/feed") =
        FeedParser.parse(Jsoup.parse(xml, base, Parser.xmlParser()), base)

    @Test
    fun rss2() {
        val feed = parse(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0" xmlns:content="http://purl.org/rss/1.0/modules/content/" xmlns:dc="http://purl.org/dc/elements/1.1/">
              <channel>
                <title>Beispiel &amp; Co</title>
                <link>https://example.org/</link>
                <item>
                  <title>Erster &amp; bester Artikel</title>
                  <link>/artikel/1</link>
                  <guid isPermaLink="false">id-1</guid>
                  <pubDate>Mon, 16 Nov 2026 10:30:00 +0100</pubDate>
                  <dc:creator>Erika Muster</dc:creator>
                  <description>Kurz</description>
                  <content:encoded><![CDATA[<p>Langer <b>Text</b></p>]]></content:encoded>
                </item>
                <item>
                  <title>Zweiter</title>
                  <guid>https://example.org/artikel/2</guid>
                  <description>&lt;p&gt;Escaped &lt;i&gt;HTML&lt;/i&gt;&lt;/p&gt;</description>
                </item>
                <item><description>Nur Text ohne Titel</description></item>
              </channel>
            </rss>
            """.trimIndent(),
        )
        assertEquals("Beispiel & Co", feed.title)
        assertEquals("https://example.org/", feed.siteUrl)
        assertEquals(3, feed.items.size)

        val first = feed.items[0]
        assertEquals("Erster & bester Artikel", first.title)
        assertEquals("https://example.org/artikel/1", first.link)
        assertEquals("id-1", first.guid)
        assertEquals("Erika Muster", first.author)
        assertEquals("<p>Langer <b>Text</b></p>", first.contentHtml)
        assertEquals(Instant.parse("2026-11-16T09:30:00Z").toEpochMilli(), first.pubDate)

        val second = feed.items[1]
        assertEquals("https://example.org/artikel/2", second.link)
        assertEquals("<p>Escaped <i>HTML</i></p>", second.contentHtml)
        assertNull(second.pubDate)

        assertEquals("Nur Text ohne Titel", feed.items[2].title)
    }

    @Test
    fun atom() {
        val feed = parse(
            """
            <?xml version="1.0" encoding="utf-8"?>
            <feed xmlns="http://www.w3.org/2005/Atom">
              <title>Atom Blog</title>
              <link rel="self" href="https://example.org/feed"/>
              <link rel="alternate" href="https://example.org/blog"/>
              <entry>
                <title type="html">Hallo &amp;amp; Welt</title>
                <link rel="alternate" href="post/1"/>
                <id>tag:example.org,2026:1</id>
                <updated>2026-11-15T08:00:00Z</updated>
                <published>2026-11-14T08:00:00+01:00</published>
                <author><name>Max</name></author>
                <content type="xhtml"><div xmlns="http://www.w3.org/1999/xhtml"><p>Inhalt</p></div></content>
              </entry>
              <entry>
                <title>Nur Summary</title>
                <link href="https://example.org/post/2"/>
                <id>2</id>
                <summary type="html">&lt;p&gt;Kurzfassung&lt;/p&gt;</summary>
              </entry>
            </feed>
            """.trimIndent(),
        )
        assertEquals("Atom Blog", feed.title)
        assertEquals("https://example.org/blog", feed.siteUrl)
        assertEquals(2, feed.items.size)
        val first = feed.items[0]
        assertEquals("Hallo & Welt", first.title)
        assertEquals("https://example.org/post/1", first.link)
        assertEquals("tag:example.org,2026:1", first.guid)
        assertEquals("Max", first.author)
        assertTrue(first.contentHtml!!.contains("<p>Inhalt</p>"))
        assertEquals(Instant.parse("2026-11-14T07:00:00Z").toEpochMilli(), first.pubDate)
        assertEquals("<p>Kurzfassung</p>", feed.items[1].contentHtml)
    }

    @Test
    fun rdf() {
        val feed = parse(
            """
            <?xml version="1.0"?>
            <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#" xmlns="http://purl.org/rss/1.0/" xmlns:dc="http://purl.org/dc/elements/1.1/">
              <channel rdf:about="https://example.org/"><title>RDF</title><link>https://example.org/</link></channel>
              <item rdf:about="https://example.org/a">
                <title>A</title><link>https://example.org/a</link><dc:date>2026-01-02T03:04:05Z</dc:date>
              </item>
            </rdf:RDF>
            """.trimIndent(),
        )
        assertEquals("RDF", feed.title)
        assertEquals(1, feed.items.size)
        assertEquals("https://example.org/a", feed.items[0].guid)
        assertEquals(Instant.parse("2026-01-02T03:04:05Z").toEpochMilli(), feed.items[0].pubDate)
    }

    @Test(expected = NotAFeedException::class)
    fun htmlIsNotAFeed() {
        parse("<!DOCTYPE html><html><head><title>x</title></head><body><p>Hallo</p></body></html>")
    }

    @Test
    fun dates() {
        val expected = Instant.parse("2026-11-16T09:30:00Z").toEpochMilli()
        listOf(
            "Mon, 16 Nov 2026 10:30:00 +0100",
            "Mon, 16 Nov 2026 09:30:00 GMT",
            "16 Nov 2026 09:30:00 Z",
            "Mon, 16 Nov 2026 09:30 UT",
            "Mon, 16 Nov 26 09:30:00 +0000",
            "Tue, 16 Nov 2026 04:30:00 EST",
            "Mon,  16 Nov 2026 10:30:00 +01:00",
            "2026-11-16T10:30:00+01:00",
            "2026-11-16T09:30:00Z",
            "2026-11-16T09:30:00.000Z",
            "2026-11-16T09:30:00",
        ).forEach { assertEquals(it, expected, DateParser.parse(it)) }
        assertNotNull(DateParser.parse("2026-11-16"))
        assertNull(DateParser.parse("gestern"))
        assertNull(DateParser.parse(""))
    }
}
