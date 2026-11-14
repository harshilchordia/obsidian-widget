package com.obsidianwidget

import com.obsidianwidget.MarkdownInline.Style
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MarkdownInlineTest {

    private fun render(markdown: String): Pair<String, List<Triple<String, Style, String?>>> {
        val result = MarkdownInline.parse(markdown)
        return result.text to result.spans.map {
            Triple(result.text.substring(it.start, it.end), it.style, it.target)
        }
    }

    private fun styles(markdown: String) = render(markdown).second.map { it.first to it.second }

    @Test
    fun emphasis() {
        assertEquals("bold" to Style.BOLD, styles("**bold**").single())
        assertEquals("bold" to Style.BOLD, styles("__bold__").single())
        assertEquals("it" to Style.ITALIC, styles("*it*").single())
        assertEquals("it" to Style.ITALIC, styles("_it_").single())
        assertEquals(setOf("both" to Style.BOLD, "both" to Style.ITALIC), styles("***both***").toSet())
    }

    @Test
    fun nestedEmphasis() {
        val (text, spans) = render("*a **b** c*")
        assertEquals("a b c", text)
        assertEquals(setOf("b" to Style.BOLD, "a b c" to Style.ITALIC), spans.map { it.first to it.second }.toSet())

        val (text2, spans2) = render("**bold *it* more**")
        assertEquals("bold it more", text2)
        assertEquals(setOf("it" to Style.ITALIC, "bold it more" to Style.BOLD), spans2.map { it.first to it.second }.toSet())
    }

    @Test
    fun noEmphasisForIntrawordUnderscoreOrSpaces() {
        assertEquals("snake_case_name" to emptyList<Any>(), render("snake_case_name").let { it.first to it.second })
        assertEquals("a * b * c", render("a * b * c").first)
        assertEquals("a == b", render("a == b").first)
    }

    @Test
    fun strikeHighlightCode() {
        assertEquals("gone" to Style.STRIKE, styles("~~gone~~").single())
        assertEquals("mark" to Style.HIGHLIGHT, styles("==mark==").single())
        val (text, spans) = render("use `**raw**` here")
        assertEquals("use **raw** here", text)
        assertEquals("**raw**" to Style.CODE, spans.map { it.first to it.second }.single())
        assertEquals("a ` b", render("``a ` b``").first)
    }

    @Test
    fun links() {
        val (text, spans) = render("see [Docs](https://example.com \"title\") now")
        assertEquals("see Docs now", text)
        assertEquals(Triple("Docs", Style.LINK, "https://example.com"), spans.single())

        val bare = render("go to https://obsidian.md.")
        assertEquals("go to https://obsidian.md.", bare.first)
        assertEquals("https://obsidian.md", bare.second.single().third)

        assertEquals("https://a.b", render("<https://a.b>").second.single().third)
        assertEquals("https://rewe.de/angebote", MarkdownInline.parse("buy at rewe.de/angebote").firstUrl)
        assertNull(MarkdownInline.parse("e.g. stuff").firstUrl)
    }

    @Test
    fun internalLinksAndEmbeds() {
        assertEquals(Triple("Note", Style.INTERNAL_LINK, "Note"), render("[[Note]]").second.single())
        assertEquals(Triple("Alias", Style.INTERNAL_LINK, "folder/Note"), render("[[folder/Note|Alias]]").second.single())
        assertEquals("Note > Heading", render("[[Note#Heading]]").first)
        assertEquals("🖼 pic.png", render("![[pic.png|200]]").first)
        assertEquals("↪ Other", render("![[Other]]").first)
        assertEquals("🖼 alt", render("![alt](img/x.png)").first)
        assertEquals(Triple("Local", Style.INTERNAL_LINK, "My Note.md"), render("[Local](My%20Note.md)").second.single())
        assertEquals("Note", MarkdownInline.parse("[[Note]]").firstInternalLink)
    }

    @Test
    fun tags() {
        val (text, spans) = render("todo #work/project and #123 and a#b")
        assertEquals("todo #work/project and #123 and a#b", text)
        assertEquals(Triple("#work/project", Style.TAG, "work/project"), spans.single())
    }

    @Test
    fun footnotesMathCommentsEscapes() {
        assertEquals("Text[1]", render("Text[^1]").first)
        assertEquals(Style.FOOTNOTE, render("Text[^1]").second.single().second)
        assertEquals("x[inline]", render("x^[inline]").first)
        assertEquals("E = mc^2" to Style.MATH, styles("\$E = mc^2\$").single())
        assertEquals("costs \$5 and \$10", render("costs \$5 and \$10").first)
        assertEquals("a  b", render("a %%hidden%% b").first)
        assertEquals("*not italic* [x]", render("\\*not italic\\* \\[x\\]").first)
    }

    @Test
    fun inlineHtmlAndEntities() {
        assertEquals(setOf("u" to Style.UNDERLINE, "b" to Style.BOLD, "2" to Style.SUPERSCRIPT),
            styles("<u>u</u> <b>b</b> x<sup>2</sup>").toSet())
        assertEquals("a\nb", render("a<br>b").first)
        assertEquals("plain", render("<span style=\"color:red\">plain</span>").first)
        assertEquals("a & b < c —", render("a &amp; b &lt; c &mdash;").first)
        assertEquals("1 < 2", render("1 < 2").first)
    }

    private fun colors(markdown: String) =
        MarkdownInline.parse(markdown).let { result ->
            result.spans.filter { it.color != null }
                .map { Triple(result.text.substring(it.start, it.end), it.style, it.color) }
        }

    @Test
    fun cssColors() {
        assertEquals(0xFFFF0000.toInt(), MarkdownInline.parseCssColor("red"))
        assertEquals(0xFFE74C3C.toInt(), MarkdownInline.parseCssColor("#e74c3c"))
        assertEquals(0xFFFF0000.toInt(), MarkdownInline.parseCssColor("#f00"))
        assertEquals(0xA6FFB86C.toInt(), MarkdownInline.parseCssColor("#FFB86CA6"))
        assertEquals(0x80FF0000.toInt(), MarkdownInline.parseCssColor("rgba(255, 0, 0, 0.5)"))
        assertEquals(0x80FF0000.toInt(), MarkdownInline.parseCssColor("rgb(255 0 0 / 50%)"))
        assertNull(MarkdownInline.parseCssColor("var(--text-accent)"))
        assertNull(MarkdownInline.parseCssColor("#12"))
    }

    @Test
    fun coloredSpanAndFont() {
        assertEquals(
            listOf(Triple("red", Style.COLOR, 0xFFFF0000.toInt())),
            colors("<span style=\"color: red\">red</span>")
        )
        assertEquals(
            listOf(Triple("x", Style.COLOR, 0xFFE74C3C.toInt())),
            colors("<font color=\"#e74c3c\">x</font>")
        )
        assertEquals(
            listOf(Triple("bg", Style.BACKGROUND, 0xFF00FF00.toInt())),
            colors("<span style='background-color:#0f0'>bg</span>")
        )
    }

    @Test
    fun highlightrMarks() {
        val inline = MarkdownInline.parse("<mark style=\"background: #FFB86CA6;\">hi</mark>")
        assertEquals(Style.BACKGROUND, inline.spans.single().style)
        assertEquals(0xA6FFB86C.toInt(), inline.spans.single().color)

        val byClass = MarkdownInline.parse("<mark class=\"hltr-red\">hi</mark>")
        assertEquals(0x66FF0000, byClass.spans.single().color)

        assertEquals(Style.HIGHLIGHT, MarkdownInline.parse("<mark>plain</mark>").spans.single().style)
    }

    @Test
    fun combinedStyleAttributeAndNesting() {
        val styles = styles("<span style=\"color:#00f; background:yellow; font-weight:700; text-decoration:underline\">all</span>")
        assertEquals(
            setOf("all" to Style.COLOR, "all" to Style.BACKGROUND, "all" to Style.BOLD, "all" to Style.UNDERLINE),
            styles.toSet()
        )
        val nested = colors("<span style=\"color:red\">a <span style=\"color:blue\">b</span> c</span>")
        assertEquals(
            setOf(Triple("b", Style.COLOR, 0xFF0000FF.toInt()), Triple("a b c", Style.COLOR, 0xFFFF0000.toInt())),
            nested.toSet()
        )
        assertEquals("unknown", render("<span style=\"color: var(--text-accent)\">unknown</span>").first)
        assertEquals(emptyList<Any>(), colors("<span style=\"color: var(--text-accent)\">unknown</span>"))
    }
}
