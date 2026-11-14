package com.obsidianwidget

import com.obsidianwidget.VaultManager.BlockType
import com.obsidianwidget.VaultManager.ChecklistItem

/**
 * Block-level Markdown parser covering the syntax Obsidian supports out of the box:
 * headings (ATX + setext), paragraphs with soft line breaks, bullet / ordered / task lists,
 * blockquotes, callouts (incl. foldable), fenced + indented code, math blocks, tables,
 * horizontal rules, footnotes, comments and block IDs.
 *
 * Each rendered row keeps the index of its source line so tasks can be toggled in place.
 * This file is free of Android dependencies so it can be unit tested on the JVM.
 */
object MarkdownParser {

    private val FENCE_OPEN = Regex("""^(\s*)(`{3,}|~{3,})\s*([^\s`]*).*$""")
    private val ATX_HEADING = Regex("""^ {0,3}(#{1,6})(?:[ \t]+(.*?))?(?:[ \t]+#+)?[ \t]*$""")
    private val SETEXT_UNDERLINE = Regex("""^ {0,3}(=+|-+)[ \t]*$""")
    private val HORIZONTAL_RULE = Regex("""^ {0,3}([-*_])(?:[ \t]*\1){2,}[ \t]*$""")
    private val TASK = Regex("""^([ \t]*)([-*+]|\d{1,9}[.)])[ \t]+\[(.)\](?:[ \t]+(.*))?$""")
    private val BULLET = Regex("""^([ \t]*)([-*+])(?:[ \t]+(.*))?$""")
    private val ORDERED = Regex("""^([ \t]*)(\d{1,9})([.)])(?:[ \t]+(.*))?$""")
    private val FOOTNOTE_DEF = Regex("""^\[\^([^\]]+)\]:[ \t]*(.*)$""")
    private val CALLOUT = Regex("""^\[!([^\]]+)\]([+-]?)[ \t]*(.*)$""")
    private val TABLE_DELIMITER = Regex("""^\s*\|?\s*:?-+:?\s*(\|\s*:?-+:?\s*)*\|?\s*$""")
    private val BLOCK_ID = Regex("""\s+\^[A-Za-z0-9-]+$""")

    /** Matches any task line, optionally nested in blockquotes / callouts. Group 2 is the status char. */
    val TASK_LINE = Regex("""^((?:[ \t]*>)*[ \t]*(?:[-*+]|\d{1,9}[.)])[ \t]+\[)(.)(\].*)$""")

    private data class QuoteLine(val depth: Int, val content: String)

    private data class Callout(val depth: Int, val type: String, val folded: Boolean)

    /**
     * Parse [lines] starting at [firstLine] (e.g. after the frontmatter) into renderable rows.
     */
    fun parse(lines: List<String>, firstLine: Int = 0): List<ChecklistItem> =
        Parser(stripComments(lines, firstLine), firstLine).run()

    /**
     * Remove `%% comments %%` that may span several lines. Lines fully inside a comment become
     * null so they neither render nor break up surrounding paragraphs.
     */
    private fun stripComments(lines: List<String>, firstLine: Int): List<String?> {
        val result = ArrayList<String?>(lines.size)
        var inComment = false
        var fence: String? = null
        lines.forEachIndexed { index, line ->
            if (index < firstLine) {
                result.add(line)
                return@forEachIndexed
            }
            if (!inComment) {
                val content = stripQuotes(line).content
                val openFence = fence
                if (openFence != null) {
                    if (isFenceClose(content, openFence)) fence = null
                    result.add(line)
                    return@forEachIndexed
                }
                FENCE_OPEN.matchEntire(content)?.let {
                    fence = it.groupValues[2]
                    result.add(line)
                    return@forEachIndexed
                }
            }
            if (!inComment && !line.contains("%%")) {
                result.add(line)
                return@forEachIndexed
            }
            val visible = StringBuilder()
            var i = 0
            var sawVisible = false
            while (i < line.length) {
                if (line.startsWith("%%", i)) {
                    inComment = !inComment
                    i += 2
                    continue
                }
                if (!inComment) {
                    visible.append(line[i])
                    sawVisible = true
                }
                i++
            }
            result.add(if (sawVisible && visible.isNotBlank()) visible.toString().trimEnd() else null)
        }
        return result
    }

