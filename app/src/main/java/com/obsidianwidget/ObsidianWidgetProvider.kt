package com.obsidianwidget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.RemoteViews

class ObsidianWidgetProvider : AppWidgetProvider() {

    companion object {
        private const val ACTION_REFRESH = "com.obsidianwidget.ACTION_REFRESH"
        private const val ACTION_CAPTURE = "com.obsidianwidget.ACTION_CAPTURE"
        private const val ACTION_OPEN = "com.obsidianwidget.ACTION_OPEN"
        private const val ACTION_TOGGLE = "com.obsidianwidget.ACTION_TOGGLE"
        private const val ACTION_ADD = "com.obsidianwidget.ACTION_ADD"
        private const val ACTION_NAV_LEFT = "com.obsidianwidget.ACTION_NAV_LEFT"
        private const val ACTION_NAV_RIGHT = "com.obsidianwidget.ACTION_NAV_RIGHT"
        const val EXTRA_LINE_INDEX = "extra_line_index"
        const val EXTRA_APPEND_TO_WIDGET = "extra_append_to_widget"
        const val EXTRA_WIDGET_ID = "extra_widget_id"

        fun updateAllWidgets(context: Context) {
            val intent = Intent(context, ObsidianWidgetProvider::class.java).apply {
                action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
            }
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val widgetIds = appWidgetManager.getAppWidgetIds(
                ComponentName(context, ObsidianWidgetProvider::class.java)
            )
            intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, widgetIds)
            context.sendBroadcast(intent)
        }
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (appWidgetId in appWidgetIds) {
            updateWidget(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        for (id in appWidgetIds) {
            VaultManager.deleteWidgetPrefs(context, id)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)

        when (intent.action) {
            ACTION_REFRESH, Intent.ACTION_USER_PRESENT -> updateAllWidgets(context)
            ACTION_CAPTURE -> {
                val captureIntent = Intent(context, QuickCaptureActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                }
                context.startActivity(captureIntent)
            }
            ACTION_ADD -> {
                val widgetId = intent.getIntExtra(EXTRA_WIDGET_ID, -1)
                val addIntent = Intent(context, QuickCaptureActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    putExtra(EXTRA_APPEND_TO_WIDGET, true)
                    putExtra(EXTRA_WIDGET_ID, widgetId)
                }
                context.startActivity(addIntent)
            }
            ACTION_OPEN -> {
                val widgetId = intent.getIntExtra(EXTRA_WIDGET_ID, -1)
                openObsidian(context, widgetId)
            }
            ACTION_TOGGLE -> {
                val lineIndex = intent.getIntExtra(EXTRA_LINE_INDEX, -1)
                val widgetId = intent.getIntExtra(EXTRA_WIDGET_ID, -1)
                if (lineIndex >= 0) {
                    val vaultManager = VaultManager(context, widgetId)
                    vaultManager.toggleChecklistItem(lineIndex)
                    // Notify the widget data changed so ListView refreshes
                    val appWidgetManager = AppWidgetManager.getInstance(context)
                    val widgetIds = appWidgetManager.getAppWidgetIds(
                        ComponentName(context, ObsidianWidgetProvider::class.java)
                    )
                    appWidgetManager.notifyAppWidgetViewDataChanged(widgetIds, R.id.widget_checklist)
                }
            }
            ACTION_NAV_LEFT, ACTION_NAV_RIGHT -> {
                val widgetId = intent.getIntExtra(EXTRA_WIDGET_ID, -1)
                if (widgetId >= 0) {
                    val vaultManager = VaultManager(context, widgetId)
                    vaultManager.navigateNote(if (intent.action == ACTION_NAV_LEFT) -1 else 1)
                    val appWidgetManager = AppWidgetManager.getInstance(context)
                    updateWidget(context, appWidgetManager, widgetId)
                }
            }
        }
    }

    private fun updateWidget(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int
    ) {
        val views = RemoteViews(context.packageName, R.layout.widget_layout)
        val vaultManager = VaultManager(context, appWidgetId)

        // Set title based on mode
        views.setTextViewText(R.id.widget_date, vaultManager.getWidgetTitle())

        // Check if note has checklist items
        val allItems = vaultManager.parseChecklist()
        val hasChecklist = allItems.any { !it.isPlainText }

        if (hasChecklist) {
            // Show interactive checklist ListView
            views.setViewVisibility(R.id.widget_checklist, View.VISIBLE)
            views.setViewVisibility(R.id.widget_note_preview, View.GONE)

            // Set up RemoteViews adapter for ListView
            val serviceIntent = Intent(context, ChecklistWidgetService::class.java).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                data = Uri.parse(toUri(Intent.URI_INTENT_SCHEME))
            }
            views.setRemoteAdapter(R.id.widget_checklist, serviceIntent)

            // Set up pending intent template for item clicks (toggle)
            val toggleIntent = Intent(context, ObsidianWidgetProvider::class.java).apply {
                action = ACTION_TOGGLE
                putExtra(EXTRA_WIDGET_ID, appWidgetId)
            }
            val togglePendingIntent = PendingIntent.getBroadcast(
                context, appWidgetId, toggleIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
            views.setPendingIntentTemplate(R.id.widget_checklist, togglePendingIntent)

            // Notify data changed
            appWidgetManager.notifyAppWidgetViewDataChanged(appWidgetId, R.id.widget_checklist)

        } else {
            // Show plain text preview
            views.setViewVisibility(R.id.widget_checklist, View.GONE)
            views.setViewVisibility(R.id.widget_note_preview, View.VISIBLE)

            if (vaultManager.isVaultConfigured || vaultManager.noteMode == VaultManager.NoteMode.PINNED) {
                val noteContent = vaultManager.readWidgetNote()
                val preview = noteContent?.take(500) ?: context.getString(R.string.no_daily_note)
                views.setTextViewText(R.id.widget_note_preview, preview)
            } else {
                views.setTextViewText(
                    R.id.widget_note_preview,
                    context.getString(R.string.no_vault_selected)
                )
            }
        }

        // Add to note button
        views.setOnClickPendingIntent(
            R.id.widget_add,
            createActionIntent(context, ACTION_ADD, appWidgetId)
        )

        // Title click opens note in Obsidian
        views.setOnClickPendingIntent(
            R.id.widget_date,
            createActionIntent(context, ACTION_OPEN, appWidgetId)
        )

        // Settings button opens widget config
        val configIntent = Intent(context, WidgetConfigActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        }
        views.setOnClickPendingIntent(
            R.id.widget_settings,
            PendingIntent.getActivity(
                context, appWidgetId + 10000, configIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        )

        // Quick capture button
        views.setOnClickPendingIntent(
            R.id.widget_btn_capture,
            createActionIntent(context, ACTION_CAPTURE, appWidgetId)
        )

        // Show/hide button bar based on setting
        views.setViewVisibility(
            R.id.widget_button_bar,
            if (vaultManager.showButtons) View.VISIBLE else View.GONE
        )

        // Apply widget transparency
        views.setFloat(R.id.widget_root, "setAlpha", vaultManager.widgetAlpha / 100f)

        // Show/hide navigation arrows for multi-note
        val noteCount = if (vaultManager.noteMode == VaultManager.NoteMode.PINNED) vaultManager.getPinnedNoteCount() else 0
        val showNav = noteCount > 1
        views.setViewVisibility(R.id.widget_nav_left, if (showNav) View.VISIBLE else View.GONE)
        views.setViewVisibility(R.id.widget_nav_right, if (showNav) View.VISIBLE else View.GONE)
        if (showNav) {
            views.setOnClickPendingIntent(R.id.widget_nav_left, createActionIntent(context, ACTION_NAV_LEFT, appWidgetId))
            views.setOnClickPendingIntent(R.id.widget_nav_right, createActionIntent(context, ACTION_NAV_RIGHT, appWidgetId))
        }

        appWidgetManager.updateAppWidget(appWidgetId, views)
    }

    private fun createActionIntent(context: Context, action: String, appWidgetId: Int): PendingIntent {
        val intent = Intent(context, ObsidianWidgetProvider::class.java).apply {
            this.action = action
            putExtra(EXTRA_WIDGET_ID, appWidgetId)
        }
        return PendingIntent.getBroadcast(
            context, action.hashCode() + appWidgetId, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun openObsidian(context: Context, widgetId: Int) {
        val vaultManager = VaultManager(context, widgetId)
        val vaultName = vaultManager.vaultName

        // Try to open the specific note in Obsidian via its URI scheme
        if (vaultName != null) {
            val noteName = when (vaultManager.noteMode) {
                VaultManager.NoteMode.PINNED ->
                    vaultManager.getCurrentPinnedNoteName()?.removeSuffix(".md")
                VaultManager.NoteMode.DAILY -> {
                    val folder = vaultManager.dailyFolder
                    val date = java.time.LocalDate.now()
                        .format(java.time.format.DateTimeFormatter.ofPattern(vaultManager.dateFormat))
                    if (folder.isNotBlank()) "$folder/$date" else date
                }
            }

            if (noteName != null) {
                try {
                    val obsidianUri = android.net.Uri.parse(
                        "obsidian://open?vault=${android.net.Uri.encode(vaultName)}&file=${android.net.Uri.encode(noteName)}"
                    )
                    val deepLinkIntent = Intent(Intent.ACTION_VIEW, obsidianUri).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(deepLinkIntent)
                    return
                } catch (_: Exception) {
                    // Obsidian not installed, fall through
                }
            }
        }

        // Fallback: try launching Obsidian app directly
        try {
            val obsidianIntent = context.packageManager
                .getLaunchIntentForPackage("md.obsidian")
            if (obsidianIntent != null) {
                obsidianIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                context.startActivity(obsidianIntent)
                return
            }
        } catch (_: Exception) {
            // Obsidian not installed
        }

        // Final fallback: open our settings
        val fallbackIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(fallbackIntent)
    }
}
