package io.github.mohnstollen.news

import io.github.mohnstollen.news.data.Opml
import io.github.mohnstollen.news.data.OpmlEntry
import org.junit.Assert.assertEquals
import org.junit.Test

class OpmlTest {
    @Test
    fun parsesFoldersAndIgnoresDuplicates() {
        val opml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <opml version="1.0"><head><title>x</title></head><body>
              <outline text="Lose" xmlUrl="https://a.example/feed" htmlUrl="https://a.example"/>
              <outline text="Technik">
                <outline type="rss" title="B &amp; C" text="ignored" xmlUrl="b.example/rss"/>
                <outline text="Linux"><outline text="D" xmlurl="https://d.example/atom.xml"/></outline>
              </outline>
              <outline text="Doppelt" xmlUrl="https://a.example/feed"/>
            </body></opml>
        """.trimIndent()
        val entries = Opml.parse(opml.byteInputStream())
        assertEquals(
            listOf(
                OpmlEntry("https://a.example/feed", "Lose", "https://a.example", null),
                OpmlEntry("https://b.example/rss", "B & C", null, "Technik"),
                OpmlEntry("https://d.example/atom.xml", "D", null, "Technik / Linux"),
            ),
            entries,
        )
    }

    @Test
    fun roundTrip() {
        val entries = listOf(
            OpmlEntry("https://a.example/feed?x=1&y=2", "A <\"quoted\"> & more", "https://a.example", null),
            OpmlEntry("https://b.example/rss", "Bär", null, "Ordner & Co"),
            OpmlEntry("https://c.example/rss", "C", null, "Ordner & Co"),
        )
        assertEquals(entries, Opml.parse(Opml.write(entries).byteInputStream()))
    }
}