    private fun isFenceClose(content: String, marker: String): Boolean {
        val trimmed = content.trim()
        return trimmed.startsWith(marker) && trimmed.all { it == marker[0] }
    }

    /** Removes up to [maxDepth] leading `>` markers and reports how many were removed. */
    private fun stripQuotes(line: String, maxDepth: Int = Int.MAX_VALUE): QuoteLine {
        var depth = 0
        var i = 0
        while (depth < maxDepth) {
            var j = i
            while (j < line.length && j - i < 4 && (line[j] == ' ' || line[j] == '\t')) j++
            if (j < line.length && line[j] == '>') {
                depth++
                i = j + 1
                if (i < line.length && line[i] == ' ') i++
            } else {
                break
            }
        }
        return QuoteLine(depth, line.substring(i))
    }

    private fun indentWidth(indent: String): Int = indent.fold(0) { width, c -> width + if (c == '\t') 4 else 1 }

    private fun stripBlockId(text: String): String = BLOCK_ID.replace(text, "")

    fun splitTableRow(row: String): List<String> {
        var s = row.trim()
        if (s.startsWith("|")) s = s.substring(1)
        if (s.endsWith("|") && !s.endsWith("\\|")) s = s.substring(0, s.length - 1)
        val cells = mutableListOf<String>()
        val cell = StringBuilder()
        var i = 0
        var inCode = false
        var inWiki = false
        while (i < s.length) {
            val c = s[i]
            when {
                c == '\\' && i + 1 < s.length && s[i + 1] == '|' -> {
                    cell.append('|'); i += 2; continue
                }
                c == '`' -> inCode = !inCode
                !inCode && s.startsWith("[[", i) -> inWiki = true
                !inCode && s.startsWith("]]", i) -> inWiki = false
                c == '|' && !inCode && !inWiki -> {
                    cells.add(cell.toString().trim()); cell.clear(); i++; continue
                }
            }
            cell.append(c)
            i++
        }
        cells.add(cell.toString().trim())
        return cells
    }

    private class Parser(private val lines: List<String?>, private val firstLine: Int) {
        private val items = mutableListOf<ChecklistItem>()
        private val footnotes = mutableListOf<ChecklistItem>()
        private val callouts = mutableListOf<Callout>()

        // Open paragraph or list item that still accepts continuation lines. A block hidden
        // inside a folded callout stays open (openIndex = -1) so its continuation is hidden too.
        private var openIndex = -1
        private var openDepth = -1
        private var previousBlank = true

        // List nesting state
        private val listIndents = mutableListOf<Int>()
        private val orderedCounters = mutableMapOf<Int, Int>()
        private var inList = false

        fun run(): List<ChecklistItem> {
            var i = firstLine
            while (i < lines.size) {
                i = parseLine(i)
            }
            if (footnotes.isNotEmpty()) {
                items.add(ChecklistItem(lineIndex = -1, text = "", type = BlockType.HORIZONTAL_RULE))
                items.addAll(footnotes)
            }
            return items
        }

        private fun currentCallout(): Callout? = callouts.lastOrNull()

        private fun isHidden(): Boolean = callouts.any { it.folded }

        private fun add(item: ChecklistItem, depth: Int, calloutType: String? = currentCallout()?.type) {
            if (!isHidden()) items.add(item.copy(quoteDepth = depth, calloutType = calloutType))
        }

        /** Adds a block that following lines may continue (paragraphs and list items). */
        private fun addOpen(item: ChecklistItem, depth: Int) {
            add(item, depth)
            openIndex = if (isHidden()) -1 else items.lastIndex
            openDepth = depth
        }

