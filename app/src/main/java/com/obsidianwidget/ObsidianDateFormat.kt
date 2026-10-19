package com.obsidianwidget

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAccessor

/**
 * Formats Obsidian/Moment.js date tokens in paths like "YYYY/MM/YYYY-MM-DD".
 *
 * Each contiguous run of letters is replaced only if the whole run decomposes
 * into known tokens; otherwise the run is left literal. This keeps static
 * folder names ("SUMMARY", "Daily Notes") intact while still resolving
 * contiguous formats like "YYYYMMDD". Text inside 'quotes' or [brackets] is
 * always literal. Legacy Java patterns saved by older versions of the widget
 * (lowercase "yyyy-MM-dd", "d.M.yyyy", "EEEE") keep their Java meaning.
 */
object ObsidianDateFormat {

    // Longest tokens first for greedy matching. Uppercase tokens follow
    // Moment.js semantics, lowercase ones keep the legacy Java meaning
    // (they agree everywhere except D/DD, which resolve as day-of-month).
    private val TOKEN_FORMATTERS: List<Pair<String, DateTimeFormatter>> = listOf(
        "MMMM" to "MMMM",
        "dddd" to "EEEE",
        "EEEE" to "EEEE",
        "YYYY" to "yyyy",
        "yyyy" to "yyyy",
        "MMM" to "MMM",
        "ddd" to "EEE",
        "EEE" to "EEE",
        "YY" to "yy",
        "yy" to "yy",
        "MM" to "MM",
        "DD" to "dd",
        "dd" to "dd",
        "HH" to "HH",
        "hh" to "hh",
        "mm" to "mm",
        "ss" to "ss",
        "M" to "M",
        "D" to "d",
        "d" to "d",
        "H" to "H",
        "h" to "h",
        "m" to "m",
        "s" to "s",
        "A" to "a",
        "a" to "a"
    ).map { (token, javaPattern) -> token to DateTimeFormatter.ofPattern(javaPattern) }

    fun format(pattern: String, date: LocalDate = LocalDate.now()): String {
        if (pattern.isBlank()) return ""
        return applyTokens(pattern, date).text
    }

    /**
     * Format a timestamp pattern, or return null if the pattern contains no
     * recognizable date/time tokens (i.e. nothing was replaced).
     */
    fun formatDateTime(pattern: String, dateTime: LocalDateTime = LocalDateTime.now()): String? {
        if (pattern.isBlank()) return null
        val result = applyTokens(pattern, dateTime)
        return if (result.replacedAny) result.text else null
    }

    private data class TokenResult(val text: String, val replacedAny: Boolean)

    private fun applyTokens(pattern: String, temporal: TemporalAccessor): TokenResult {
        val sb = StringBuilder(pattern.length)
        var replacedAny = false
        var i = 0
        while (i < pattern.length) {
            val c = pattern[i]
            when {
                c == '\'' || c == '[' -> {
                    val close = pattern.indexOf(if (c == '\'') '\'' else ']', i + 1)
                    if (close < 0) {
                        sb.append(pattern, i + 1, pattern.length)
                        i = pattern.length
                    } else {
                        sb.append(pattern, i + 1, close)
                        i = close + 1
                    }
                }
                c.isLetter() -> {
                    var runEnd = i + 1
                    while (runEnd < pattern.length && pattern[runEnd].isLetter()) runEnd++
                    val run = pattern.substring(i, runEnd)
                    val formatted = formatTokenRun(run, temporal)
                    if (formatted != null) {
                        sb.append(formatted)
                        replacedAny = true
                    } else {
                        sb.append(run)
                    }
                    i = runEnd
                }
                else -> {
                    sb.append(c)
                    i++
                }
            }
        }
        return TokenResult(sb.toString(), replacedAny)
    }

    /**
     * Format a run of letters if it fully decomposes into known tokens
     * supported by [temporal]; otherwise return null to keep it literal.
     */
    private fun formatTokenRun(run: String, temporal: TemporalAccessor): String? {
        val sb = StringBuilder()
        var i = 0
        while (i < run.length) {
            val match = TOKEN_FORMATTERS.firstOrNull { (token, _) ->
                run.startsWith(token, i)
            } ?: return null
            val (token, formatter) = match
            val value = try {
                formatter.format(temporal)
            } catch (_: Exception) {
                return null
            }
            sb.append(value)
            i += token.length
        }
        return sb.toString()
    }
}
