package com.obsidianwidget

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.SubscriptSpan
import android.text.style.SuperscriptSpan
import android.text.style.TypefaceSpan
import android.text.style.UnderlineSpan
import com.obsidianwidget.MarkdownInline.Style

/**
 * Turns parsed inline Markdown into a Spanned for RemoteViews. Only span types that
 * survive RemoteViews parceling are used.
 */
object MarkdownStyler {

    private const val HIGHLIGHT_COLOR = 0x66FFD000

    data class Callout(val color: Int, val icon: String)

    private val CALLOUTS: Map<String, Callout> = buildMap {
        fun put(names: List<String>, color: Int, icon: String) = names.forEach { put(it, Callout(color, icon)) }
        put(listOf("note"), 0xFF086DDD.toInt(), "✎")
        put(listOf("abstract", "summary", "tldr"), 0xFF00BFBC.toInt(), "☰")
        put(listOf("info"), 0xFF086DDD.toInt(), "ℹ")
        put(listOf("todo"), 0xFF086DDD.toInt(), "☑")
        put(listOf("tip", "hint", "important"), 0xFF00BFBC.toInt(), "🔥")
        put(listOf("success", "check", "done"), 0xFF08B94E.toInt(), "✓")
        put(listOf("question", "help", "faq"), 0xFFEC7500.toInt(), "?")
        put(listOf("warning", "caution", "attention"), 0xFFEC7500.toInt(), "⚠")
        put(listOf("failure", "fail", "missing"), 0xFFE93147.toInt(), "✕")
        put(listOf("danger", "error"), 0xFFE93147.toInt(), "⚡")
        put(listOf("bug"), 0xFFE93147.toInt(), "🐞")
        put(listOf("example"), 0xFF7852EE.toInt(), "☷")
        put(listOf("quote", "cite"), 0xFF9E9E9E.toInt(), "❝")
    }

    /** Unknown callout types fall back to the "note" look, like in Obsidian. */
    fun callout(type: String?): Callout = CALLOUTS[type?.lowercase()] ?: CALLOUTS.getValue("note")

    fun withAlpha(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or (alpha shl 24)

    /** Applies [spans] to the whole of [text]. */
    fun spanAll(text: CharSequence, vararg spans: Any): Spanned =
        SpannableStringBuilder(text).apply {
            spans.forEach { setSpan(it, 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
        }

    private fun mathSpans(): List<Any> = listOf(TypefaceSpan("serif"), StyleSpan(Typeface.ITALIC))

    fun math(text: String): Spanned = spanAll(text, *mathSpans().toTypedArray())

    fun style(markdown: String, colors: VaultManager.ThemeColors): Spanned =
        style(MarkdownInline.parse(markdown), colors)

    fun style(parsed: MarkdownInline.Result, colors: VaultManager.ThemeColors): Spanned {
        val text = SpannableStringBuilder(parsed.text)
        val codeBackground = withAlpha(colors.textSecondary, 0x33)
        for (span in parsed.spans) {
            val spans: List<Any> = when (span.style) {
                Style.BOLD -> listOf(StyleSpan(Typeface.BOLD))
                Style.ITALIC -> listOf(StyleSpan(Typeface.ITALIC))
                Style.STRIKE -> listOf(StrikethroughSpan())
                Style.UNDERLINE -> listOf(UnderlineSpan())
                Style.HIGHLIGHT -> listOf(BackgroundColorSpan(HIGHLIGHT_COLOR))
                Style.CODE -> listOf(TypefaceSpan("monospace"), BackgroundColorSpan(codeBackground))
                Style.MATH -> mathSpans()
                Style.LINK -> listOf(ForegroundColorSpan(colors.accent), UnderlineSpan())
                Style.INTERNAL_LINK -> listOf(ForegroundColorSpan(colors.accent))
                Style.EMBED -> listOf(ForegroundColorSpan(colors.accent), StyleSpan(Typeface.ITALIC))
                Style.TAG -> listOf(
                    ForegroundColorSpan(colors.accent),
                    BackgroundColorSpan(withAlpha(colors.accent, 0x26))
                )
                Style.FOOTNOTE -> listOf(
                    SuperscriptSpan(),
                    RelativeSizeSpan(0.75f),
                    ForegroundColorSpan(colors.accent)
                )
                Style.SUPERSCRIPT -> listOf(SuperscriptSpan(), RelativeSizeSpan(0.8f))
                Style.SUBSCRIPT -> listOf(SubscriptSpan(), RelativeSizeSpan(0.8f))
            }
            spans.forEach { text.setSpan(it, span.start, span.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
        }
        return text
    }
}