        private fun closeParagraph() {
            openIndex = -1
            openDepth = -1
        }

        private fun addListItem(index: Int, depth: Int, indent: String, item: (level: Int) -> ChecklistItem) {
            closeParagraph()
            val level = listLevel(indentWidth(indent))
            inList = true
            addOpen(item(level).copy(lineIndex = index), depth)
            previousBlank = false
        }

        private fun endList() {
            inList = false
            listIndents.clear()
            orderedCounters.clear()
        }

        private fun listLevel(indent: Int): Int {
            while (listIndents.isNotEmpty() && listIndents.last() > indent) listIndents.removeAt(listIndents.size - 1)
            if (listIndents.isEmpty() || listIndents.last() < indent) listIndents.add(indent)
            val level = listIndents.size - 1
            orderedCounters.keys.filter { it > level }.forEach { orderedCounters.remove(it) }
            return level
        }

        private fun appendToOpen(text: String) {
            if (openIndex < 0) return
            val open = items[openIndex]
            items[openIndex] = open.copy(text = open.text + "\n" + stripBlockId(text.trim()))
        }

        /** Parses the block starting at line [index] and returns the next line to look at. */
        private fun parseLine(index: Int): Int {
            val raw = lines[index] ?: return index + 1
            val quote = stripQuotes(raw)
            val depth = quote.depth
            val content = quote.content

            // Leaving a quote (or entering a shallower one) closes nested callouts.
            while (callouts.isNotEmpty() && callouts.last().depth > depth) callouts.removeAt(callouts.size - 1)

            if (content.isBlank()) {
                if (depth == 0) callouts.clear()
                closeParagraph()
                previousBlank = true
                return index + 1
            }

            if (openDepth >= 0 && openDepth != depth) closeParagraph()

            // Callout header: first line of a quote level starting with [!type]
            if (depth > 0) {
                val callout = CALLOUT.matchEntire(content.trim())
                if (callout != null && callouts.none { it.depth == depth }) {
                    closeParagraph(); endList()
                    val type = callout.groupValues[1].trim().lowercase()
                    val fold = callout.groupValues[2]
                    val title = callout.groupValues[3].trim().ifEmpty { type.replaceFirstChar { it.uppercase() } }
                    // The title itself is drawn inside its own callout box.
                    add(
                        ChecklistItem(
                            lineIndex = index,
                            text = title,
                            type = BlockType.CALLOUT_TITLE,
                            calloutFold = fold
                        ),
                        depth,
                        calloutType = type
                    )
                    callouts.add(Callout(depth, type, fold == "-"))
                    previousBlank = false
                    return index + 1
                }
            }

            // Fenced code block
            FENCE_OPEN.matchEntire(content)?.let { fence ->
                return parseFence(index, depth, fence)
            }

            // Math block
            if (content.trim().startsWith("$$")) {
                return parseMath(index, depth, content)
            }

            // Indented code block (only outside of lists and paragraphs)
            if (previousBlank && !inList && openIndex < 0 && (content.startsWith("    ") || content.startsWith("\t"))) {
                return parseIndentedCode(index, depth)
            }

            // Setext heading underline
            if (openIndex >= 0 && openDepth == depth && items[openIndex].type == BlockType.PARAGRAPH) {
                SETEXT_UNDERLINE.matchEntire(content)?.let { underline ->
                    val level = if (underline.groupValues[1].startsWith("=")) 1 else 2
                    items[openIndex] = items[openIndex].copy(type = BlockType.HEADING, headingLevel = level)
                    closeParagraph()
                    previousBlank = false
                    return index + 1
                }
            }

            if (HORIZONTAL_RULE.matches(content)) {
                closeParagraph(); endList()
                add(ChecklistItem(lineIndex = index, text = "", type = BlockType.HORIZONTAL_RULE), depth)
                previousBlank = false
                return index + 1
            }

            ATX_HEADING.matchEntire(content)?.let { heading ->
                closeParagraph(); endList()
                add(
                    ChecklistItem(
                        lineIndex = index,
                        text = stripBlockId(heading.groupValues[2].trim()),
                        type = BlockType.HEADING,
                        headingLevel = heading.groupValues[1].length
                    ),
                    depth
                )
                previousBlank = false
                return index + 1
            }

            // Table: header row followed by a delimiter row
            if (content.contains('|') && index + 1 < lines.size) {
                val next = lines[index + 1]?.let { stripQuotes(it) }
                if (next != null && next.depth == depth && next.content.contains('|') &&
                    TABLE_DELIMITER.matches(next.content)
                ) {
                    val header = splitTableRow(content)
                    val delimiter = splitTableRow(next.content)
                    if (header.size == delimiter.size) {
                        return parseTable(index, depth, header, delimiter)
                    }
                }
            }

            FOOTNOTE_DEF.matchEntire(content.trim())?.let { footnote ->
                closeParagraph(); endList()
                footnotes.add(
                    ChecklistItem(
                        lineIndex = index,
                        text = footnote.groupValues[2],
                        type = BlockType.FOOTNOTE,
                        listMarker = footnote.groupValues[1]
                    )
                )
                previousBlank = false
                return index + 1
            }

            TASK.matchEntire(content)?.let { task ->
                addListItem(index, depth, task.groupValues[1]) { level ->
                    ChecklistItem(
                        lineIndex = index,
                        text = stripBlockId(task.groupValues[4].trim()),
                        type = BlockType.TASK,
                        isChecked = task.groupValues[3] != " ",
                        indentLevel = level
                    )
                }
                return index + 1
            }

            BULLET.matchEntire(content)?.let { bullet ->
                addListItem(index, depth, bullet.groupValues[1]) { level ->
                    orderedCounters.remove(level)
                    ChecklistItem(
                        lineIndex = index,
                        text = stripBlockId(bullet.groupValues[3].trim()),
                        type = BlockType.BULLET,
                        indentLevel = level,
                        listMarker = "•"
                    )
                }
                return index + 1
            }

            ORDERED.matchEntire(content)?.let { ordered ->
                addListItem(index, depth, ordered.groupValues[1]) { level ->
                    // Like Obsidian, numbering continues from the first item's number.
                    val number = orderedCounters[level]?.plus(1) ?: ordered.groupValues[2].toInt()
                    orderedCounters[level] = number
                    ChecklistItem(
                        lineIndex = index,
                        text = stripBlockId(ordered.groupValues[4].trim()),
                        type = BlockType.ORDERED,
                        indentLevel = level,
                        listMarker = "$number${ordered.groupValues[3]}"
                    )
                }
                return index + 1
            }

            // Continuation line: directly after a paragraph / list item, or indented after a
            // blank line inside a list item
            val continuesListItem = inList && previousBlank && content.first().isWhitespace() &&
                items.lastOrNull()?.type?.isListItem == true
            if (openDepth == depth || continuesListItem) {
                if (continuesListItem && openDepth != depth) {
                    openIndex = items.lastIndex
                    openDepth = depth
                }
                appendToOpen(content)
                previousBlank = false
                return index + 1
            }

            endList()
            addOpen(
                ChecklistItem(lineIndex = index, text = stripBlockId(content.trim()), type = BlockType.PARAGRAPH),
                depth
            )
            previousBlank = false
            return index + 1
        }

