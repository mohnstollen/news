package io.github.mohnstollen.news.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

/** A piece of an article, as laid out by [HtmlContent]. */
sealed interface HtmlBlock {
    enum class Kind { Paragraph, Heading1, Heading2, Heading3, Quote, Code, Caption }

    data class Text(val text: AnnotatedString, val kind: Kind, val indent: Int = 0, val bullet: String? = null) : HtmlBlock
    data class Picture(val src: String, val alt: String) : HtmlBlock
    data object Rule : HtmlBlock
}

/**
 * Turns the sanitized article HTML (see FullTextExtractor.sanitize) into blocks. Supports what
 * the sanitizer lets through: paragraphs, headings, lists, quotes, code, tables (as text),
 * figures, pictures, links and inline emphasis.
 */
class HtmlLayout(private val linkStyle: SpanStyle, private val onLink: (String) -> Unit) {

    private val blocks = mutableListOf<HtmlBlock>()
    private var builder = AnnotatedString.Builder()
    private var kind = HtmlBlock.Kind.Paragraph
    private var indent = 0
    private var bullet: String? = null
    private var preformatted = 0
    private var lastChar = '\n'
    private val whitespace = Regex("\\s+")

    private fun append(text: String) {
        if (text.isEmpty()) return
        builder.append(text)
        lastChar = text.last()
    }

    fun layout(html: String, baseUrl: String?): List<HtmlBlock> {
        val body = Jsoup.parseBodyFragment(html, baseUrl.orEmpty()).body()
        walkChildren(body)
        flush()
        return blocks.toList()
    }

    private fun flush() {
        val text = builder.toAnnotatedString()
        val trimmed = if (preformatted > 0 || kind == HtmlBlock.Kind.Code) text else text.trimWhitespace()
        if (trimmed.text.isNotBlank()) blocks += HtmlBlock.Text(trimmed, kind, indent, bullet)
        builder = AnnotatedString.Builder()
        bullet = null
    }

    private fun block(newKind: HtmlBlock.Kind, newIndent: Int = indent, newBullet: String? = null, body: () -> Unit) {
        flush()
        val (oldKind, oldIndent) = kind to indent
        kind = newKind
        indent = newIndent
        bullet = newBullet
        body()
        flush()
        kind = oldKind
        indent = oldIndent
    }

    private fun walkChildren(element: Element) {
        for (child in element.childNodes()) walk(child)
    }

    private fun walk(node: Node) {
        when (node) {
            is TextNode -> appendText(if (preformatted > 0) node.wholeText else node.text())
            is Element -> element(node)
        }
    }

    private fun appendText(text: String) {
        if (text.isEmpty()) return
        if (preformatted > 0) {
            append(text)
            return
        }
        // Collapse white space across nodes, as a browser does.
        val endsWithSpace = builder.length == 0 || lastChar == ' ' || lastChar == '\n'
        val normalized = text.replace(whitespace, " ")
        append(if (endsWithSpace) normalized.trimStart() else normalized)
    }

    private fun element(e: Element) {
        when (e.normalName()) {
            "p", "div", "section", "article", "header", "footer", "main", "dl", "dd", "dt", "center" ->
                block(if (kind == HtmlBlock.Kind.Quote) kind else HtmlBlock.Kind.Paragraph) { walkChildren(e) }
            "h1", "h2" -> block(HtmlBlock.Kind.Heading1) { walkChildren(e) }
            "h3" -> block(HtmlBlock.Kind.Heading2) { walkChildren(e) }
            "h4", "h5", "h6" -> block(HtmlBlock.Kind.Heading3) { walkChildren(e) }
            "blockquote" -> block(HtmlBlock.Kind.Quote) { walkChildren(e) }
            "pre" -> {
                preformatted++
                block(HtmlBlock.Kind.Code) { walkChildren(e) }
                preformatted--
            }
            "figcaption", "caption" -> block(HtmlBlock.Kind.Caption) { walkChildren(e) }
            "ul", "ol" -> {
                flush()
                val ordered = e.normalName() == "ol"
                var number = e.attr("start").toIntOrNull() ?: 1
                for (child in e.children()) {
                    if (child.normalName() != "li") continue
                    val mark = if (ordered) "${number++}." else if (indent % 2 == 0) "•" else "◦"
                    block(HtmlBlock.Kind.Paragraph, indent + 1, mark) { walkChildren(child) }
                }
            }
            "li" -> block(HtmlBlock.Kind.Paragraph, indent + 1, "•") { walkChildren(e) }
            "table" -> {
                flush()
                for (row in e.select("tr")) {
                    block(HtmlBlock.Kind.Paragraph) {
                        row.children().forEachIndexed { i, cell ->
                            if (i > 0) append("  ·  ")
                            if (cell.normalName() == "th") {
                                builder.pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                                walkChildren(cell)
                                builder.pop()
                            } else walkChildren(cell)
                        }
                    }
                }
            }
            "hr" -> {
                flush()
                blocks += HtmlBlock.Rule
            }
            "br" -> append("\n")
            "img" -> {
                val src = e.absUrl("src").ifEmpty { e.attr("src") }
                if (src.isNotEmpty() && !src.startsWith("data:")) {
                    flush()
                    blocks += HtmlBlock.Picture(src, e.attr("alt"))
                }
            }
            "a" -> {
                val href = e.absUrl("href").ifEmpty { e.attr("href") }
                if (href.startsWith("http")) {
                    builder.pushLink(LinkAnnotation.Clickable(href, TextLinkStyles(linkStyle)) { onLink(href) })
                    walkChildren(e)
                    builder.pop()
                } else walkChildren(e)
            }
            "b", "strong" -> styled(SpanStyle(fontWeight = FontWeight.Bold), e)
            "i", "em", "cite", "q" -> styled(SpanStyle(fontStyle = FontStyle.Italic), e)
            "u", "ins" -> styled(SpanStyle(textDecoration = TextDecoration.Underline), e)
            "s", "strike", "del" -> styled(SpanStyle(textDecoration = TextDecoration.LineThrough), e)
            "code", "kbd", "samp", "tt" -> styled(SpanStyle(fontFamily = FontFamily.Monospace), e)
            "sub" -> styled(SpanStyle(baselineShift = BaselineShift.Subscript, fontSize = 0.8.em), e)
            "sup" -> styled(SpanStyle(baselineShift = BaselineShift.Superscript, fontSize = 0.8.em), e)
            "small" -> styled(SpanStyle(fontSize = 0.85.em), e)
            "script", "style", "noscript", "iframe", "video", "audio", "object", "svg" -> Unit
            else -> walkChildren(e)
        }
    }

