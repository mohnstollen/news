package io.github.mohnstollen.news

import io.github.mohnstollen.news.data.DateParser
import io.github.mohnstollen.news.data.DurationParser
import io.github.mohnstollen.news.data.MediaTypes
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

    @Test
    fun podcastRss() {
        val feed = parse(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0" xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd">
              <channel>
                <title>Der Podcast</title>
                <link>https://example.org/</link>
                <itunes:image href="https://cdn.example.org/cover.jpg"/>
                <image><url>https://example.org/klein.png</url></image>
                <item>
                  <title>Folge 2</title>
                  <guid isPermaLink="false">ep-2</guid>
                  <itunes:author>Max Muster</itunes:author>
                  <itunes:duration>1:02:03</itunes:duration>
                  <itunes:image href="/folge2.jpg"/>
                  <enclosure url="https://cdn.example.org/titelbild.jpg" type="image/jpeg" length="1000"/>
                  <enclosure url="/audio/folge2.mp3" type="audio/mpeg; charset=binary" length="12345678"/>
                </item>
                <item>
                  <title>Folge 1</title>
                  <itunes:duration>754</itunes:duration>
                  <enclosure url="https://cdn.example.org/folge1.m4a?token=x" length="0"/>
                </item>
                <item>
                  <title>Nur ein Bild</title>
                  <enclosure url="https://example.org/bild.jpg" type="image/jpeg" length="1"/>
                </item>
              </channel>
            </rss>
            """.trimIndent(),
        )
        assertEquals("itunes:image wins over the channel image", "https://cdn.example.org/cover.jpg", feed.imageUrl)

        val ep2 = feed.items[0]
        assertEquals("Max Muster", ep2.author)
        assertEquals(3723L, ep2.durationSeconds)
        assertEquals("https://example.org/folge2.jpg", ep2.imageUrl)
        val audio = ep2.enclosure!!
        assertEquals("the image enclosure is skipped", "https://example.org/audio/folge2.mp3", audio.url)
        assertEquals("audio/mpeg", audio.type)
        assertEquals(12345678L, audio.length)

        val ep1 = feed.items[1]
        assertEquals(754L, ep1.durationSeconds)
        assertNull(ep1.imageUrl)
        assertEquals("type guessed from the file name", "audio/mp4", ep1.enclosure!!.type)
        assertNull("length 0 means unknown", ep1.enclosure!!.length)

        assertNull("images are not episodes", feed.items[2].enclosure)
    }

    @Test
    fun mediaRssAndChannelImage() {
        val feed = parse(
            """
            <rss version="2.0" xmlns:media="http://search.yahoo.com/mrss/">
              <channel>
                <title>Video</title>
                <image><url>https://example.org/logo.png</url></image>
                <item>
                  <title>Clip</title>
                  <media:group>
                    <media:content url="https://example.org/clip.webm" type="video/webm" fileSize="999" duration="95"/>
                    <media:thumbnail url="https://example.org/clip.jpg"/>
                  </media:group>
                </item>
                <item>
                  <title>Mit Foto</title>
                  <media:content url="https://example.org/foto.jpg" medium="image"/>
                  <enclosure url="ftp://example.org/datei.mp3" type="audio/mpeg"/>
                </item>
              </channel>
            </rss>
            """.trimIndent(),
        )
        assertEquals("https://example.org/logo.png", feed.imageUrl)
        val clip = feed.items[0]
        assertEquals("https://example.org/clip.webm", clip.enclosure!!.url)
        assertEquals("video/webm", clip.enclosure!!.type)
        assertEquals(999L, clip.enclosure!!.length)
        assertEquals(95L, clip.durationSeconds)
        assertEquals("https://example.org/clip.jpg", clip.imageUrl)

        val photo = feed.items[1]
        assertNull("only http(s) media, and photos are not episodes", photo.enclosure)
        assertEquals("https://example.org/foto.jpg", photo.imageUrl)
    }

    @Test
    fun atomEnclosure() {
        val feed = parse(
            """
            <feed xmlns="http://www.w3.org/2005/Atom">
              <title>Atom-Podcast</title>
              <logo>/logo.svg</logo>
              <entry>
                <title>Episode</title>
                <id>urn:ep:1</id>
                <link rel="alternate" href="https://example.org/ep1"/>
                <link rel="enclosure" href="https://example.org/ep1.ogg" type="audio/ogg" length="4242"/>
              </entry>
              <entry>
                <title>Ohne Audio</title>
                <id>urn:ep:2</id>
              </entry>
            </feed>
            """.trimIndent(),
        )
        assertEquals("https://example.org/logo.svg", feed.imageUrl)
        assertEquals("https://example.org/ep1", feed.items[0].link)
        assertEquals("https://example.org/ep1.ogg", feed.items[0].enclosure!!.url)
        assertEquals(4242L, feed.items[0].enclosure!!.length)
        assertNull(feed.items[1].enclosure)
    }

    @Test
    fun durations() {
        assertEquals(45L, DurationParser.parse("45"))
        assertEquals(125L, DurationParser.parse("2:05"))
        assertEquals(3723L, DurationParser.parse(" 01:02:03 "))
        assertEquals(90L, DurationParser.parse("90.7"))
        for (bad in listOf("", "0", "00:00:00", "abc", "1:2:3:4", "1:", "-5", "NaN", "Infinity")) {
            assertNull("'$bad'", DurationParser.parse(bad))
        }
    }

    @Test
    fun mediaTypesFromFileNames() {
        assertEquals("audio/mpeg", MediaTypes.fromUrl("https://example.org/a/b.MP3?x=1#t"))
        assertEquals("video/mp4", MediaTypes.fromUrl("https://example.org/v.m4v"))
        assertNull(MediaTypes.fromUrl("https://example.org/seite.html"))
        assertNull(MediaTypes.fromUrl("https://example.org/download"))
        assertNull(MediaTypes.fromUrl("https://example.mp3/download"))
        assertTrue(MediaTypes.isPlayable("application/ogg"))
    }

    @Test
    fun chaptersFromTheFeed() {
        val feed = parse(
            """
            <rss version="2.0" xmlns:psc="http://podlove.org/simple-chapters" xmlns:podcast="https://podcastindex.org/namespace/1.0">
              <channel><title>Kapitel</title>
                <item>
                  <title>Mit Podlove-Kapiteln</title>
                  <enclosure url="https://example.org/a.mp3" type="audio/mpeg"/>
                  <psc:chapters version="1.2">
                    <psc:chapter start="00:12:30.500" title="Zweites Thema"/>
                    <psc:chapter start="00:00:00.000" title="Begrüßung"/>
                    <psc:chapter start="1:02:03" title=""/>
                    <psc:chapter start="kaputt" title="Ohne Zeit"/>
                  </psc:chapters>
                </item>
                <item>
                  <title>Mit Kapitel-Datei</title>
                  <enclosure url="https://example.org/b.mp3" type="audio/mpeg"/>
                  <podcast:chapters url="/b-chapters.json" type="application/json+chapters"/>
                </item>
              </channel>
            </rss>
            """.trimIndent(),
        )
        val chapters = feed.items[0].chapters
        assertEquals(listOf(0L, 750_500L, 3_723_000L), chapters.map { it.startMs })
        assertEquals(listOf("Begrüßung", "Zweites Thema", "Kapitel 3"), chapters.map { it.title })
        assertNull(feed.items[0].chaptersUrl)
        assertEquals("https://example.org/b-chapters.json", feed.items[1].chaptersUrl)
        assertTrue(feed.items[1].chapters.isEmpty())
    }

}
