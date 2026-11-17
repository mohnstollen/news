package io.github.mohnstollen.news.desktop

import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HtmlLayoutTest {
    private val opened = mutableListOf<String>()
    private fun layout(html: String) = HtmlLayout(SpanStyle()) { opened += it }.layout(html, "https://example.org/a/")

    @Test
    fun paragraphsHeadingsAndWhitespace() {
        val blocks = layout("<h2>Titel</h2><p>Ein   <b>fetter</b>\n Satz.</p><p> </p><p>Zweiter<br>Teil</p>")
        val texts = blocks.filterIsInstance<HtmlBlock.Text>()
        assertEquals(listOf("Titel", "Ein fetter Satz.", "Zweiter\nTeil"), texts.map { it.text.text })
        assertEquals(HtmlBlock.Kind.Heading1, texts[0].kind)
    }

    @Test
    fun listsPicturesAndLinks() {
        val blocks = layout(
            "<ol><li>eins</li><li>zwei <a href=\"/x\">Link</a></li></ol>" +
                "<figure><img src=\"bild.png\" alt=\"Ein Bild\"><figcaption>Unterschrift</figcaption></figure><hr>",
        )
        val items = blocks.filterIsInstance<HtmlBlock.Text>()
        assertEquals("1.", items[0].bullet)
        assertEquals("2.", items[1].bullet)
        val picture = blocks.filterIsInstance<HtmlBlock.Picture>().single()
        assertEquals("https://example.org/a/bild.png", picture.src)
        assertEquals(HtmlBlock.Kind.Caption, items[2].kind)
        assertTrue(blocks.last() is HtmlBlock.Rule)

        val link = items[1].text.getLinkAnnotations(0, items[1].text.length).single().item as LinkAnnotation.Clickable
        assertEquals("https://example.org/x", link.tag)
    }

    @Test
    fun preformattedKeepsLines() {
        val code = layout("<pre><code>a  = 1\n  b</code></pre>").single() as HtmlBlock.Text
        assertEquals(HtmlBlock.Kind.Code, code.kind)
        assertEquals("a  = 1\n  b", code.text.text)
    }

    @Test
    fun relativeTimes() {
        val now = 1_000_000_000_000L
        assertEquals("gerade eben", relativeTime(now - 10_000, now))
        assertEquals("vor 5 Minuten", relativeTime(now - 5 * 60_000, now))
        assertEquals("vor 3 Stunden", relativeTime(now - 3 * 3_600_000, now))
        assertEquals("gestern", relativeTime(now - 30 * 3_600_000L, now))
    }
}
