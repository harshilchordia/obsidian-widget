package com.obsidianwidget

import com.obsidianwidget.VaultManager.BlockType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownParserTest {

    private fun parse(markdown: String) = MarkdownParser.parse(markdown.lines())

    @Test
    fun headingsAllLevelsAndSetext() {
        val items = parse("# One\n###### Six\nTitle\n===\nSub\n---\n#notatag heading")
        assertEquals(listOf(1, 6, 1, 2), items.take(4).map { it.headingLevel })
        assertTrue(items.take(4).all { it.type == BlockType.HEADING })
        assertEquals("Title", items[2].text)
        assertEquals(BlockType.PARAGRAPH, items[4].type)
    }

    @Test
    fun paragraphJoinsSoftLineBreaks() {
        val items = parse("line one\nline two\n\nnext")
        assertEquals(2, items.size)
        assertEquals("line one\nline two", items[0].text)
        assertEquals(0, items[0].lineIndex)
        assertEquals(3, items[1].lineIndex)
    }

    @Test
    fun horizontalRuleVariants() {
        val items = parse("---\n\n***\n\n___\n\n- - -")
        assertTrue(items.all { it.type == BlockType.HORIZONTAL_RULE })
        assertEquals(4, items.size)
    }

    @Test
    fun tasksWithAnyMarkerAndStatus() {
        val items = parse("- [ ] open\n* [x] done\n1. [/] partial\n\t- [-] cancelled\n- [ ]")
        assertEquals(5, items.size)
        assertTrue(items.all { it.type == BlockType.TASK })
        assertEquals(listOf(false, true, true, true, false), items.map { it.isChecked })
        assertEquals(1, items[3].indentLevel)
        assertEquals("", items[4].text)
    }

    @Test
    fun nestedListsWithTwoAndFourSpaceIndents() {
        val items = parse("- a\n    - b\n        - c\n- d\n  1. e\n  2. f")
        assertEquals(listOf(0, 1, 2, 0, 1, 1), items.map { it.indentLevel })
        assertEquals(listOf("•", "•", "•", "•", "1.", "2."), items.map { it.listMarker })
    }

    @Test
    fun orderedListNumbersFollowStart() {
        val items = parse("3. a\n3. b\n7) c")
        assertEquals(listOf("3.", "4.", "5)"), items.map { it.listMarker })
    }

    @Test
    fun listContinuationLines() {
        val items = parse("- item\n  continued\nlazy\n\nparagraph")
        assertEquals(2, items.size)
        assertEquals("item\ncontinued\nlazy", items[0].text)
        assertEquals(BlockType.PARAGRAPH, items[1].type)
    }

    @Test
    fun quoteDirectlyAfterListIsNotSwallowed() {
        val items = parse("- item\n> quote")
        assertEquals(listOf("item", "quote"), items.map { it.text })
        assertEquals(1, items[1].quoteDepth)
    }

    @Test
    fun fencedCodeKeepsContentVerbatim() {
        val items = parse("```kotlin\nval x = 1\n# not a heading\n- [ ] not a task\n```\nafter")
        assertEquals(2, items.size)
        assertEquals(BlockType.CODE, items[0].type)
        assertEquals("kotlin", items[0].codeLanguage)
        assertEquals("val x = 1\n# not a heading\n- [ ] not a task", items[0].text)
        assertEquals("after", items[1].text)
    }

    @Test
    fun fencedCodeKeepsQuoteMarkers() {
        assertEquals("> not a quote\n>> neither", parse("```\n> not a quote\n>> neither\n```")[0].text)
        val quoted = parse("> ```\n> > inner\n> ```")
        assertEquals(1, quoted.size)
        assertEquals("> inner", quoted[0].text)
    }

    @Test
    fun tildeFenceAndUnclosedFence() {
        val items = parse("~~~\ncode\n~~~\n```\nopen")
        assertEquals(listOf("code", "open"), items.map { it.text })
    }

    @Test
    fun indentedCodeOnlyOutsideLists() {
        assertEquals(BlockType.CODE, parse("text\n\n    code\n    more")[1].type)
        assertEquals(BlockType.BULLET, parse("- a\n\n    - b")[1].type)
    }

    @Test
    fun mathBlocks() {
        val items = parse("\$\$\na^2 + b^2\n\$\$\n\$\$x\$\$")
        assertEquals(listOf(BlockType.MATH, BlockType.MATH), items.map { it.type })
        assertEquals("a^2 + b^2", items[0].text)
        assertEquals("x", items[1].text)
    }

    @Test
    fun blockquotesAndNesting() {
        val items = parse("> quote\n> continues\n>> nested\n\nplain")
        assertEquals(1, items[0].quoteDepth)
        assertEquals("quote\ncontinues", items[0].text)
        assertEquals(2, items[1].quoteDepth)
        assertEquals(0, items[2].quoteDepth)
        assertNull(items[0].calloutType)
    }

    @Test
    fun calloutsWithContentAndTasks() {
        val items = parse("> [!warning] Careful\n> body text\n> - [ ] task inside\n\nafter")
        assertEquals(BlockType.CALLOUT_TITLE, items[0].type)
        assertEquals("Careful", items[0].text)
        assertEquals("warning", items[0].calloutType)
        assertEquals("warning", items[1].calloutType)
        assertEquals(BlockType.TASK, items[2].type)
        assertEquals(2, items[2].lineIndex)
        assertNull(items[3].calloutType)
    }

    @Test
    fun calloutDefaultTitleAndFolding() {
        val items = parse("> [!tip]-\n> hidden\n> - [ ] hidden task\n\n> [!info]+ Open\n> shown")
        assertEquals(listOf("Tip", "Open", "shown"), items.map { it.text })
        assertEquals("-", items[0].calloutFold)
        assertEquals("+", items[1].calloutFold)
    }

    @Test
    fun tables() {
        val items = parse("| Name | Qty |\n| :--- | ---: |\n| Apple | 3 |\n| Pear |\n\nafter")
        assertEquals(4, items.size)
        assertTrue(items[0].isTableHeader)
        assertEquals(listOf("Name", "Qty"), items[0].tableCells)
        assertEquals(listOf('l', 'r'), items[0].tableAlignments)
        assertEquals(listOf("Pear", ""), items[2].tableCells)
        assertEquals("after", items[3].text)
    }

    @Test
    fun tableCellsHonourEscapesCodeAndWikilinks() {
        assertEquals(
            listOf("a|b", "`x|y`", "[[Note|Alias]]"),
            MarkdownParser.splitTableRow("| a\\|b | `x|y` | [[Note|Alias]] |")
        )
    }

    @Test
    fun commentsAreRemovedAcrossLines() {
        val items = parse("visible %%hidden%% text\n%%\nsecret\n%%\nafter")
        assertEquals(1, items.size)
        assertEquals("visible  text\nafter", items[0].text)
    }

    @Test
    fun blockIdsAreStripped() {
        val items = parse("Paragraph ^abc-123\n\n- item ^id1")
        assertEquals("Paragraph", items[0].text)
        assertEquals("item", items[1].text)
    }

    @Test
    fun footnoteDefinitionsMoveToEnd() {
        val items = parse("[^1]: The note\n\nText[^1]")
        assertEquals(BlockType.PARAGRAPH, items[0].type)
        assertEquals(BlockType.HORIZONTAL_RULE, items[1].type)
        assertEquals(BlockType.FOOTNOTE, items[2].type)
        assertEquals("1", items[2].listMarker)
    }

    @Test
    fun firstLineSkipsFrontmatter() {
        val lines = "---\ntags: x\n---\n# Body".lines()
        val items = MarkdownParser.parse(lines, 3)
        assertEquals(1, items.size)
        assertEquals(3, items[0].lineIndex)
    }

    @Test
    fun taskLineRegexToggleTargets() {
        val regex = MarkdownParser.TASK_LINE
        assertEquals(" ", regex.matchEntire("- [ ] a")!!.groupValues[2])
        assertEquals("x", regex.matchEntire("> > 1. [x] nested")!!.groupValues[2])
        assertFalse(regex.matches("- plain"))
    }
}