    private fun styled(style: SpanStyle, e: Element) {
        builder.pushStyle(style)
        walkChildren(e)
        builder.pop()
    }

    private fun AnnotatedString.trimWhitespace(): AnnotatedString {
        val start = text.indexOfFirst { !it.isWhitespace() }
        if (start < 0) return AnnotatedString("")
        val end = text.indexOfLast { !it.isWhitespace() } + 1
        return subSequence(start, end)
    }
}

/** Shows the blocks of an article. Pictures load in the background through [images]. */
@Composable
fun HtmlContent(blocks: List<HtmlBlock>, images: ImageLoader, bodyStyle: TextStyle) {
    val colors = MaterialTheme.colorScheme
    val type = MaterialTheme.typography
    Column {
        for (block in blocks) {
            when (block) {
                is HtmlBlock.Text -> {
                    val style = when (block.kind) {
                        HtmlBlock.Kind.Heading1 -> type.titleLarge.copy(fontWeight = FontWeight.SemiBold)
                        HtmlBlock.Kind.Heading2 -> type.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                        HtmlBlock.Kind.Heading3 -> type.titleSmall.copy(fontWeight = FontWeight.SemiBold)
                        HtmlBlock.Kind.Code -> bodyStyle.copy(fontFamily = FontFamily.Monospace, fontSize = bodyStyle.fontSize * 0.85f)
                        HtmlBlock.Kind.Caption -> type.bodySmall.copy(color = colors.onSurfaceVariant)
                        else -> bodyStyle
                    }
                    val top = when (block.kind) {
                        HtmlBlock.Kind.Heading1, HtmlBlock.Kind.Heading2, HtmlBlock.Kind.Heading3 -> 18.dp
                        else -> if (block.bullet != null) 4.dp else 10.dp
                    }
                    val modifier = Modifier.fillMaxWidth().padding(top = top, start = (block.indent * 20).dp)
                    when {
                        block.kind == HtmlBlock.Kind.Quote -> Row(modifier) {
                            Box(Modifier.width(3.dp).heightIn(min = 20.dp).background(colors.outlineVariant))
                            Text(block.text, style = style.copy(color = colors.onSurfaceVariant), modifier = Modifier.padding(start = 14.dp))
                        }
                        block.kind == HtmlBlock.Kind.Code -> Box(
                            modifier.clip(RoundedCornerShape(8.dp)).background(colors.surfaceContainer)
                                .horizontalScroll(rememberScrollState()).padding(12.dp),
                        ) { Text(block.text, style = style, softWrap = false) }
                        block.bullet != null -> Row(modifier) {
                            Text(block.bullet, style = style, modifier = Modifier.widthIn(min = 22.dp))
                            Text(block.text, style = style)
                        }
                        else -> Text(block.text, style = style, modifier = modifier)
                    }
                }
                is HtmlBlock.Picture -> Picture(block, images)
                HtmlBlock.Rule -> HorizontalDivider(Modifier.padding(vertical = 16.dp))
            }
        }
    }
}

@Composable
private fun Picture(block: HtmlBlock.Picture, images: ImageLoader) {
    var bitmap by remember(block.src) { mutableStateOf<ImageBitmap?>(images.cached(block.src)) }
    var failed by remember(block.src) { mutableStateOf(false) }
    LaunchedEffect(block.src) {
        if (bitmap == null) {
            bitmap = images.load(block.src)
            failed = bitmap == null
        }
    }
    val image = bitmap
    when {
        image != null -> {
            Image(
                bitmap = image,
                contentDescription = block.alt.ifBlank { null },
                contentScale = ContentScale.FillWidth,
                modifier = Modifier.padding(vertical = 12.dp).widthIn(max = image.width.dp).fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp)),
            )
        }
        failed && block.alt.isNotBlank() -> Text(
            "[Bild: ${block.alt}]",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 8.dp),
        )
        !failed -> Box(
            Modifier.padding(vertical = 12.dp).fillMaxWidth().heightIn(min = 120.dp).clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp)),
        )
        else -> Unit
    }
}
