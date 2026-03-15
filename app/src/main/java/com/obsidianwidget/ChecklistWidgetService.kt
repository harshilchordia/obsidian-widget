package com.obsidianwidget

import android.content.Context
import android.content.Intent
import android.graphics.Paint
import android.widget.RemoteViews
import android.widget.RemoteViewsService

class ChecklistWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory {
        return ChecklistRemoteViewsFactory(applicationContext)
    }
}

class ChecklistRemoteViewsFactory(
    private val context: Context
) : RemoteViewsService.RemoteViewsFactory {

    private var items = listOf<VaultManager.ChecklistItem>()

    override fun onCreate() {}

    override fun onDataSetChanged() {
        val vaultManager = VaultManager(context)
        items = vaultManager.parseChecklist()
    }

    override fun onDestroy() {
        items = emptyList()
    }

    override fun getCount(): Int = items.size

    override fun getViewAt(position: Int): RemoteViews {
        val item = items[position]
        val views = RemoteViews(context.packageName, R.layout.widget_checklist_item)

        // Set checkbox icon
        views.setImageViewResource(
            R.id.checklist_checkbox,
            if (item.isChecked) R.drawable.ic_checkbox_checked else R.drawable.ic_checkbox_unchecked
        )

        // Set text with strikethrough if checked
        views.setTextViewText(R.id.checklist_text, item.text)
        if (item.isChecked) {
            views.setInt(R.id.checklist_text, "setPaintFlags",
                Paint.STRIKE_THRU_TEXT_FLAG or Paint.ANTI_ALIAS_FLAG)
            views.setTextColor(R.id.checklist_text,
                context.getColor(R.color.obsidian_text_secondary))
        } else {
            views.setInt(R.id.checklist_text, "setPaintFlags", Paint.ANTI_ALIAS_FLAG)
            views.setTextColor(R.id.checklist_text,
                context.getColor(R.color.obsidian_text))
        }

        // Fill-in intent for toggling this item
        val fillIntent = Intent().apply {
            putExtra(ObsidianWidgetProvider.EXTRA_LINE_INDEX, item.lineIndex)
        }
        views.setOnClickFillInIntent(R.id.checklist_item_root, fillIntent)

        return views
    }

    override fun getLoadingView(): RemoteViews? = null
    override fun getViewTypeCount(): Int = 1
    override fun getItemId(position: Int): Long = items[position].lineIndex.toLong()
    override fun hasStableIds(): Boolean = true
}
