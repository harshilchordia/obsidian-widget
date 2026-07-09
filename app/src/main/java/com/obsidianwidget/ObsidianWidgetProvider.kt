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
                    if (vaultManager.hapticFeedback) {
                        performHapticClick(context)
                    }
                    vaultManager.toggleChecklistItem(lineIndex)
                    // Lightweight refresh only — never re-inflate flipper (that caused bogus slides)
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
                    val fromTab = vaultManager.activeTab
                    val toTab = if (intent.action == ACTION_TAB_FOCUS) "focus" else "notes"
                    if (fromTab == toTab) return // re-tap: do nothing

                    vaultManager.activeTab = toTab
                    val appWidgetManager = AppWidgetManager.getInstance(context)
                    // Directional slide: Focus→Notes = LTR, Notes→Focus = RTL
                    updateWidget(
                        context,
                        appWidgetManager,
                        widgetId,
                        layoutRes = if (toTab == "notes") {
                            R.layout.widget_layout_slide_ltr
                        } else {
                            R.layout.widget_layout_slide_rtl
                        },
                        animateFromTab = fromTab,
                        animateToTab = toTab
                    )
                }
            }
        }
    }

    private fun performHapticClick(context: Context) {
        try {
            val appContext = context.applicationContext
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                appContext.getSystemService(VibratorManager::class.java)?.defaultVibrator
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
                vibrator.vibrate(
                    effect,
                    VibrationAttributes.Builder()
                        .setUsage(VibrationAttributes.USAGE_TOUCH)
                        .build()
                )
            } else {
                vibrator.vibrate(effect)
            }
        } catch (_: Exception) {
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

    private fun refreshListData(
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        vaultManager: VaultManager
    ) {
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
        applyTodoCount(views, vaultManager)
        appWidgetManager.partiallyUpdateAppWidget(appWidgetId, views)
    }

    private fun applyTodoCount(views: RemoteViews, vaultManager: VaultManager) {
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
    }

    fun updateWidget(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        layoutRes: Int = R.layout.widget_layout,
        animateFromTab: String? = null,
        animateToTab: String? = null
    ) {
        val views = RemoteViews(context.packageName, layoutRes)
        val vaultManager = VaultManager(context, appWidgetId)

        val noteCount =
            if (vaultManager.noteMode == VaultManager.NoteMode.PINNED) vaultManager.getPinnedNoteCount() else 0
        views.setTextViewText(R.id.widget_date, vaultManager.getWidgetTitle())

        val smartTabs = vaultManager.smartTabsEnabled
        val isFocus = vaultManager.activeTab == "focus"
        val colors = vaultManager.getThemeColors()

        // ── Tabs ──────────────────────────────────────────────────────────────
        if (smartTabs) {
            views.setViewVisibility(R.id.widget_tab_bar, View.VISIBLE)
            views.setTextViewText(R.id.widget_tab_focus, vaultManager.taskHeading)
            views.setTextViewText(R.id.widget_tab_notes, vaultManager.notesHeading)
            styleTab(views, R.id.widget_tab_focus, isFocus, colors.accent)
            styleTab(views, R.id.widget_tab_notes, !isFocus, colors.accent)

            views.setOnClickPendingIntent(
                R.id.widget_tab_focus,
                createActionIntent(context, ACTION_TAB_FOCUS, appWidgetId)
            )
            views.setOnClickPendingIntent(
                R.id.widget_tab_notes,
                createActionIntent(context, ACTION_TAB_NOTES, appWidgetId)
            )
        } else {
            views.setViewVisibility(R.id.widget_tab_bar, View.GONE)
        }

        // ── Content ───────────────────────────────────────────────────────────
        val allItems = vaultManager.parseChecklist()
        val hasStructuredItems = allItems.isNotEmpty()
        applyTodoCount(views, vaultManager)

        if (hasStructuredItems || vaultManager.isVaultConfigured || vaultManager.noteMode == VaultManager.NoteMode.PINNED) {
            if (hasStructuredItems || smartTabs) {
                views.setViewVisibility(R.id.widget_note_preview, View.GONE)

                if (smartTabs) {
                    views.setViewVisibility(R.id.widget_checklist, View.GONE)
                    views.setViewVisibility(R.id.widget_tab_flipper, View.VISIBLE)

                    bindListView(context, views, appWidgetId, R.id.widget_checklist_focus, "focus")
                    bindListView(context, views, appWidgetId, R.id.widget_checklist_notes, "notes")

                    /*
                     * Directional slide:
                     * inflate a layout whose ViewFlipper already has the correct
                     * in/out animations, then queue setDisplayedChild(from) then
                     * setDisplayedChild(to). On a fresh inflate the first call is
                     * non-animated (mFirstTime), the second plays the slide.
                     *
                     * Normal refreshes use widget_layout (no flipper anims) and a
                     * single setDisplayedChild so todo toggles never re-slide.
                     */
                    if (animateFromTab != null && animateToTab != null && animateFromTab != animateToTab) {
                        val fromIdx = if (animateFromTab == "focus") 0 else 1
                        val toIdx = if (animateToTab == "focus") 0 else 1
                        views.setDisplayedChild(R.id.widget_tab_flipper, fromIdx)
                        views.setDisplayedChild(R.id.widget_tab_flipper, toIdx)
                    } else {
                        views.setDisplayedChild(R.id.widget_tab_flipper, if (isFocus) 0 else 1)
                    }

                    refreshListData(appWidgetManager, appWidgetId, vaultManager)
                } else {
                    views.setViewVisibility(R.id.widget_tab_flipper, View.GONE)
                    views.setViewVisibility(R.id.widget_checklist, View.VISIBLE)
                    bindListView(context, views, appWidgetId, R.id.widget_checklist, forceTab = null)
                    appWidgetManager.notifyAppWidgetViewDataChanged(appWidgetId, R.id.widget_checklist)
                }
            } else {
                views.setViewVisibility(R.id.widget_checklist, View.GONE)
                views.setViewVisibility(R.id.widget_tab_flipper, View.GONE)
                views.setViewVisibility(R.id.widget_note_preview, View.VISIBLE)
                val noteContent = vaultManager.getDisplayContent()
                val preview = noteContent?.take(500) ?: context.getString(R.string.no_daily_note)
                views.setTextViewText(R.id.widget_note_preview, preview)
            }
        } else {
            views.setViewVisibility(R.id.widget_checklist, View.GONE)
            views.setViewVisibility(R.id.widget_tab_flipper, View.GONE)
            views.setViewVisibility(R.id.widget_note_preview, View.VISIBLE)
            views.setTextViewText(
                R.id.widget_note_preview,
                context.getString(R.string.no_vault_selected)
            )
        }

        // ── Actions ───────────────────────────────────────────────────────────
        views.setOnClickPendingIntent(
            R.id.widget_add,
            createActionIntent(context, ACTION_ADD, appWidgetId)
        )
        views.setOnClickPendingIntent(
            R.id.widget_btn_capture,
            createActionIntent(context, ACTION_CAPTURE, appWidgetId)
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

        views.setViewVisibility(
            R.id.widget_button_bar,
            if (vaultManager.showButtons) View.VISIBLE else View.GONE
        )

        // Empty-space taps refresh quietly (no-anim layout)
        views.setOnClickPendingIntent(
            R.id.widget_root,
            createActionIntent(context, ACTION_REFRESH, appWidgetId)
        )

        // ── Theme ─────────────────────────────────────────────────────────────
        views.setFloat(R.id.widget_root, "setAlpha", vaultManager.widgetAlpha / 100f)
        val isDark = vaultManager.widgetTheme == "dark"
        views.setInt(
            R.id.widget_root, "setBackgroundResource",
            if (isDark) R.drawable.widget_background else R.drawable.widget_background_light
        )
        views.setTextColor(R.id.widget_date, colors.text)
        views.setTextColor(R.id.widget_note_preview, colors.textSecondary)
        views.setTextColor(R.id.widget_todo_count, colors.textSecondary)
        views.setTextColor(R.id.widget_add_label, colors.buttonText)
        views.setTextColor(R.id.widget_capture_label, colors.buttonText)

        views.setInt(R.id.widget_refresh, "setColorFilter", colors.text)
        views.setInt(R.id.widget_settings, "setColorFilter", colors.text)
        views.setInt(R.id.widget_cycle_note, "setColorFilter", colors.text)

        styleActionButton(views, R.id.widget_add, colors.accent)
        styleActionButton(views, R.id.widget_btn_capture, colors.accent)

        appWidgetManager.updateAppWidget(appWidgetId, views)
    }

    private fun styleTab(views: RemoteViews, tabId: Int, active: Boolean, accent: Int) {
        views.setInt(
            tabId, "setBackgroundResource",
            if (active) R.drawable.widget_tab_active else R.drawable.widget_tab_inactive
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val tint = if (active) accent else 0x33FFFFFF
            views.setColorStateList(
                tabId, "setBackgroundTintList",
                ColorStateList.valueOf(tint)
            )
        } else if (active) {
            views.setInt(tabId, "setBackgroundColor", accent)
        }
    }

    private fun styleActionButton(views: RemoteViews, buttonId: Int, accent: Int) {
        views.setInt(buttonId, "setBackgroundResource", R.drawable.widget_button_bg)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            views.setColorStateList(
                buttonId, "setBackgroundTintList",
                ColorStateList.valueOf(accent)
            )
        } else {
            views.setInt(buttonId, "setBackgroundColor", accent)
        }
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
        // Unique request codes per action so PendingIntents don't collide
        val requestCode = (action.hashCode() xor (appWidgetId * 17))
        return PendingIntent.getBroadcast(
            context, requestCode, intent,
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
