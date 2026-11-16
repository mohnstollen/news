package io.github.mohnstollen.news

import androidx.compose.ui.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.mohnstollen.news.data.Article
import io.github.mohnstollen.news.ui.articleHtml
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = TestApp::class, sdk = [34])
class ReaderHtmlTest {
    private fun html(article: Article) = articleHtml(
        article, "Feed", article.content, Color.Black, Color.Gray, Color.Blue, Color.Yellow, Color.Black,
    )

    private val article = Article(
        feedId = 1, guid = "g", title = "Zahlen <b>& Fakten", link = "https://example.org/a?x=1&y=\"2\"",
        content = "<p>Text</p>", pubDate = 0, fetchedAt = 0, note = "Notiz <script>",
    )

    @Test
    fun headlineLinksToTheArticleAndTextIsEscaped() {
        val page = html(article)
        assertTrue(
            page,
            page.contains("<h1><a href=\"https://example.org/a?x=1&amp;y=&quot;2&quot;\">Zahlen &lt;b&gt;&amp; Fakten</a></h1>"),
        )
        assertTrue(page.contains("Notiz &lt;script&gt;"))
        assertTrue(page.contains("<p>Text</p>"))
    }

    @Test
    fun headlineWithoutLinkIsPlainText() {
        val page = html(article.copy(link = null))
        assertTrue(page.contains("<h1>Zahlen &lt;b&gt;&amp; Fakten</h1>"))
        assertFalse(page.contains("<a href"))
    }
}