        /** Line content inside a block at quote [depth], or null if the line leaves that quote. */
        private fun contentAt(index: Int, depth: Int): String? {
            val raw = lines[index] ?: return ""
            val quote = stripQuotes(raw, depth)
            return if (quote.depth < depth) null else quote.content
        }

        private fun parseFence(index: Int, depth: Int, fence: MatchResult): Int {
            closeParagraph(); endList()
            val indent = indentWidth(fence.groupValues[1])
            val marker = fence.groupValues[2]
            val language = fence.groupValues[3]
            val body = mutableListOf<String>()
            var i = index + 1
            while (i < lines.size) {
                val content = contentAt(i, depth) ?: break
                if (isFenceClose(content, marker)) {
                    i++
                    break
                }
                body.add(removeIndent(content, indent))
                i++
            }
            add(
                ChecklistItem(
                    lineIndex = index,
                    text = body.joinToString("\n"),
                    type = BlockType.CODE,
                    codeLanguage = language
                ),
                depth
            )
            previousBlank = false
            return i
        }

        private fun removeIndent(line: String, width: Int): String {
            var removed = 0
            var i = 0
            while (i < line.length && removed < width && (line[i] == ' ' || line[i] == '\t')) {
                removed += if (line[i] == '\t') 4 else 1
                i++
            }
            return line.substring(i)
        }

