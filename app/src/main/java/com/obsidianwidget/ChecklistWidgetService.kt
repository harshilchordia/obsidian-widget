package com.obsidianwidget

import android.content.Context
import android.content.Intent
import android.graphics.Paint
import android.text.Html
import android.view.View
import android.widget.RemoteViews
import android.widget.RemoteViewsService

class ChecklistWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory {
        val widgetId = intent.getIntExtra(
            android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_ID, -1
        )
        val forceTab = intent.getStringExtra(EXTRA_FORCE_TAB)
        return ChecklistRemoteViewsFactory(applicationContext, widgetId, forceTab)
    }

    companion object {
        const val EXTRA_FORCE_TAB = "extra_force_tab"
    }
}

class ChecklistRemoteViewsFactory(
    private val context: Context,
    private val widgetId: Int,
    private val forceTab: String? = null
) : RemoteViewsService.RemoteViewsFactory {

    private var items = listOf<VaultManager.ChecklistItem>()
    private var tapCheckboxOnly = false
    private var themeColors = VaultManager.ThemeColors(
        bg = 0xFF1A1A1E.toInt(),
        text = 0xFFE8E6E3.toInt(),
        textSecondary = 0xFF8B8B8F.toInt(),
        accent = 0xFFD97757.toInt(),
        buttonText = 0xFFFFFFFF.toInt()
    )

    companion object {
        private val BOLD_ITALIC = Regex("""\*\*\*(.+?)\*\*\*""")
        private val BOLD = Regex("""\*\*(.+?)\*\*""")
        private val ITALIC_STAR = Regex("""\*(.+?)\*""")
        private val ITALIC_UNDER = Regex("""_(.+?)_""")
        private val MD_LINK = Regex("""\[([^\]]+)\]\(([^)]+)\)""")
        private val BARE_URL = Regex("""(?<!["'=(])((?:https?://)?[a-zA-Z0-9](?:[a-zA-Z0-9-]*[a-zA-Z0-9])?(?:\.[a-zA-Z]{2,})+(?:/[^\s<>"']*)?)""")

        fun extractFirstUrl(text: String): String? {
            val mdMatch = MD_LINK.find(text)
            if (mdMatch != null) {
                val url = mdMatch.groupValues[2]
                return if (url.startsWith("http")) url else "https://$url"
            }
            val bareMatch = BARE_URL.find(text)
            if (bareMatch != null) {
                val url = bareMatch.groupValues[1]
                return if (url.startsWith("http")) url else "https://$url"
            }
            return null
        }

        fun markdownToHtml(text: String): CharSequence {
            var html = text
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
            html = MD_LINK.replace(html) { "<a href=\"${it.groupValues[2]}\">${it.groupValues[1]}</a>" }
            html = BARE_URL.replace(html) {
                val url = it.groupValues[1]
                val href = if (url.startsWith("http")) url else "https://$url"
                "<a href=\"$href\">$url</a>"
            }
            html = BOLD_ITALIC.replace(html) { "<b><i>${it.groupValues[1]}</i></b>" }
            html = BOLD.replace(html) { "<b>${it.groupValues[1]}</b>" }
            html = ITALIC_STAR.replace(html) { "<i>${it.groupValues[1]}</i>" }
            html = ITALIC_UNDER.replace(html) { "<i>${it.groupValues[1]}</i>" }
            return Html.fromHtml(html, Html.FROM_HTML_MODE_COMPACT)
        }
    }

    override fun onCreate() {}

    override fun onDataSetChanged() {
        val vaultManager = VaultManager(context, widgetId)
        items = vaultManager.parseChecklist(forceTab)
        tapCheckboxOnly = vaultManager.tapCheckboxOnly
        themeColors = vaultManager.getThemeColors()
    }

    override fun onDestroy() {
        items = emptyList()
    }

    override fun getCount(): Int = items.size

    override fun getViewAt(position: Int): RemoteViews {
        val item = items[position]

        if (item.isHeading) {
            val views = RemoteViews(context.packageName, R.layout.widget_heading_item)
            views.setTextViewText(R.id.heading_item_content, markdownToHtml(item.text))
            views.setTextColor(R.id.heading_item_content, themeColors.text)
            val url = extractFirstUrl(item.text)
            if (url != null) {
                views.setOnClickFillInIntent(R.id.heading_item_root, Intent().apply {
                    putExtra(ObsidianWidgetProvider.EXTRA_URL, url)
                })
            } else {
                views.setOnClickFillInIntent(R.id.heading_item_root, Intent())
            }
            return views
        }

        if (item.isPlainText) {
            // Notes / bullets — same edit & delete actions as tasks
            val views = RemoteViews(context.packageName, R.layout.widget_text_item)
            val displayText = if (item.isBullet) "•  ${item.text}" else item.text
            views.setTextViewText(R.id.text_item_content, markdownToHtml(displayText))
            views.setTextColor(R.id.text_item_content, themeColors.text)
            val density = context.resources.displayMetrics.density
            val indentPx = (item.indentLevel * 16 * density).toInt()
            views.setViewPadding(
                R.id.text_item_root,
                indentPx + (2 * density).toInt(),
                (5 * density).toInt(),
                0,
                (5 * density).toInt()
            )

            views.setInt(R.id.text_btn_edit, "setColorFilter", themeColors.textSecondary)
            views.setInt(R.id.text_btn_delete, "setColorFilter", 0xFFB07070.toInt())

            val editIntent = Intent().apply {
                putExtra(ObsidianWidgetProvider.EXTRA_WIDGET_ID, widgetId)
                putExtra(ObsidianWidgetProvider.EXTRA_LINE_INDEX, item.lineIndex)
                putExtra(ObsidianWidgetProvider.EXTRA_TASK_ACTION, ObsidianWidgetProvider.TASK_EDIT)
            }
            val deleteIntent = Intent().apply {
                putExtra(ObsidianWidgetProvider.EXTRA_WIDGET_ID, widgetId)
                putExtra(ObsidianWidgetProvider.EXTRA_LINE_INDEX, item.lineIndex)
                putExtra(ObsidianWidgetProvider.EXTRA_TASK_ACTION, ObsidianWidgetProvider.TASK_DELETE)
            }
            views.setOnClickFillInIntent(R.id.text_btn_edit, editIntent)
            views.setOnClickFillInIntent(R.id.text_btn_delete, deleteIntent)

            // Body tap: open link if present, otherwise no-op (edit via pencil)
            val url = extractFirstUrl(item.text)
            if (url != null) {
                views.setOnClickFillInIntent(R.id.text_item_content, Intent().apply {
                    putExtra(ObsidianWidgetProvider.EXTRA_URL, url)
                })
            } else {
                views.setOnClickFillInIntent(R.id.text_item_content, Intent())
            }
            views.setOnClickFillInIntent(R.id.text_item_root, Intent())
            return views
        }

        // ── Checklist task row ───────────────────────────────────────────────
        val views = RemoteViews(context.packageName, R.layout.widget_checklist_item)

        val density = context.resources.displayMetrics.density
        val indentPx = (item.indentLevel * 16 * density).toInt()
        views.setViewPadding(
            R.id.checklist_item_root,
            indentPx + (2 * density).toInt(),
            (6 * density).toInt(),
            0,
            (6 * density).toInt()
        )

        if (item.isChecked) {
            views.setImageViewResource(R.id.checklist_checkbox_bg, R.drawable.ic_checkbox_checked)
            views.setInt(R.id.checklist_checkbox_bg, "setColorFilter", themeColors.accent)
            views.setViewVisibility(R.id.checklist_checkbox_mark, View.VISIBLE)
        } else {
            views.setImageViewResource(R.id.checklist_checkbox_bg, R.drawable.ic_checkbox_unchecked)
            views.setInt(R.id.checklist_checkbox_bg, "setColorFilter", themeColors.textSecondary)
            views.setViewVisibility(R.id.checklist_checkbox_mark, View.GONE)
        }

        views.setTextViewText(R.id.checklist_text, markdownToHtml(item.text))
        if (item.isChecked) {
            views.setInt(
                R.id.checklist_text, "setPaintFlags",
                Paint.STRIKE_THRU_TEXT_FLAG or Paint.ANTI_ALIAS_FLAG
            )
            views.setTextColor(R.id.checklist_text, themeColors.textSecondary)
        } else {
            views.setInt(R.id.checklist_text, "setPaintFlags", Paint.ANTI_ALIAS_FLAG)
            views.setTextColor(R.id.checklist_text, themeColors.text)
        }

        // Completion time label
        val completed = item.completedLabel
        if (item.isChecked && !completed.isNullOrBlank()) {
            views.setViewVisibility(R.id.checklist_completed_label, View.VISIBLE)
            views.setTextViewText(R.id.checklist_completed_label, completed)
            views.setTextColor(R.id.checklist_completed_label, 0xFF6B6B70.toInt())
        } else {
            views.setViewVisibility(R.id.checklist_completed_label, View.GONE)
        }

        // Tint action icons
        views.setInt(R.id.checklist_btn_edit, "setColorFilter", themeColors.textSecondary)
        views.setInt(R.id.checklist_btn_delete, "setColorFilter", 0xFFB07070.toInt())

        // ── Click actions via fill-in intents ───────────────────────────────
        val toggleIntent = Intent().apply {
            putExtra(ObsidianWidgetProvider.EXTRA_WIDGET_ID, widgetId)
            putExtra(ObsidianWidgetProvider.EXTRA_LINE_INDEX, item.lineIndex)
            putExtra(ObsidianWidgetProvider.EXTRA_TASK_ACTION, ObsidianWidgetProvider.TASK_TOGGLE)
        }
        val editIntent = Intent().apply {
            putExtra(ObsidianWidgetProvider.EXTRA_WIDGET_ID, widgetId)
            putExtra(ObsidianWidgetProvider.EXTRA_LINE_INDEX, item.lineIndex)
            putExtra(ObsidianWidgetProvider.EXTRA_TASK_ACTION, ObsidianWidgetProvider.TASK_EDIT)
        }
        val deleteIntent = Intent().apply {
            putExtra(ObsidianWidgetProvider.EXTRA_WIDGET_ID, widgetId)
            putExtra(ObsidianWidgetProvider.EXTRA_LINE_INDEX, item.lineIndex)
            putExtra(ObsidianWidgetProvider.EXTRA_TASK_ACTION, ObsidianWidgetProvider.TASK_DELETE)
        }

        views.setOnClickFillInIntent(R.id.checklist_checkbox, toggleIntent)
        views.setOnClickFillInIntent(R.id.checklist_btn_edit, editIntent)
        views.setOnClickFillInIntent(R.id.checklist_btn_delete, deleteIntent)

        // Tapping the text column toggles (or only checkbox if configured)
        if (tapCheckboxOnly) {
            views.setOnClickFillInIntent(R.id.checklist_text_col, Intent())
            views.setOnClickFillInIntent(R.id.checklist_item_root, Intent())
        } else {
            views.setOnClickFillInIntent(R.id.checklist_text_col, toggleIntent)
            views.setOnClickFillInIntent(R.id.checklist_item_root, Intent())
        }

        return views
    }

    override fun getLoadingView(): RemoteViews? = null
    override fun getViewTypeCount(): Int = 3
    override fun getItemId(position: Int): Long = items[position].lineIndex.toLong()
    override fun hasStableIds(): Boolean = true
}
