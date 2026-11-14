package com.obsidianwidget

import java.net.URLDecoder

/**
 * Inline Markdown parser for the Obsidian flavour: emphasis, strikethrough, highlights,
 * inline code, math, links, wikilinks, embeds, tags, footnotes, comments, escapes and
 * the inline HTML tags Obsidian renders. Produces plain text plus style ranges so the
 * Android layer only has to map them to spans (and this stays unit testable).
 */
object MarkdownInline {

    enum class Style {
        BOLD, ITALIC, STRIKE, HIGHLIGHT, CODE, MATH, LINK, INTERNAL_LINK, EMBED, TAG,
        FOOTNOTE, UNDERLINE, SUPERSCRIPT, SUBSCRIPT
    }

    data class Span(val start: Int, val end: Int, val style: Style, val target: String? = null)

    data class Result(val text: String, val spans: List<Span>) {
        /** First external URL, used to make a row tappable. */
        val firstUrl: String? get() = spans.firstOrNull { it.style == Style.LINK }?.target
        /** First internal link target ([[wikilink]]), used to open the note in Obsidian. */
        val firstInternalLink: String? get() =
            spans.firstOrNull { it.style == Style.INTERNAL_LINK || it.style == Style.EMBED }?.target
    }

    private const val ESCAPABLE = "\\`*_{}[]()#+-.!|~=<>\$%^&\"'"
    private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "bmp", "svg", "webp", "avif")
    private val TAG = Regex("""#[\p{L}\p{N}_/\-]+""")
    private val URL = Regex("""(?:https?|obsidian|mailto|file)://[^\s<>]+|mailto:[^\s<>]+""")
    // Bare domains such as "example.com/path" (kept from the previous widget renderer)
    private val BARE_DOMAIN = Regex("""[a-zA-Z0-9](?:[a-zA-Z0-9-]*[a-zA-Z0-9])?(?:\.[a-zA-Z]{2,})+(?:/[^\s<>"']*)?""")
    private val LINK_TITLE = Regex("""\s+["'(].*$""")
    private val HREF = Regex("""href\s*=\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE)
    private val HTML_TAG = Regex("""</?([A-Za-z][A-Za-z0-9-]*)(?:\s[^<>]*)?/?>""")
    private val ENTITY = Regex("""&(#[0-9]+|#[xX][0-9a-fA-F]+|[A-Za-z]+);""")
    private val NAMED_ENTITIES = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to "\u00A0",
        "ndash" to "–", "mdash" to "—", "hellip" to "…", "copy" to "©", "reg" to "®", "trade" to "™",
        "larr" to "←", "rarr" to "→", "uarr" to "↑", "darr" to "↓", "times" to "×", "deg" to "°"
    )
    private val HTML_STYLES = mapOf(
        "b" to listOf(Style.BOLD), "strong" to listOf(Style.BOLD),
        "i" to listOf(Style.ITALIC), "em" to listOf(Style.ITALIC), "cite" to listOf(Style.ITALIC),
        "u" to listOf(Style.UNDERLINE), "ins" to listOf(Style.UNDERLINE),
        "s" to listOf(Style.STRIKE), "del" to listOf(Style.STRIKE), "strike" to listOf(Style.STRIKE),
        "mark" to listOf(Style.HIGHLIGHT),
        "code" to listOf(Style.CODE), "kbd" to listOf(Style.CODE), "samp" to listOf(Style.CODE),
        "sup" to listOf(Style.SUPERSCRIPT), "sub" to listOf(Style.SUBSCRIPT)
    )

    fun parse(source: String): Result {
        val builder = Builder()
        builder.parse(decodeEntities(source))
        return Result(builder.out.toString(), builder.spans)
    }

    private fun decodeEntities(s: String): String {
        if (!s.contains('&')) return s
        return ENTITY.replace(s) { m ->
            val name = m.groupValues[1]
            when {
                name.startsWith("#x") || name.startsWith("#X") ->
                    name.substring(2).toIntOrNull(16)?.let { String(Character.toChars(it)) } ?: m.value
                name.startsWith("#") ->
                    name.substring(1).toIntOrNull()?.let { String(Character.toChars(it)) } ?: m.value
                else -> NAMED_ENTITIES[name] ?: m.value
            }
        }
    }

    private class Builder {
        val out = StringBuilder()
        val spans = mutableListOf<Span>()

        inline fun styled(style: Style, target: String? = null, block: () -> Unit) {
            val start = out.length
            block()
            if (out.length > start) spans.add(Span(start, out.length, style, target))
        }

        fun parse(s: String) {
            var i = 0
            while (i < s.length) {
                i = step(s, i)
            }
        }

        /** Consumes one token at [i] and returns the next position. */
        private fun step(s: String, i: Int): Int {
            val c = s[i]
            when (c) {
                '\\' -> if (i + 1 < s.length && s[i + 1] in ESCAPABLE) {
                    out.append(s[i + 1]); return i + 2
                }
                '`' -> return code(s, i)
                '%' -> if (s.startsWith("%%", i)) {
                    val end = s.indexOf("%%", i + 2)
                    return if (end < 0) s.length else end + 2
                }
                '$' -> math(s, i)?.let { return it }
                '!' -> {
                    if (s.startsWith("![[", i)) wikiLink(s, i + 1, embed = true)?.let { return it }
                    if (s.startsWith("![", i)) link(s, i + 1, image = true)?.let { return it }
                }
                '[' -> {
                    if (s.startsWith("[[", i)) wikiLink(s, i, embed = false)?.let { return it }
                    if (s.startsWith("[^", i)) footnoteRef(s, i)?.let { return it }
                    link(s, i, image = false)?.let { return it }
                }
                '^' -> if (s.startsWith("^[", i)) inlineFootnote(s, i)?.let { return it }
                '<' -> angle(s, i)?.let { return it }
                '#' -> tag(s, i)?.let { return it }
                '*', '_', '~', '=' -> emphasis(s, i)?.let { return it }
                else -> if (c.isLetterOrDigit()) bareUrl(s, i)?.let { return it }
            }
            out.append(c)
            return i + 1
        }

        private fun runLength(s: String, i: Int, ch: Char): Int {
            var j = i
            while (j < s.length && s[j] == ch) j++
            return j - i
        }

        private fun code(s: String, i: Int): Int {
            val n = runLength(s, i, '`')
            var j = i + n
            while (j < s.length) {
                val k = s.indexOf('`', j)
                if (k < 0) break
                val m = runLength(s, k, '`')
                if (m == n) {
                    var content = s.substring(i + n, k)
                    if (content.length > 2 && content.startsWith(" ") && content.endsWith(" ")) {
                        content = content.substring(1, content.length - 1)
                    }
                    styled(Style.CODE) { out.append(content) }
                    return k + m
                }
                j = k + m
            }
            out.append(s, i, i + n)
            return i + n
        }

        private fun math(s: String, i: Int): Int? {
            if (s.startsWith("$$", i)) {
                val end = s.indexOf("$$", i + 2)
                if (end <= i + 2) return null
                styled(Style.MATH) { out.append(s.substring(i + 2, end).trim()) }
                return end + 2
            }
            if (i + 1 >= s.length || s[i + 1].isWhitespace()) return null
            var j = i + 1
            while (j < s.length) {
                j = s.indexOf('$', j)
                if (j < 0) return null
                val closes = !s[j - 1].isWhitespace() && s[j - 1] != '\\' &&
                    (j + 1 >= s.length || !s[j + 1].isDigit())
                if (closes) {
                    styled(Style.MATH) { out.append(s.substring(i + 1, j)) }
                    return j + 1
                }
                j++
            }
            return null
        }

        private fun wikiLink(s: String, i: Int, embed: Boolean): Int? {
            val end = s.indexOf("]]", i + 2)
            if (end < 0) return null
            val inner = s.substring(i + 2, end)
            if (inner.isBlank() || inner.contains('\n')) return null
            val target = inner.substringBefore('|').trim()
            val alias = if (inner.contains('|')) inner.substringAfter('|').trim() else ""
            val file = target.substringBefore('#')
            val subpath = if (target.contains('#')) target.substringAfter('#').removePrefix("^") else ""
            val defaultLabel = when {
                file.isEmpty() -> subpath
                subpath.isNotEmpty() -> "${file.substringAfterLast('/')} > $subpath"
                else -> file.substringAfterLast('/')
            }
            if (embed) {
                val ext = file.substringAfterLast('.', "").lowercase()
                val isImage = ext in IMAGE_EXTENSIONS
                // For images the alias is a size (e.g. "|200"), not a caption.
                val label = if (isImage || alias.isEmpty() || alias.all { it.isDigit() || it == 'x' }) defaultLabel else alias
                val icon = if (isImage) "🖼 " else "↪ "
                styled(Style.EMBED, file.ifEmpty { null }) { out.append(icon).append(label) }
            } else {
                styled(Style.INTERNAL_LINK, file.ifEmpty { null }) { out.append(alias.ifEmpty { defaultLabel }) }
            }
            return end + 2
        }

        /** Finds the `]` matching the `[` at [open], honouring nesting and escapes. */
        private fun closingBracket(s: String, open: Int): Int {
            var depth = 0
            var j = open
            while (j < s.length) {
                when (s[j]) {
                    '\\' -> j++
                    '[' -> depth++
                    ']' -> {
                        depth--
                        if (depth == 0) return j
                    }
                    '`' -> {
                        val n = runLength(s, j, '`')
                        val close = s.indexOf("`".repeat(n), j + n)
                        if (close > 0) j = close + n - 1
                    }
                }
                j++
            }
            return -1
        }

        private fun link(s: String, i: Int, image: Boolean): Int? {
            val close = closingBracket(s, i)
            if (close < 0 || close + 1 >= s.length || s[close + 1] != '(') return null
            var depth = 0
            var j = close + 1
            while (j < s.length) {
                when (s[j]) {
                    '(' -> depth++
                    ')' -> {
                        depth--
                        if (depth == 0) break
                    }
                }
                j++
            }
            if (j >= s.length) return null
            var destination = s.substring(close + 2, j).trim()
            if (destination.startsWith("<") && destination.contains(">")) {
                destination = destination.substring(1, destination.indexOf('>'))
            } else {
                destination = destination.replace(LINK_TITLE, "")
            }
            val label = s.substring(i + 1, close)
            val external = destination.contains("://") || destination.startsWith("mailto:")
            val style = if (external) Style.LINK else Style.INTERNAL_LINK
            val target = if (external) destination else decodePath(destination).substringBefore('#')
            if (image) {
                val alt = label.substringBefore('|').ifBlank { destination.substringAfterLast('/') }
                styled(Style.EMBED, target) { out.append("🖼 ").append(alt) }
            } else {
                styled(style, target) { parse(label.ifEmpty { destination }) }
            }
            return j + 1
        }

        private fun decodePath(path: String): String =
            try {
                URLDecoder.decode(path.replace("+", "%2B"), "UTF-8")
            } catch (_: Exception) {
                path
            }

        private fun footnoteRef(s: String, i: Int): Int? {
            val end = s.indexOf(']', i + 2)
            if (end < 0 || end == i + 2) return null
            // A definition ("[^1]: text") is handled at block level.
            styled(Style.FOOTNOTE) { out.append('[').append(s, i + 2, end).append(']') }
            return end + 1
        }

        private fun inlineFootnote(s: String, i: Int): Int? {
            val close = closingBracket(s, i + 1)
            if (close < 0) return null
            styled(Style.FOOTNOTE) {
                out.append('[')
                parse(s.substring(i + 2, close))
                out.append(']')
            }
            return close + 1
        }

        private fun angle(s: String, i: Int): Int? {
            val end = s.indexOf('>', i)
            if (end < 0) return null
            val inner = s.substring(i + 1, end)
            if (URL.matches(inner)) {
                styled(Style.LINK, inner) { out.append(inner) }
                return end + 1
            }
            val tag = HTML_TAG.matchAt(s, i) ?: return null
            val name = tag.groupValues[1].lowercase()
            val afterTag = i + tag.value.length
            if (tag.value.startsWith("</")) return afterTag
            if (name == "br") {
                out.append('\n')
                return afterTag
            }
            if (name == "hr") {
                out.append("\n――――\n")
                return afterTag
            }
            val styles = HTML_STYLES[name]
            val closeTag = Regex("</$name\\s*>", RegexOption.IGNORE_CASE).find(s, afterTag)
            if (closeTag == null || tag.value.endsWith("/>")) return afterTag
            val content = s.substring(afterTag, closeTag.range.first)
            if (name == "a") {
                val href = HREF.find(tag.value)?.groupValues?.get(1)
                if (href != null) {
                    styled(Style.LINK, href) { parse(content) }
                    return closeTag.range.last + 1
                }
            }
            applyStyles(styles ?: emptyList()) { parse(content) }
            return closeTag.range.last + 1
        }

        private fun applyStyles(styles: List<Style>, block: () -> Unit) {
            if (styles.isEmpty()) {
                block()
                return
            }
            styled(styles.first()) { applyStyles(styles.drop(1), block) }
        }

        private fun tag(s: String, i: Int): Int? {
            if (i > 0 && !s[i - 1].isWhitespace() && s[i - 1] !in "([{,;") return null
            val match = TAG.matchAt(s, i) ?: return null
            val body = match.value.substring(1).trimEnd('/', '-')
            if (body.isEmpty() || body.all { it.isDigit() }) return null
            styled(Style.TAG, body) { out.append('#').append(body) }
            return i + 1 + body.length
        }

        private fun bareUrl(s: String, i: Int): Int? {
            if (i > 0 && (s[i - 1].isLetterOrDigit() || s[i - 1] == '/')) return null
            val match = URL.matchAt(s, i) ?: BARE_DOMAIN.matchAt(s, i) ?: return null
            val url = match.value.trimEnd('.', ',', ';', ':', '!', '?', ')', '\'', '"')
            val target = if (url.contains(':')) url else "https://$url"
            styled(Style.LINK, target) { out.append(url) }
            return i + url.length
        }

        private fun emphasis(s: String, i: Int): Int? {
            val ch = s[i]
            val run = runLength(s, i, ch)
            val prev = if (i > 0) s[i - 1] else ' '
            when (ch) {
                '~', '=' -> {
                    if (run != 2 || i + 2 >= s.length || s[i + 2].isWhitespace()) return null
                    val end = findClosing(s, i + 2, "$ch$ch") ?: return null
                    val style = if (ch == '~') Style.STRIKE else Style.HIGHLIGHT
                    styled(style) { parse(s.substring(i + 2, end)) }
                    return end + 2
                }
            }
            // No emphasis for intraword underscores (snake_case)
            if (ch == '_' && prev.isLetterOrDigit()) return null
            for (len in minOf(run, 3) downTo 1) {
                val delimiter = ch.toString().repeat(len)
                val start = i + len
                if (start >= s.length || s[start].isWhitespace()) continue
                val end = findClosing(s, start, delimiter) ?: continue
                val inner = s.substring(start, end)
                when (len) {
                    3 -> styled(Style.BOLD) { styled(Style.ITALIC) { parse(inner) } }
                    2 -> styled(Style.BOLD) { parse(inner) }
                    else -> styled(Style.ITALIC) { parse(inner) }
                }
                return end + len
            }
            return null
        }

        /** Index of a closing [delimiter] at or after [from], skipping code spans and nested runs. */
        private fun findClosing(s: String, from: Int, delimiter: String): Int? {
            val ch = delimiter[0]
            var j = from
            while (j < s.length) {
                val c = s[j]
                if (c == '\\') {
                    j += 2
                    continue
                }
                if (c == '`') {
                    val n = runLength(s, j, '`')
                    val close = s.indexOf("`".repeat(n), j + n)
                    j = if (close > 0) close + n else j + n
                    continue
                }
                if (c == '[' && s.startsWith("[[", j)) {
                    val close = s.indexOf("]]", j)
                    if (close > 0) {
                        j = close + 2
                        continue
                    }
                }
                if (c == ch) {
                    val run = runLength(s, j, ch)
                    val next = if (j + run < s.length) s[j + run] else ' '
                    val canClose = j > from && !s[j - 1].isWhitespace() &&
                        (ch != '_' || !next.isLetterOrDigit())
                    if (canClose && run >= delimiter.length) return j
                    // Otherwise this run may open a nested emphasis; skip over it as a whole.
                    val canOpen = !next.isWhitespace() && (ch != '_' || j == 0 || !s[j - 1].isLetterOrDigit())
                    if (canOpen) {
                        val nestedLength = minOf(run, 3)
                        val nested = findClosing(s, j + nestedLength, ch.toString().repeat(nestedLength))
                        if (nested != null) {
                            j = nested + nestedLength
                            continue
                        }
                    }
                    j += run
                    continue
                }
                j++
            }
            return null
        }
    }
}
