package com.obsidianwidget

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.obsidianwidget.VaultManager.BlockType

class ChecklistWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory {
        val widgetId = intent.getIntExtra(
            android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_ID, -1
        )
        return ChecklistRemoteViewsFactory(applicationContext, widgetId)
    }
}

class ChecklistRemoteViewsFactory(
    private val context: Context,
    private val widgetId: Int
) : RemoteViewsService.RemoteViewsFactory {

    private var items = listOf<VaultManager.ChecklistItem>()
    private var tapCheckboxOnly = false
    private var vaultName: String? = null
    private var themeColors = VaultManager.ThemeColors(
        bg = 0xFF1A1A1E.toInt(),
        text = 0xFFE8E6E3.toInt(),
        textSecondary = 0xFF8B8B8F.toInt(),
        accent = 0xFFD97757.toInt(),
        buttonText = 0xFFFFFFFF.toInt()
    )

    companion object {
        private val HEADING_SIZES_SP = floatArrayOf(20f, 18f, 16.5f, 15f, 14f, 13.5f)
    }

    private val density get() = context.resources.displayMetrics.density

    private fun dp(value: Int): Int = (value * density).toInt()

    override fun onCreate() {}

    override fun onDataSetChanged() {
        val vaultManager = VaultManager(context, widgetId)
        items = vaultManager.parseChecklist()
        tapCheckboxOnly = vaultManager.tapCheckboxOnly
        themeColors = vaultManager.getThemeColors()
        vaultName = vaultManager.vaultName
    }

    override fun onDestroy() {
        items = emptyList()
    }

    override fun getCount(): Int = items.size

    override fun getViewAt(position: Int): RemoteViews {
        val item = items[position]
        return when (item.type) {
            BlockType.PROPERTY -> propertyView(item)
            BlockType.HEADING -> headingView(item)
            BlockType.HORIZONTAL_RULE -> horizontalRuleView()
            BlockType.CODE, BlockType.MATH -> codeView(item)
            BlockType.TABLE_ROW -> tableRowView(item)
            BlockType.TASK -> taskView(item)
            BlockType.PARAGRAPH, BlockType.BULLET, BlockType.ORDERED,
            BlockType.CALLOUT_TITLE, BlockType.FOOTNOTE -> textView(item)
        }
    }

    private fun styled(markdown: String) = MarkdownStyler.style(markdown, themeColors)

    /**
     * Tap target for a row: first external link, else first internal link opened in Obsidian,
     * else no action.
     */
    private fun linkIntent(markdown: String): Intent {
        val parsed = MarkdownInline.parse(markdown)
        val url = parsed.firstUrl ?: parsed.firstInternalLink?.let { target ->
            val vault = vaultName ?: return@let null
            Uri.Builder()
                .scheme("obsidian")
                .authority("open")
                .appendQueryParameter("vault", vault)
                .appendQueryParameter("file", target)
                .build()
                .toString()
        }
        return if (url != null) {
            Intent().putExtra(ObsidianWidgetProvider.EXTRA_URL, url)
        } else {
            noActionIntent()
        }
    }

    /** Rows without their own action still need a fill-in intent so taps are absorbed. */
    private fun noActionIntent(): Intent = Intent()

    /**
     * Draws one vertical bar per blockquote level and tints callout rows. Always resets the
     * containers so recycled rows don't keep stale decoration.
     */
    private fun decorate(views: RemoteViews, rootId: Int, item: VaultManager.ChecklistItem) {
        views.removeAllViews(R.id.quote_bars)
        val callout = item.calloutType?.let { MarkdownStyler.callout(it) }
        if (item.quoteDepth > 0) {
            views.setViewVisibility(R.id.quote_bars, View.VISIBLE)
            repeat(item.quoteDepth) { level ->
                val bar = RemoteViews(context.packageName, R.layout.widget_quote_bar)
                val color = if (callout != null && level == item.quoteDepth - 1) callout.color else themeColors.accent
                bar.setInt(R.id.quote_bar_line, "setBackgroundColor", color)
                views.addView(R.id.quote_bars, bar)
            }
        } else {
            views.setViewVisibility(R.id.quote_bars, View.GONE)
        }
        views.setInt(
            rootId, "setBackgroundColor",
            if (callout != null) MarkdownStyler.withAlpha(callout.color, 0x1F) else Color.TRANSPARENT
        )
    }

    private fun setIndent(views: RemoteViews, level: Int) {
        views.setViewPadding(R.id.row_body, dp(level * 16), 0, 0, 0)
    }

    private fun propertyView(item: VaultManager.ChecklistItem): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_property_item)
        views.setTextViewText(R.id.property_item_name, item.propertyName)
        views.setTextViewText(R.id.property_item_value, styled(item.text))
        views.setTextColor(R.id.property_item_name, themeColors.textSecondary)
        views.setTextColor(R.id.property_item_value, themeColors.text)
        views.setOnClickFillInIntent(R.id.property_item_root, linkIntent(item.text))
        return views
    }

    private fun headingView(item: VaultManager.ChecklistItem): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_heading_item)
        decorate(views, R.id.heading_item_root, item)
        val level = item.headingLevel.coerceIn(1, 6)
        views.setTextViewText(R.id.heading_item_content, styled(item.text))
        views.setTextViewTextSize(R.id.heading_item_content, TypedValue.COMPLEX_UNIT_SP, HEADING_SIZES_SP[level - 1])
        views.setTextColor(R.id.heading_item_content, if (level == 6) themeColors.textSecondary else themeColors.text)
        val topPadding = if (level <= 2) 10 else 8
        views.setViewPadding(R.id.heading_item_root, dp(4), dp(topPadding), dp(4), dp(4))
        views.setOnClickFillInIntent(R.id.heading_item_root, linkIntent(item.text))
        return views
    }

    private fun horizontalRuleView(): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_horizontal_rule_item)
        views.setInt(R.id.horizontal_rule_line, "setColorFilter", themeColors.textSecondary)
        views.setOnClickFillInIntent(R.id.horizontal_rule_root, noActionIntent())
        return views
    }

    private fun codeView(item: VaultManager.ChecklistItem): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_code_item)
        decorate(views, R.id.code_item_root, item)
        views.setInt(R.id.row_body, "setBackgroundColor", MarkdownStyler.withAlpha(themeColors.textSecondary, 0x26))
        val isMath = item.type == BlockType.MATH
        val label = if (isMath) "" else item.codeLanguage
        views.setTextViewText(R.id.code_item_language, label)
        views.setViewVisibility(R.id.code_item_language, if (label.isEmpty()) View.GONE else View.VISIBLE)
        views.setTextColor(R.id.code_item_language, themeColors.textSecondary)

        views.setTextViewText(R.id.code_item_content, if (isMath) MarkdownStyler.math(item.text) else item.text)
        views.setInt(R.id.code_item_content, "setGravity", if (isMath) Gravity.CENTER_HORIZONTAL else Gravity.START)
        views.setTextColor(R.id.code_item_content, themeColors.text)
        views.setOnClickFillInIntent(R.id.code_item_root, noActionIntent())
        return views
    }

    private fun tableRowView(item: VaultManager.ChecklistItem): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_table_row_item)
        decorate(views, R.id.table_row_root, item)
        views.removeAllViews(R.id.table_cells)
        item.tableCells.forEachIndexed { index, cell ->
            val cellView = RemoteViews(context.packageName, R.layout.widget_table_cell)
            val text = styled(cell)
            cellView.setTextViewText(
                R.id.table_cell,
                if (item.isTableHeader) MarkdownStyler.spanAll(text, StyleSpan(Typeface.BOLD)) else text
            )
            cellView.setTextColor(R.id.table_cell, themeColors.text)
            val gravity = when (item.tableAlignments.getOrNull(index)) {
                'c' -> Gravity.CENTER_HORIZONTAL
                'r' -> Gravity.END
                else -> Gravity.START
            }
            cellView.setInt(R.id.table_cell, "setGravity", gravity or Gravity.TOP)
            views.addView(R.id.table_cells, cellView)
        }
        views.setInt(
            R.id.table_cells, "setBackgroundColor",
            if (item.isTableHeader) MarkdownStyler.withAlpha(themeColors.textSecondary, 0x26) else Color.TRANSPARENT
        )
        views.setInt(
            R.id.table_row_divider, "setBackgroundColor",
            MarkdownStyler.withAlpha(themeColors.textSecondary, if (item.isTableHeader) 0x80 else 0x40)
        )
        views.setOnClickFillInIntent(R.id.table_row_root, linkIntent(item.text))
        return views
    }

    private fun textView(item: VaultManager.ChecklistItem): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_text_item)
        decorate(views, R.id.text_item_root, item)
        setIndent(views, item.indentLevel)

        var content: CharSequence = styled(item.text)
        var marker: CharSequence? = item.listMarker
        var textColor = themeColors.text
        var textSize = 13f
        when (item.type) {
            BlockType.CALLOUT_TITLE -> {
                val callout = MarkdownStyler.callout(item.calloutType)
                val fold = when (item.calloutFold) {
                    "-" -> "  ▸"
                    "+" -> "  ▾"
                    else -> ""
                }
                marker = MarkdownStyler.spanAll(callout.icon, ForegroundColorSpan(callout.color))
                content = MarkdownStyler.spanAll(TextUtils.concat(content, fold), StyleSpan(Typeface.BOLD))
                textColor = callout.color
            }
            BlockType.FOOTNOTE -> {
                marker = "${item.listMarker}."
                textColor = themeColors.textSecondary
                textSize = 12f
            }
            else -> {}
        }

        if (marker != null) {
            views.setViewVisibility(R.id.text_item_marker, View.VISIBLE)
            views.setTextViewText(R.id.text_item_marker, marker)
            views.setTextColor(R.id.text_item_marker, themeColors.textSecondary)
            views.setTextViewTextSize(R.id.text_item_marker, TypedValue.COMPLEX_UNIT_SP, textSize)
        } else {
            views.setViewVisibility(R.id.text_item_marker, View.GONE)
        }
        views.setTextViewText(R.id.text_item_content, content)
        views.setTextColor(R.id.text_item_content, textColor)
        views.setTextViewTextSize(R.id.text_item_content, TypedValue.COMPLEX_UNIT_SP, textSize)
        views.setOnClickFillInIntent(R.id.text_item_root, linkIntent(item.text))
        return views
    }

    private fun taskView(item: VaultManager.ChecklistItem): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_checklist_item)
        decorate(views, R.id.checklist_item_root, item)
        setIndent(views, item.indentLevel)

        // Set checkbox: circle bg tinted to accent/secondary, white checkmark overlay
        if (item.isChecked) {
            views.setImageViewResource(R.id.checklist_checkbox_bg, R.drawable.ic_checkbox_checked)
            views.setInt(R.id.checklist_checkbox_bg, "setColorFilter", themeColors.accent)
            views.setViewVisibility(R.id.checklist_checkbox_mark, View.VISIBLE)
        } else {
            views.setImageViewResource(R.id.checklist_checkbox_bg, R.drawable.ic_checkbox_unchecked)
            views.setInt(R.id.checklist_checkbox_bg, "setColorFilter", themeColors.textSecondary)
            views.setViewVisibility(R.id.checklist_checkbox_mark, View.GONE)
        }

        // Set text with strikethrough if checked
        views.setTextViewText(R.id.checklist_text, styled(item.text))
        if (item.isChecked) {
            views.setInt(R.id.checklist_text, "setPaintFlags",
                Paint.STRIKE_THRU_TEXT_FLAG or Paint.ANTI_ALIAS_FLAG)
            views.setTextColor(R.id.checklist_text, themeColors.textSecondary)
        } else {
            views.setInt(R.id.checklist_text, "setPaintFlags", Paint.ANTI_ALIAS_FLAG)
            views.setTextColor(R.id.checklist_text, themeColors.text)
        }

        // Fill-in intent for toggling this item
        val fillIntent = Intent().apply {
            putExtra(ObsidianWidgetProvider.EXTRA_LINE_INDEX, item.lineIndex)
            putExtra(ObsidianWidgetProvider.EXTRA_WIDGET_ID, widgetId)
        }
        if (tapCheckboxOnly) {
            views.setOnClickFillInIntent(R.id.checklist_checkbox, fillIntent)
            views.setOnClickFillInIntent(R.id.checklist_item_root, linkIntent(item.text))
        } else {
            views.setOnClickFillInIntent(R.id.checklist_item_root, fillIntent)
        }

        return views
    }

    override fun getLoadingView(): RemoteViews? = null
    override fun getViewTypeCount(): Int = 7
    override fun getItemId(position: Int): Long = items[position].lineIndex.toLong()
    override fun hasStableIds(): Boolean = true
}
