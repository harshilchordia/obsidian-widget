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
        const val EXTRA_LINE_INDEX = "extra_line_index"

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

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)

        when (intent.action) {
            ACTION_REFRESH -> updateAllWidgets(context)
            ACTION_CAPTURE -> {
                val captureIntent = Intent(context, QuickCaptureActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                }
                context.startActivity(captureIntent)
            }
            ACTION_OPEN -> openObsidian(context)
            ACTION_TOGGLE -> {
                val lineIndex = intent.getIntExtra(EXTRA_LINE_INDEX, -1)
                if (lineIndex >= 0) {
                    val vaultManager = VaultManager(context)
                    vaultManager.toggleChecklistItem(lineIndex)
                    // Notify the widget data changed so ListView refreshes
                    val appWidgetManager = AppWidgetManager.getInstance(context)
                    val widgetIds = appWidgetManager.getAppWidgetIds(
                        ComponentName(context, ObsidianWidgetProvider::class.java)
                    )
                    appWidgetManager.notifyAppWidgetViewDataChanged(widgetIds, R.id.widget_checklist)
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
        val vaultManager = VaultManager(context)

        // Set title based on mode
        views.setTextViewText(R.id.widget_date, vaultManager.getWidgetTitle())

        // Check if note has checklist items
        val hasChecklist = vaultManager.parseChecklist().isNotEmpty()

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
            }
            val togglePendingIntent = PendingIntent.getBroadcast(
                context, 0, toggleIntent,
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

        // Refresh button
        views.setOnClickPendingIntent(
            R.id.widget_refresh,
            createActionIntent(context, ACTION_REFRESH)
        )

        // Quick capture button
        views.setOnClickPendingIntent(
            R.id.widget_btn_capture,
            createActionIntent(context, ACTION_CAPTURE)
        )

        // Open Obsidian button
        views.setOnClickPendingIntent(
            R.id.widget_btn_open,
            createActionIntent(context, ACTION_OPEN)
        )

        appWidgetManager.updateAppWidget(appWidgetId, views)
    }

    private fun createActionIntent(context: Context, action: String): PendingIntent {
        val intent = Intent(context, ObsidianWidgetProvider::class.java).apply {
            this.action = action
        }
        return PendingIntent.getBroadcast(
            context, action.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun openObsidian(context: Context) {
        val vaultManager = VaultManager(context)
        val vaultName = vaultManager.vaultName

        // Try to open the specific note in Obsidian via its URI scheme
        if (vaultName != null) {
            val noteName = when (vaultManager.noteMode) {
                VaultManager.NoteMode.PINNED ->
                    vaultManager.pinnedNoteName?.removeSuffix(".md")
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