        private fun parseMath(index: Int, depth: Int, first: String): Int {
            closeParagraph(); endList()
            val trimmed = first.trim().removePrefix("$$")
            val body = mutableListOf<String>()
            var i = index + 1
            if (trimmed.trimEnd().endsWith("$$")) {
                body.add(trimmed.trimEnd().removeSuffix("$$"))
            } else {
                if (trimmed.isNotBlank()) body.add(trimmed)
                while (i < lines.size) {
                    val content = contentAt(i, depth) ?: break
                    i++
                    val t = content.trimEnd()
                    if (t.endsWith("$$")) {
                        t.removeSuffix("$$").takeIf { it.isNotBlank() }?.let(body::add)
                        break
                    }
                    body.add(content)
                }
            }
            add(
                ChecklistItem(lineIndex = index, text = body.joinToString("\n").trim(), type = BlockType.MATH),
                depth
            )
            previousBlank = false
            return i
        }

        private fun parseIndentedCode(index: Int, depth: Int): Int {
            closeParagraph()
            val body = mutableListOf<String>()
            var i = index
            var lastContent = index
            while (i < lines.size) {
                val content = contentAt(i, depth) ?: break
                if (content.isBlank()) {
                    body.add("")
                } else if (content.startsWith("    ") || content.startsWith("\t")) {
                    body.add(removeIndent(content, 4))
                    lastContent = i
                } else {
                    break
                }
                i++
            }
            val trimmedBody = body.take(lastContent - index + 1)
            add(
                ChecklistItem(lineIndex = index, text = trimmedBody.joinToString("\n"), type = BlockType.CODE),
                depth
            )
            previousBlank = false
            return lastContent + 1
        }

        private fun parseTable(index: Int, depth: Int, header: List<String>, delimiter: List<String>): Int {
            closeParagraph(); endList()
            val alignments = delimiter.map {
                val left = it.startsWith(":")
                val right = it.endsWith(":")
                when {
                    left && right -> 'c'
                    right -> 'r'
                    else -> 'l'
                }
            }
            add(
                ChecklistItem(
                    lineIndex = index,
                    text = header.joinToString(" | "),
                    type = BlockType.TABLE_ROW,
                    tableCells = header,
                    tableAlignments = alignments,
                    isTableHeader = true
                ),
                depth
            )
            var i = index + 2
            while (i < lines.size) {
                val raw = lines[i] ?: break
                val quote = stripQuotes(raw)
                if (quote.depth != depth || quote.content.isBlank() || !quote.content.contains('|')) break
                val cells = splitTableRow(quote.content)
                val normalized = List(header.size) { cells.getOrElse(it) { "" } }
                add(
                    ChecklistItem(
                        lineIndex = i,
                        text = normalized.joinToString(" | "),
                        type = BlockType.TABLE_ROW,
                        tableCells = normalized,
                        tableAlignments = alignments
                    ),
                    depth
                )
                i++
            }
            previousBlank = false
            return i
        }
    }
}
