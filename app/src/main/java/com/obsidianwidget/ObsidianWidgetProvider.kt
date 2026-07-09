package com.obsidianwidget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.DocumentsContract
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
        private const val ACTION_TAB_FOCUS = "com.obsidianwidget.ACTION_TAB_FOCUS"
        private const val ACTION_TAB_NOTES = "com.obsidianwidget.ACTION_TAB_NOTES"
        const val EXTRA_LINE_INDEX = "extra_line_index"
        const val EXTRA_APPEND_TO_WIDGET = "extra_append_to_widget"
        const val EXTRA_WIDGET_ID = "extra_widget_id"
        const val EXTRA_URL = "extra_url"

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

        /** Refresh only widgets that have Auto Sync enabled (used on screen unlock). */
        fun updateAutoSyncWidgets(context: Context) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val widgetIds = appWidgetManager.getAppWidgetIds(
                ComponentName(context, ObsidianWidgetProvider::class.java)
            )
            val provider = ObsidianWidgetProvider()
            for (id in widgetIds) {
                if (VaultManager(context, id).autoSync) {
                    provider.updateWidget(context, appWidgetManager, id)
                }
            }
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
            ACTION_REFRESH -> {
                val widgetId = intent.getIntExtra(EXTRA_WIDGET_ID, -1)
                val appWidgetManager = AppWidgetManager.getInstance(context)
                if (widgetId >= 0) {
                    updateWidget(context, appWidgetManager, widgetId)
                } else {
                    updateAllWidgets(context)
                }
            }
            ACTION_CAPTURE -> {
                val widgetId = intent.getIntExtra(EXTRA_WIDGET_ID, -1)
                val captureIntent = Intent(context, QuickCaptureActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    putExtra(EXTRA_WIDGET_ID, widgetId)
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
                val url = intent.getStringExtra(EXTRA_URL)
                if (!url.isNullOrEmpty()) {
                    try {
                        val browseIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        context.startActivity(browseIntent)
                    } catch (_: Exception) { }
                    return
                }
                val lineIndex = intent.getIntExtra(EXTRA_LINE_INDEX, -1)
                val widgetId = intent.getIntExtra(EXTRA_WIDGET_ID, -1)
                if (lineIndex >= 0 && widgetId >= 0) {
                    val vaultManager = VaultManager(context, widgetId)

                    // Haptic first — before any slow file I/O — so it always feels immediate
                    if (vaultManager.hapticFeedback) {
                        performHapticClick(context)
                    }

                    vaultManager.toggleChecklistItem(lineIndex)

                    // Lightweight refresh: only rebind list data + todo count.
                    // A full updateWidget rebuild used to re-trigger tab flipper animations.
                    val appWidgetManager = AppWidgetManager.getInstance(context)
                    refreshListData(appWidgetManager, widgetId, vaultManager)
                    updateTodoCountPartial(context, appWidgetManager, widgetId, vaultManager)
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
            ACTION_TAB_FOCUS, ACTION_TAB_NOTES -> {
                val widgetId = intent.getIntExtra(EXTRA_WIDGET_ID, -1)
                if (widgetId >= 0) {
                    val vaultManager = VaultManager(context, widgetId)
                    val target = if (intent.action == ACTION_TAB_FOCUS) "focus" else "notes"
                    // Ignore re-taps on the already-active tab (prevents flicker)
                    if (vaultManager.activeTab == target) return

                    vaultManager.activeTab = target
                    val appWidgetManager = AppWidgetManager.getInstance(context)
                    applyTabSwitch(context, appWidgetManager, widgetId, vaultManager)
                }
            }
        }
    }

    /**
     * Crisp haptic click. Uses USAGE_TOUCH on API 33+ (required on many OEMs like
     * Xiaomi/HyperOS for widget/background vibration), with one-shot fallbacks.
     */
    private fun performHapticClick(context: Context) {
        try {
            val appContext = context.applicationContext
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val manager = appContext.getSystemService(VibratorManager::class.java)
                manager?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                appContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            } ?: return

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !vibrator.hasVibrator()) return

            val effect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
                } catch (_: Exception) {
                    VibrationEffect.createOneShot(18, VibrationEffect.DEFAULT_AMPLITUDE)
                }
            } else {
                VibrationEffect.createOneShot(18, VibrationEffect.DEFAULT_AMPLITUDE)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val attrs = VibrationAttributes.Builder()
                    .setUsage(VibrationAttributes.USAGE_TOUCH)
                    .build()
                vibrator.vibrate(effect, attrs)
            } else {
                vibrator.vibrate(effect)
            }
        } catch (_: Exception) {
            // Last-resort short pulse for picky OEMs
            try {
                @Suppress("DEPRECATION")
                val v = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    v.vibrate(VibrationEffect.createOneShot(25, 180))
                } else {
                    @Suppress("DEPRECATION")
                    v.vibrate(25)
                }
            } catch (_: Exception) { }
        }
    }

    private fun refreshListData(appWidgetManager: AppWidgetManager, appWidgetId: Int, vaultManager: VaultManager) {
        if (vaultManager.smartTabsEnabled) {
            appWidgetManager.notifyAppWidgetViewDataChanged(appWidgetId, R.id.widget_checklist_focus)
            appWidgetManager.notifyAppWidgetViewDataChanged(appWidgetId, R.id.widget_checklist_notes)
        } else {
            appWidgetManager.notifyAppWidgetViewDataChanged(appWidgetId, R.id.widget_checklist)
        }
    }

    private fun updateTodoCountPartial(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        vaultManager: VaultManager
    ) {
        val views = RemoteViews(context.packageName, R.layout.widget_layout)
        val items = vaultManager.parseChecklist()
        val hasChecklist = items.any { !it.isPlainText }
        if (vaultManager.showTodoCount && hasChecklist) {
            val unchecked = items.count { !it.isPlainText && !it.isChecked }
            val total = items.count { !it.isPlainText }
            views.setTextViewText(R.id.widget_todo_count, "$unchecked of $total remaining")
            views.setViewVisibility(R.id.widget_todo_count, View.VISIBLE)
        } else {
            views.setViewVisibility(R.id.widget_todo_count, View.GONE)
        }
        appWidgetManager.partiallyUpdateAppWidget(appWidgetId, views)
    }

    /**
     * Switch visible list + tab chrome without rebuilding the whole widget.
     * No ViewFlipper / slide animation — instant visibility toggle only.
     */
    private fun applyTabSwitch(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        vaultManager: VaultManager
    ) {
        val views = RemoteViews(context.packageName, R.layout.widget_layout)
        val accentColor = vaultManager.getThemeColors().accent
        val inactiveColor = 0x33FFFFFF.toInt()
        val isFocus = vaultManager.activeTab == "focus"

        views.setInt(
            R.id.widget_tab_focus, "setBackgroundColor",
            if (isFocus) accentColor else inactiveColor
        )
        views.setInt(
            R.id.widget_tab_notes, "setBackgroundColor",
            if (!isFocus) accentColor else inactiveColor
        )

        views.setViewVisibility(R.id.widget_checklist, View.GONE)
        views.setViewVisibility(R.id.widget_checklist_focus, if (isFocus) View.VISIBLE else View.GONE)
        views.setViewVisibility(R.id.widget_checklist_notes, if (isFocus) View.GONE else View.VISIBLE)

        // TODO count for the newly active tab
        val items = vaultManager.parseChecklist()
        val hasChecklist = items.any { !it.isPlainText }
        if (vaultManager.showTodoCount && hasChecklist) {
            val unchecked = items.count { !it.isPlainText && !it.isChecked }
            val total = items.count { !it.isPlainText }
            views.setTextViewText(R.id.widget_todo_count, "$unchecked of $total remaining")
            views.setViewVisibility(R.id.widget_todo_count, View.VISIBLE)
        } else {
            views.setViewVisibility(R.id.widget_todo_count, View.GONE)
        }

        appWidgetManager.partiallyUpdateAppWidget(appWidgetId, views)
    }

    fun updateWidget(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int
    ) {
        val views = RemoteViews(context.packageName, R.layout.widget_layout)
        val vaultManager = VaultManager(context, appWidgetId)

        val noteCount = if (vaultManager.noteMode == VaultManager.NoteMode.PINNED) vaultManager.getPinnedNoteCount() else 0
        views.setTextViewText(R.id.widget_date, vaultManager.getWidgetTitle())

        // ── Smart Daily Tabs ───────────────────────────────────────────────────
        val smartTabs = vaultManager.smartTabsEnabled
        val isFocus = vaultManager.activeTab == "focus"
        if (smartTabs) {
            views.setViewVisibility(R.id.widget_tab_bar, View.VISIBLE)

            val accentColor = vaultManager.getThemeColors().accent
            val inactiveColor = 0x33FFFFFF.toInt()

            views.setInt(
                R.id.widget_tab_focus, "setBackgroundColor",
                if (isFocus) accentColor else inactiveColor
            )
            views.setInt(
                R.id.widget_tab_notes, "setBackgroundColor",
                if (!isFocus) accentColor else inactiveColor
            )
            views.setTextViewText(R.id.widget_tab_focus, vaultManager.taskHeading)
            views.setTextViewText(R.id.widget_tab_notes, vaultManager.notesHeading)

            val focusIntent = PendingIntent.getBroadcast(
                context, appWidgetId * 100 + 10,
                Intent(context, ObsidianWidgetProvider::class.java).apply {
                    action = ACTION_TAB_FOCUS
                    putExtra(EXTRA_WIDGET_ID, appWidgetId)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notesIntent = PendingIntent.getBroadcast(
                context, appWidgetId * 100 + 11,
                Intent(context, ObsidianWidgetProvider::class.java).apply {
                    action = ACTION_TAB_NOTES
                    putExtra(EXTRA_WIDGET_ID, appWidgetId)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_tab_focus, focusIntent)
            views.setOnClickPendingIntent(R.id.widget_tab_notes, notesIntent)
        } else {
            views.setViewVisibility(R.id.widget_tab_bar, View.GONE)
        }

        // ── Content ────────────────────────────────────────────────────────
        val allItems = vaultManager.parseChecklist()
        val hasStructuredItems = allItems.isNotEmpty()
        val hasChecklist = allItems.any { !it.isPlainText }

        if (vaultManager.showTodoCount && hasChecklist) {
            val unchecked = allItems.count { !it.isPlainText && !it.isChecked }
            val total = allItems.count { !it.isPlainText }
            views.setTextViewText(R.id.widget_todo_count, "$unchecked of $total remaining")
            views.setViewVisibility(R.id.widget_todo_count, View.VISIBLE)
        } else {
            views.setViewVisibility(R.id.widget_todo_count, View.GONE)
        }

        if (hasStructuredItems || (vaultManager.isVaultConfigured || vaultManager.noteMode == VaultManager.NoteMode.PINNED)) {
            if (hasStructuredItems || smartTabs) {
                views.setViewVisibility(R.id.widget_note_preview, View.GONE)

                if (smartTabs) {
                    views.setViewVisibility(R.id.widget_checklist, View.GONE)
                    views.setViewVisibility(
                        R.id.widget_checklist_focus,
                        if (isFocus) View.VISIBLE else View.GONE
                    )
                    views.setViewVisibility(
                        R.id.widget_checklist_notes,
                        if (isFocus) View.GONE else View.VISIBLE
                    )

                    bindListView(context, views, appWidgetId, R.id.widget_checklist_focus, "focus")
                    bindListView(context, views, appWidgetId, R.id.widget_checklist_notes, "notes")
                    refreshListData(appWidgetManager, appWidgetId, vaultManager)
                } else {
                    views.setViewVisibility(R.id.widget_checklist, View.VISIBLE)
                    views.setViewVisibility(R.id.widget_checklist_focus, View.GONE)
                    views.setViewVisibility(R.id.widget_checklist_notes, View.GONE)

                    bindListView(context, views, appWidgetId, R.id.widget_checklist, forceTab = null)
                    appWidgetManager.notifyAppWidgetViewDataChanged(appWidgetId, R.id.widget_checklist)
                }
            } else {
                views.setViewVisibility(R.id.widget_checklist, View.GONE)
                views.setViewVisibility(R.id.widget_checklist_focus, View.GONE)
                views.setViewVisibility(R.id.widget_checklist_notes, View.GONE)
                views.setViewVisibility(R.id.widget_note_preview, View.VISIBLE)
                val noteContent = vaultManager.getDisplayContent()
                val preview = noteContent?.take(500) ?: context.getString(R.string.no_daily_note)
                views.setTextViewText(R.id.widget_note_preview, preview)
            }
        } else {
            views.setViewVisibility(R.id.widget_checklist, View.GONE)
            views.setViewVisibility(R.id.widget_checklist_focus, View.GONE)
            views.setViewVisibility(R.id.widget_checklist_notes, View.GONE)
            views.setViewVisibility(R.id.widget_note_preview, View.VISIBLE)
            views.setTextViewText(
                R.id.widget_note_preview,
                context.getString(R.string.no_vault_selected)
            )
        }

        views.setOnClickPendingIntent(
            R.id.widget_add,
            createActionIntent(context, ACTION_ADD, appWidgetId)
        )

        views.setOnClickPendingIntent(
            R.id.widget_date,
            createActionIntent(context, ACTION_OPEN, appWidgetId)
        )

        if (noteCount > 1) {
            views.setViewVisibility(R.id.widget_cycle_note, View.VISIBLE)
            views.setOnClickPendingIntent(
                R.id.widget_cycle_note,
                createActionIntent(context, ACTION_NAV_RIGHT, appWidgetId)
            )
        } else {
            views.setViewVisibility(R.id.widget_cycle_note, View.GONE)
        }

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

        if (vaultManager.autoSync) {
            views.setViewVisibility(R.id.widget_refresh, View.GONE)
        } else {
            views.setViewVisibility(R.id.widget_refresh, View.VISIBLE)
            views.setOnClickPendingIntent(
                R.id.widget_refresh,
                createActionIntent(context, ACTION_REFRESH, appWidgetId)
            )
        }

        views.setOnClickPendingIntent(
            R.id.widget_btn_capture,
            createActionIntent(context, ACTION_CAPTURE, appWidgetId)
        )

        views.setViewVisibility(
            R.id.widget_button_bar,
            if (vaultManager.showButtons) View.VISIBLE else View.GONE
        )

        // Absorb empty taps (no full rebuild side-effects from flipper anymore)
        views.setOnClickPendingIntent(
            R.id.widget_root,
            createActionIntent(context, ACTION_REFRESH, appWidgetId)
        )

        views.setFloat(R.id.widget_root, "setAlpha", vaultManager.widgetAlpha / 100f)

        val colors = vaultManager.getThemeColors()
        val isDark = vaultManager.widgetTheme == "dark"
        views.setInt(
            R.id.widget_root, "setBackgroundResource",
            if (isDark) R.drawable.widget_background else R.drawable.widget_background_light
        )
        views.setTextColor(R.id.widget_date, colors.text)
        views.setTextColor(R.id.widget_note_preview, colors.textSecondary)
        views.setTextColor(R.id.widget_todo_count, colors.textSecondary)

        views.setInt(R.id.widget_refresh, "setColorFilter", colors.text)
        views.setInt(R.id.widget_settings, "setColorFilter", colors.text)
        views.setInt(R.id.widget_cycle_note, "setColorFilter", colors.text)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val accentTint = ColorStateList.valueOf(colors.accent)
            views.setColorStateList(R.id.widget_btn_capture, "setBackgroundTintList", accentTint)
            views.setColorStateList(R.id.widget_add, "setBackgroundTintList", accentTint)
        } else {
            views.setInt(R.id.widget_btn_capture, "setBackgroundColor", colors.accent)
            views.setInt(R.id.widget_add, "setBackgroundColor", colors.accent)
        }

        appWidgetManager.updateAppWidget(appWidgetId, views)
    }

    private fun bindListView(
        context: Context,
        views: RemoteViews,
        appWidgetId: Int,
        listViewId: Int,
        forceTab: String?
    ) {
        val serviceIntent = Intent(context, ChecklistWidgetService::class.java).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            if (forceTab != null) {
                putExtra(ChecklistWidgetService.EXTRA_FORCE_TAB, forceTab)
            }
            data = Uri.parse("content://com.obsidianwidget.checklist/$appWidgetId/${forceTab ?: "all"}")
        }
        views.setRemoteAdapter(listViewId, serviceIntent)

        val toggleIntent = Intent(context, ObsidianWidgetProvider::class.java).apply {
            action = ACTION_TOGGLE
            putExtra(EXTRA_WIDGET_ID, appWidgetId)
        }
        val requestCode = appWidgetId * 31 + listViewId
        val togglePendingIntent = PendingIntent.getBroadcast(
            context, requestCode, toggleIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
        views.setPendingIntentTemplate(listViewId, togglePendingIntent)
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
        val vaultUri = vaultManager.vaultUri

        if (vaultName != null) {
            val noteName = when (vaultManager.noteMode) {
                VaultManager.NoteMode.PINNED ->
                    resolvePinnedNotePath(vaultUri, vaultManager)
                VaultManager.NoteMode.DAILY -> {
                    val folder = vaultManager.dailyFolder
                    val date = java.time.LocalDate.now()
                        .format(java.time.format.DateTimeFormatter.ofPattern(vaultManager.dateFormat))
                    if (folder.isNotBlank()) "$folder/$date" else date
                }
            }

            if (noteName != null) {
                try {
                    val obsidianUri = Uri.Builder()
                        .scheme("obsidian")
                        .authority("open")
                        .appendQueryParameter("vault", vaultName)
                        .appendQueryParameter("file", noteName)
                        .build()
                    val deepLinkIntent = Intent(Intent.ACTION_VIEW, obsidianUri).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                Intent.FLAG_ACTIVITY_SINGLE_TOP
                    }
                    context.startActivity(deepLinkIntent)
                    return
                } catch (_: Exception) { }
            }
        }

        try {
            val obsidianIntent = context.packageManager
                .getLaunchIntentForPackage("md.obsidian")
            if (obsidianIntent != null) {
                obsidianIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                context.startActivity(obsidianIntent)
                return
            }
        } catch (_: Exception) { }

        val fallbackIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(fallbackIntent)
    }

    private fun resolvePinnedNotePath(vaultUri: Uri?, vaultManager: VaultManager): String? {
        val fallbackName = vaultManager.getCurrentPinnedNoteName()?.removeSuffix(".md")
        val noteUri = vaultManager.getCurrentPinnedNoteUri() ?: return fallbackName
        val rootUri = vaultUri ?: return fallbackName

        return try {
            val treeId = DocumentsContract.getTreeDocumentId(rootUri)
            val docId = DocumentsContract.getDocumentId(noteUri)

            val relativePath = when {
                docId.startsWith("$treeId/") -> docId.removePrefix("$treeId/")
                docId == treeId -> ""
                ':' in docId -> docId.substringAfter(':')
                else -> docId
            }

            relativePath
                .trim('/')
                .takeIf { it.isNotEmpty() }
                ?.removeSuffix(".md")
                ?: fallbackName
        } catch (_: Exception) {
            fallbackName
        }
    }
}
