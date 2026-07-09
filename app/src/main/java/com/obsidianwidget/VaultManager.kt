package com.obsidianwidget

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Manages reading/writing to the Obsidian vault via SAF (Storage Access Framework).
 */
class VaultManager(private val context: Context, private val widgetId: Int = -1) {

    enum class NoteMode { DAILY, PINNED }

    data class ChecklistItem(
        val lineIndex: Int,
        val text: String,
        val isChecked: Boolean,
        val isPlainText: Boolean = false,
        val isHeading: Boolean = false,
        val isBullet: Boolean = false,
        val indentLevel: Int = 0
    )

    companion object {
        private const val PREFS_NAME = "obsidian_widget_prefs"
        // Global keys (shared across all widgets)
        private const val KEY_VAULT_URI = "vault_uri"
        private const val KEY_VAULT_NAME = "vault_name"
        // Per-widget keys (suffixed with _widgetId)
        private const val KEY_DAILY_FOLDER = "daily_folder"
        private const val KEY_DATE_FORMAT = "date_format"
        private const val KEY_NOTE_MODE = "note_mode"
        private const val KEY_PINNED_NOTE_URI = "pinned_note_uri"
        private const val KEY_PINNED_NOTE_NAME = "pinned_note_name"
        private const val KEY_SHOW_BUTTONS = "show_buttons"
        private const val KEY_SORT_UNCHECKED = "sort_unchecked"
        private const val KEY_PINNED_NOTE_URIS = "pinned_note_uris"
        private const val KEY_PINNED_NOTE_NAMES = "pinned_note_names"
        private const val KEY_CURRENT_NOTE_INDEX = "current_note_index"
        private const val KEY_WIDGET_ALPHA = "widget_alpha"
        private const val KEY_TAP_CHECKBOX_ONLY = "tap_checkbox_only"
        private const val KEY_ADD_TO_TOP = "add_to_top"
        private const val KEY_SHOW_ADD_TO_TOP = "show_add_to_top"
        private const val KEY_WIDGET_THEME = "widget_theme"
        private const val KEY_ACCENT_COLOR = "accent_color"
        private const val KEY_SHOW_TODO_COUNT = "show_todo_count"
        private const val KEY_HIDE_FRONTMATTER = "hide_frontmatter"
        private const val KEY_START_AFTER_HEADING = "start_after_heading"
        // Smart Daily Tabs / Capture keys
        private const val KEY_SMART_TABS = "smart_tabs"
        private const val KEY_SMART_CAPTURE = "smart_capture"
        private const val KEY_TASK_HEADING = "task_heading"
        private const val KEY_NOTES_HEADING = "notes_heading"
        private const val KEY_TIMESTAMP_FORMAT = "timestamp_format"
        private const val KEY_CREATE_MISSING_HEADING = "create_missing_heading"
        private const val KEY_ACTIVE_TAB = "active_tab"
        private const val KEY_DEFAULT_TAB = "default_tab"
        private const val KEY_HAPTIC_FEEDBACK = "haptic_feedback"
        private const val KEY_AUTO_SYNC = "auto_sync"
        private const val DEFAULT_DATE_FORMAT = "yyyy-MM-dd"
        private const val DEFAULT_TASK_HEADING = "Fokus"
        private const val DEFAULT_NOTES_HEADING = "Notes"

        private val CHECKLIST_REGEX = Regex("""^(\s*)-\s*\[([ xX])\]\s*(.*)$""")
        private val HEADING_REGEX = Regex("""^(#{1,6})\s+(.+)$""")
        private val BULLET_REGEX = Regex("""^(\s*)[*+-]\s+(.+)$""")

        fun deleteWidgetPrefs(context: Context, widgetId: Int) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit()
                .remove("${KEY_DAILY_FOLDER}_$widgetId")
                .remove("${KEY_DATE_FORMAT}_$widgetId")
                .remove("${KEY_NOTE_MODE}_$widgetId")
                .remove("${KEY_PINNED_NOTE_URI}_$widgetId")
                .remove("${KEY_PINNED_NOTE_NAME}_$widgetId")
                .remove("${KEY_SHOW_BUTTONS}_$widgetId")
                .remove("${KEY_SORT_UNCHECKED}_$widgetId")
                .remove("${KEY_PINNED_NOTE_URIS}_$widgetId")
                .remove("${KEY_PINNED_NOTE_NAMES}_$widgetId")
                .remove("${KEY_CURRENT_NOTE_INDEX}_$widgetId")
                .remove("${KEY_WIDGET_ALPHA}_$widgetId")
                .remove("${KEY_TAP_CHECKBOX_ONLY}_$widgetId")
                .remove("${KEY_ADD_TO_TOP}_$widgetId")
                .remove("${KEY_SHOW_ADD_TO_TOP}_$widgetId")
                .remove("${KEY_WIDGET_THEME}_$widgetId")
                .remove("${KEY_ACCENT_COLOR}_$widgetId")
                .remove("${KEY_SHOW_TODO_COUNT}_$widgetId")
                .remove("${KEY_HIDE_FRONTMATTER}_$widgetId")
                .remove("${KEY_START_AFTER_HEADING}_$widgetId")
                .remove("${KEY_SMART_TABS}_$widgetId")
                .remove("${KEY_SMART_CAPTURE}_$widgetId")
                .remove("${KEY_TASK_HEADING}_$widgetId")
                .remove("${KEY_NOTES_HEADING}_$widgetId")
                .remove("${KEY_TIMESTAMP_FORMAT}_$widgetId")
                .remove("${KEY_CREATE_MISSING_HEADING}_$widgetId")
                .remove("${KEY_ACTIVE_TAB}_$widgetId")
                .remove("${KEY_DEFAULT_TAB}_$widgetId")
                .remove("${KEY_HAPTIC_FEEDBACK}_$widgetId")
                .remove("${KEY_AUTO_SYNC}_$widgetId")
                .apply()
        }

        /**
         * Returns the line index (inclusive) of the closing '---' of YAML frontmatter,
         * or -1 if no frontmatter is present.
         * Lines up to and including this index should be skipped during display parsing.
         * We deliberately do NOT strip the content string so that raw lineIndex values
         * stored in ChecklistItem still map directly to real file line numbers.
         */
        fun findFrontmatterEndLine(lines: List<String>): Int {
            if (lines.isEmpty() || lines[0].trim() != "---") return -1
            for (i in 1 until lines.size) {
                if (lines[i].trim() == "---") return i
                // A blank line before closing --- means no frontmatter (Obsidian rule)
                // but we keep scanning — some templates have blank lines inside frontmatter
            }
            // Opening --- found but no closing --- — treat entire rest as frontmatter
            return lines.size - 1
        }
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun wk(key: String): String =
        if (widgetId >= 0) "${key}_$widgetId" else key

    // Global settings (same for all widgets)
    var vaultUri: Uri?
        get() = prefs.getString(KEY_VAULT_URI, null)?.let { Uri.parse(it) }
        set(value) = prefs.edit().putString(KEY_VAULT_URI, value?.toString()).apply()

    var vaultName: String?
        get() = prefs.getString(KEY_VAULT_NAME, null)
        set(value) = prefs.edit().putString(KEY_VAULT_NAME, value).apply()

    // Per-widget settings
    var dailyFolder: String
        get() = prefs.getString(wk(KEY_DAILY_FOLDER), prefs.getString(KEY_DAILY_FOLDER, "") ?: "") ?: ""
        set(value) = prefs.edit().putString(wk(KEY_DAILY_FOLDER), value).apply()

    var dateFormat: String
        get() = prefs.getString(wk(KEY_DATE_FORMAT), prefs.getString(KEY_DATE_FORMAT, DEFAULT_DATE_FORMAT) ?: DEFAULT_DATE_FORMAT) ?: DEFAULT_DATE_FORMAT
        set(value) = prefs.edit().putString(wk(KEY_DATE_FORMAT), value).apply()

    var noteMode: NoteMode
        get() = if (prefs.getString(wk(KEY_NOTE_MODE), prefs.getString(KEY_NOTE_MODE, "DAILY")) == "PINNED") NoteMode.PINNED else NoteMode.DAILY
        set(value) = prefs.edit().putString(wk(KEY_NOTE_MODE), value.name).apply()

    var pinnedNoteUri: Uri?
        get() = prefs.getString(wk(KEY_PINNED_NOTE_URI), prefs.getString(KEY_PINNED_NOTE_URI, null))?.let { Uri.parse(it) }
        set(value) = prefs.edit().putString(wk(KEY_PINNED_NOTE_URI), value?.toString()).apply()

    var pinnedNoteName: String?
        get() = prefs.getString(wk(KEY_PINNED_NOTE_NAME), prefs.getString(KEY_PINNED_NOTE_NAME, null))
        set(value) = prefs.edit().putString(wk(KEY_PINNED_NOTE_NAME), value).apply()

    var pinnedNoteUriList: List<String>
        get() {
            val list = prefs.getString(wk(KEY_PINNED_NOTE_URIS), null)
            if (!list.isNullOrEmpty()) return list.split("|||").filter { it.isNotEmpty() }
            val single = prefs.getString(wk(KEY_PINNED_NOTE_URI), prefs.getString(KEY_PINNED_NOTE_URI, null))
            return if (single != null) listOf(single) else emptyList()
        }
        set(value) { prefs.edit().putString(wk(KEY_PINNED_NOTE_URIS), value.joinToString("|||")).commit() }

    var pinnedNoteNameList: List<String>
        get() {
            val list = prefs.getString(wk(KEY_PINNED_NOTE_NAMES), null)
            if (!list.isNullOrEmpty()) return list.split("|||").filter { it.isNotEmpty() }
            val single = prefs.getString(wk(KEY_PINNED_NOTE_NAME), prefs.getString(KEY_PINNED_NOTE_NAME, null))
            return if (single != null) listOf(single) else emptyList()
        }
        set(value) { prefs.edit().putString(wk(KEY_PINNED_NOTE_NAMES), value.joinToString("|||")).commit() }

    var currentNoteIndex: Int
        get() = prefs.getInt(wk(KEY_CURRENT_NOTE_INDEX), 0)
        set(value) { prefs.edit().putInt(wk(KEY_CURRENT_NOTE_INDEX), value).commit() }

    fun addPinnedNote(uri: Uri, name: String) {
        val uris = pinnedNoteUriList.toMutableList()
        val names = pinnedNoteNameList.toMutableList()
        uris.add(uri.toString())
        names.add(name)
        pinnedNoteUriList = uris
        pinnedNoteNameList = names
    }

    fun removePinnedNote(index: Int) {
        val uris = pinnedNoteUriList.toMutableList()
        val names = pinnedNoteNameList.toMutableList()
        if (index in uris.indices) {
            uris.removeAt(index)
            names.removeAt(index)
            pinnedNoteUriList = uris
            pinnedNoteNameList = names
            if (currentNoteIndex >= uris.size) {
                currentNoteIndex = (uris.size - 1).coerceAtLeast(0)
            }
        }
    }

    fun getPinnedNoteCount(): Int = pinnedNoteUriList.size

    fun navigateNote(direction: Int) {
        val count = getPinnedNoteCount()
        if (count <= 1) return
        var newIndex = currentNoteIndex + direction
        if (newIndex < 0) newIndex = count - 1
        if (newIndex >= count) newIndex = 0
        currentNoteIndex = newIndex
    }

    fun getCurrentPinnedNoteUri(): Uri? {
        val uris = pinnedNoteUriList
        if (uris.isEmpty()) return null
        val idx = currentNoteIndex.coerceIn(0, uris.lastIndex)
        return Uri.parse(uris[idx])
    }

    fun getCurrentPinnedNoteName(): String? {
        val names = pinnedNoteNameList
        if (names.isEmpty()) return null
        val idx = currentNoteIndex.coerceIn(0, names.lastIndex)
        return names[idx]
    }

    var showButtons: Boolean
        get() = prefs.getBoolean(wk(KEY_SHOW_BUTTONS), prefs.getBoolean(KEY_SHOW_BUTTONS, true))
        set(value) = prefs.edit().putBoolean(wk(KEY_SHOW_BUTTONS), value).apply()

    var sortUnchecked: Boolean
        get() = prefs.getBoolean(wk(KEY_SORT_UNCHECKED), prefs.getBoolean(KEY_SORT_UNCHECKED, false))
        set(value) = prefs.edit().putBoolean(wk(KEY_SORT_UNCHECKED), value).apply()

    var widgetAlpha: Int
        get() = prefs.getInt(wk(KEY_WIDGET_ALPHA), 100)
        set(value) = prefs.edit().putInt(wk(KEY_WIDGET_ALPHA), value).apply()

    var tapCheckboxOnly: Boolean
        get() = prefs.getBoolean(wk(KEY_TAP_CHECKBOX_ONLY), false)
        set(value) = prefs.edit().putBoolean(wk(KEY_TAP_CHECKBOX_ONLY), value).apply()

    var addToTop: Boolean
        get() = prefs.getBoolean(wk(KEY_ADD_TO_TOP), false)
        set(value) = prefs.edit().putBoolean(wk(KEY_ADD_TO_TOP), value).apply()

    var showAddToTop: Boolean
        get() = prefs.getBoolean(wk(KEY_SHOW_ADD_TO_TOP), true)
        set(value) = prefs.edit().putBoolean(wk(KEY_SHOW_ADD_TO_TOP), value).apply()

    var widgetTheme: String
        get() = prefs.getString(wk(KEY_WIDGET_THEME), "dark") ?: "dark"
        set(value) = prefs.edit().putString(wk(KEY_WIDGET_THEME), value).apply()

    var accentColor: String
        get() = prefs.getString(wk(KEY_ACCENT_COLOR), "#D97757") ?: "#D97757"
        set(value) = prefs.edit().putString(wk(KEY_ACCENT_COLOR), value).apply()

    var showTodoCount: Boolean
        get() = prefs.getBoolean(wk(KEY_SHOW_TODO_COUNT), false)
        set(value) = prefs.edit().putBoolean(wk(KEY_SHOW_TODO_COUNT), value).apply()

    /**
     * When enabled, YAML frontmatter (--- ... ---) is hidden in the widget.
     * Defaults to true — most users want this.
     */
    var hideFrontmatter: Boolean
        get() = prefs.getBoolean(wk(KEY_HIDE_FRONTMATTER), true)
        set(value) = prefs.edit().putBoolean(wk(KEY_HIDE_FRONTMATTER), value).apply()

    /**
     * When enabled, content before (and including) the first heading is skipped.
     * Useful for notes with a metadata/property section before the actual content.
     */
    var startAfterFirstHeading: Boolean
        get() = prefs.getBoolean(wk(KEY_START_AFTER_HEADING), false)
        set(value) = prefs.edit().putBoolean(wk(KEY_START_AFTER_HEADING), value).apply()

    // ── Smart Daily Tabs ────────────────────────────────────────────────────────

    /** Show Focus / Notes tab bar in widget. Off by default — safe for existing users. */
    var smartTabsEnabled: Boolean
        get() = prefs.getBoolean(wk(KEY_SMART_TABS), false)
        set(value) = prefs.edit().putBoolean(wk(KEY_SMART_TABS), value).apply()

    /** When on, capture input is routed to the correct heading instead of plain append. Off by default. */
    var smartCaptureEnabled: Boolean
        get() = prefs.getBoolean(wk(KEY_SMART_CAPTURE), false)
        set(value) = prefs.edit().putBoolean(wk(KEY_SMART_CAPTURE), value).apply()

    /** Heading name for the task/focus section. Default: "Fokus" */
    var taskHeading: String
        get() = prefs.getString(wk(KEY_TASK_HEADING), DEFAULT_TASK_HEADING) ?: DEFAULT_TASK_HEADING
        set(value) = prefs.edit().putString(wk(KEY_TASK_HEADING), value).apply()

    /** Heading name for the notes/log section. Default: "Notes" */
    var notesHeading: String
        get() = prefs.getString(wk(KEY_NOTES_HEADING), DEFAULT_NOTES_HEADING) ?: DEFAULT_NOTES_HEADING
        set(value) = prefs.edit().putString(wk(KEY_NOTES_HEADING), value).apply()

    /** Short haptic click when toggling a checklist item. Default: on. */
    var hapticFeedback: Boolean
        get() = prefs.getBoolean(wk(KEY_HAPTIC_FEEDBACK), true)
        set(value) = prefs.edit().putBoolean(wk(KEY_HAPTIC_FEEDBACK), value).apply()

    /**
     * When enabled, widgets refresh on the system interval and on screen unlock.
     * When disabled, a manual refresh button is shown on the widget header.
     * Default: on.
     */
    var autoSync: Boolean
        get() = prefs.getBoolean(wk(KEY_AUTO_SYNC), true)
        set(value) = prefs.edit().putBoolean(wk(KEY_AUTO_SYNC), value).apply()

    /** Time format for timestamped notes. Default: "HH:mm" */
    var timestampFormat: String
        get() = prefs.getString(wk(KEY_TIMESTAMP_FORMAT), "HH:mm") ?: "HH:mm"
        set(value) = prefs.edit().putString(wk(KEY_TIMESTAMP_FORMAT), value).apply()

    /** If the target heading is missing from the note, create it. Default: true */
    var createMissingHeading: Boolean
        get() = prefs.getBoolean(wk(KEY_CREATE_MISSING_HEADING), true)
        set(value) = prefs.edit().putBoolean(wk(KEY_CREATE_MISSING_HEADING), value).apply()

    /**
     * Which tab is currently active: "focus" or "notes".
     * Persisted per-widget so the last-used tab survives refresh.
     */
    var activeTab: String
        get() = prefs.getString(wk(KEY_ACTIVE_TAB), prefs.getString(wk(KEY_DEFAULT_TAB), "focus") ?: "focus") ?: "focus"
        set(value) { prefs.edit().putString(wk(KEY_ACTIVE_TAB), value).commit() }

    /** Default tab when widget is first configured: "focus", "notes". Default: "focus" */
    var defaultTab: String
        get() = prefs.getString(wk(KEY_DEFAULT_TAB), "focus") ?: "focus"
        set(value) = prefs.edit().putString(wk(KEY_DEFAULT_TAB), value).apply()

    fun getThemeColors(): ThemeColors {
        val isDark = widgetTheme == "dark"
        val accent = try { android.graphics.Color.parseColor(accentColor) } catch (_: Exception) { 0xFFD97757.toInt() }
        return if (isDark) {
            ThemeColors(
                bg = 0xF21A1A1E.toInt(),
                text = 0xFFE8E6E3.toInt(),
                textSecondary = 0xFF8B8B8F.toInt(),
                accent = accent,
                buttonText = 0xFFFFFFFF.toInt()
            )
        } else {
            ThemeColors(
                bg = 0xF2F5F5F5.toInt(),
                text = 0xFF1A1A1E.toInt(),
                textSecondary = 0xFF6B6B6F.toInt(),
                accent = accent,
                buttonText = 0xFFFFFFFF.toInt()
            )
        }
    }

    data class ThemeColors(
        val bg: Int,
        val text: Int,
        val textSecondary: Int,
        val accent: Int,
        val buttonText: Int
    )

    /**
     * Batch save all widget settings using commit() for reliable persistence.
     */
    fun saveWidgetSettings(
        dailyFolder: String,
        dateFormat: String,
        noteMode: NoteMode,
        showButtons: Boolean,
        sortUnchecked: Boolean,
        widgetAlpha: Int,
        tapCheckboxOnly: Boolean,
        addToTop: Boolean,
        showAddToTop: Boolean,
        widgetTheme: String,
        accentColor: String,
        showTodoCount: Boolean,
        hideFrontmatter: Boolean,
        startAfterFirstHeading: Boolean,
        smartTabsEnabled: Boolean,
        smartCaptureEnabled: Boolean,
        taskHeading: String,
        notesHeading: String,
        timestampFormat: String,
        createMissingHeading: Boolean,
        defaultTab: String,
        hapticFeedback: Boolean,
        autoSync: Boolean
    ) {
        prefs.edit()
            .putString(wk(KEY_DAILY_FOLDER), dailyFolder)
            .putString(wk(KEY_DATE_FORMAT), dateFormat)
            .putString(wk(KEY_NOTE_MODE), noteMode.name)
            .putBoolean(wk(KEY_SHOW_BUTTONS), showButtons)
            .putBoolean(wk(KEY_SORT_UNCHECKED), sortUnchecked)
            .putInt(wk(KEY_WIDGET_ALPHA), widgetAlpha)
            .putBoolean(wk(KEY_TAP_CHECKBOX_ONLY), tapCheckboxOnly)
            .putBoolean(wk(KEY_ADD_TO_TOP), addToTop)
            .putBoolean(wk(KEY_SHOW_ADD_TO_TOP), showAddToTop)
            .putString(wk(KEY_WIDGET_THEME), widgetTheme)
            .putString(wk(KEY_ACCENT_COLOR), accentColor)
            .putBoolean(wk(KEY_SHOW_TODO_COUNT), showTodoCount)
            .putBoolean(wk(KEY_HIDE_FRONTMATTER), hideFrontmatter)
            .putBoolean(wk(KEY_START_AFTER_HEADING), startAfterFirstHeading)
            .putBoolean(wk(KEY_SMART_TABS), smartTabsEnabled)
            .putBoolean(wk(KEY_SMART_CAPTURE), smartCaptureEnabled)
            .putString(wk(KEY_TASK_HEADING), taskHeading.ifBlank { DEFAULT_TASK_HEADING })
            .putString(wk(KEY_NOTES_HEADING), notesHeading.ifBlank { DEFAULT_NOTES_HEADING })
            .putString(wk(KEY_TIMESTAMP_FORMAT), timestampFormat.ifBlank { "HH:mm" })
            .putBoolean(wk(KEY_CREATE_MISSING_HEADING), createMissingHeading)
            .putString(wk(KEY_DEFAULT_TAB), defaultTab)
            .putBoolean(wk(KEY_HAPTIC_FEEDBACK), hapticFeedback)
            .putBoolean(wk(KEY_AUTO_SYNC), autoSync)
            .commit()
    }

    val isVaultConfigured: Boolean
        get() = vaultUri != null

    /**
     * Read the widget note based on current mode.
     */
    fun readWidgetNote(): String? {
        return when (noteMode) {
            NoteMode.DAILY -> readDailyNote()
            NoteMode.PINNED -> readPinnedNote()
        }
    }

    /**
     * Get the display title for the widget.
     */
    fun getWidgetTitle(): String {
        return when (noteMode) {
            NoteMode.DAILY -> "Daily Note"
            NoteMode.PINNED -> getCurrentPinnedNoteName()?.removeSuffix(".md") ?: "Pinned Note"
        }
    }

    /**
     * Read the pinned note content.
     */
    fun readPinnedNote(): String? {
        val uri = getCurrentPinnedNoteUri() ?: return null
        return readFileContent(uri)
    }

    /**
     * Read today's daily note content, if it exists.
     */
    fun readDailyNote(): String? {
        val uri = vaultUri ?: return null
        val rootDoc = DocumentFile.fromTreeUri(context, uri) ?: return null

        val todayFileName = getTodayFileName()
        val targetDir = if (dailyFolder.isNotBlank()) {
            findSubDirectory(rootDoc, dailyFolder)
        } else {
            rootDoc
        } ?: return null

        val noteFile = targetDir.findFile(todayFileName) ?: return null
        return readFileContent(noteFile.uri)
    }

    /**
     * Append text to today's daily note (creates the file if it doesn't exist).
     */
    fun appendToWidgetNote(text: String): Boolean {
        val formatted = if (parseChecklist().any { !it.isPlainText }) "- [ ] $text" else text
        return when (noteMode) {
            NoteMode.DAILY -> appendToDailyNote(formatted)
            NoteMode.PINNED -> appendToPinnedNote(formatted)
        }
    }

    fun appendToPinnedNote(text: String): Boolean {
        val uri = getCurrentPinnedNoteUri() ?: return false
        val existing = readFileContent(uri) ?: ""
        val newContent = if (existing.isNotBlank()) {
            if (addToTop) "$text\n$existing" else "$existing\n$text"
        } else text
        return writeFileContent(uri, newContent)
    }

    fun appendToDailyNote(text: String): Boolean {
        val uri = vaultUri ?: return false
        val rootDoc = DocumentFile.fromTreeUri(context, uri) ?: return false

        val todayFileName = getTodayFileName()
        val targetDir = if (dailyFolder.isNotBlank()) {
            findOrCreateSubDirectory(rootDoc, dailyFolder)
        } else {
            rootDoc
        } ?: return false

        val noteFile = targetDir.findFile(todayFileName)

        return if (noteFile != null) {
            // Append or prepend to existing file
            val existing = readFileContent(noteFile.uri) ?: ""
            val newContent = if (existing.isNotBlank()) {
                if (addToTop) "$text\n\n$existing" else "$existing\n\n$text"
            } else text
            writeFileContent(noteFile.uri, newContent)
        } else {
            // Create new file — just the captured text, no header
            val newFile = targetDir.createFile("text/markdown", todayFileName) ?: return false
            writeFileContent(newFile.uri, text)
        }
    }

    private fun getTodayFileName(): String {
        val formatter = DateTimeFormatter.ofPattern(dateFormat)
        return "${LocalDate.now().format(formatter)}.md"
    }

    private fun findSubDirectory(root: DocumentFile, path: String): DocumentFile? {
        var current = root
        for (segment in path.split("/")) {
            if (segment.isBlank()) continue
            current = current.findFile(segment) ?: return null
            if (!current.isDirectory) return null
        }
        return current
    }

    private fun findOrCreateSubDirectory(root: DocumentFile, path: String): DocumentFile? {
        var current = root
        for (segment in path.split("/")) {
            if (segment.isBlank()) continue
            val existing = current.findFile(segment)
            current = if (existing != null && existing.isDirectory) {
                existing
            } else {
                current.createDirectory(segment) ?: return null
            }
        }
        return current
    }

    private fun readFileContent(uri: Uri): String? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                BufferedReader(InputStreamReader(inputStream)).readText()
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun writeFileContent(uri: Uri, content: String): Boolean {
        return try {
            context.contentResolver.openOutputStream(uri, "wt")?.use { outputStream ->
                OutputStreamWriter(outputStream).use { writer ->
                    writer.write(content)
                }
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Parse checklist items from the current widget note.
     *
     * Frontmatter handling: we skip lines up to and including the closing '---',
     * but we preserve the original line indices so that toggleChecklistItem() can
     * still locate the correct line in the raw file without any offset arithmetic.
     *
     * startAfterFirstHeading: when enabled, items before the first heading are
     * also skipped, so only content after the first # heading is shown.
     */
    /**
     * @param forceTab When non-null ("focus" or "notes"), filter to that section
     *                 even if it is not the active tab. Used by dual ListView factories.
     */
    fun parseChecklist(forceTab: String? = null): List<ChecklistItem> {
        val content = readWidgetNote() ?: return emptyList()
        val lines = content.lines()
        val items = mutableListOf<ChecklistItem>()

        // Determine the last line index belonging to YAML frontmatter.
        // -1 means no frontmatter present.
        val fmEndLine = if (hideFrontmatter) findFrontmatterEndLine(lines) else -1

        // Smart tabs: find the bounds of the active (or forced) section so we only show
        // that section's content. Indices preserved → toggles still work.
        val tab = forceTab ?: activeTab
        val filterBySection = smartTabsEnabled || forceTab != null
        val (sectionStart, sectionEnd) = if (filterBySection && smartTabsEnabled) {
            val heading = if (tab == "focus") taskHeading else notesHeading
            getSectionBounds(lines, heading)
        } else Pair(-1, Int.MAX_VALUE)

        // When startAfterFirstHeading is on we collect items but defer emitting
        // until we see the first heading (the heading itself is also skipped).
        // (Only applies when smart tabs is off — tabs already pin to one section)
        val skipUntilHeading = startAfterFirstHeading && !smartTabsEnabled
        var headingFound = false

        lines.forEachIndexed { index, line ->
            // Skip frontmatter lines entirely (indices preserved for toggle safety)
            if (index <= fmEndLine) return@forEachIndexed
            // Skip lines outside the active section when smart tabs is on
            if (smartTabsEnabled && (index < sectionStart || index > sectionEnd)) return@forEachIndexed

            val match = CHECKLIST_REGEX.matchEntire(line)
            val headingMatch = HEADING_REGEX.matchEntire(line)

            if (match != null) {
                if (skipUntilHeading && !headingFound) return@forEachIndexed
                val indent = match.groupValues[1]
                val indentLevel = indent.count { it == ' ' } / 2 + indent.count { it == '\t' }
                val checked = match.groupValues[2].lowercase() == "x"
                val text = match.groupValues[3].trim()
                items.add(ChecklistItem(lineIndex = index, text = text, isChecked = checked, indentLevel = indentLevel))
            } else if (headingMatch != null) {
                if (skipUntilHeading && !headingFound) {
                    // Mark that we've passed the first heading; skip the heading line itself
                    headingFound = true
                    return@forEachIndexed
                }
                val text = headingMatch.groupValues[2].trim()
                items.add(ChecklistItem(lineIndex = index, text = text, isChecked = false, isPlainText = true, isHeading = true))
            } else {
                if (skipUntilHeading && !headingFound) return@forEachIndexed
                val bulletMatch = BULLET_REGEX.matchEntire(line)
                if (bulletMatch != null) {
                    val indent = bulletMatch.groupValues[1]
                    val indentLevel = indent.count { it == ' ' } / 2 + indent.count { it == '\t' }
                    val text = bulletMatch.groupValues[2].trim()
                    items.add(ChecklistItem(lineIndex = index, text = text, isChecked = false, isPlainText = true, isBullet = true, indentLevel = indentLevel))
                } else if (line.isNotBlank()) {
                    items.add(ChecklistItem(lineIndex = index, text = line.trim(), isChecked = false, isPlainText = true))
                }
            }
        }

        if (sortUnchecked) {
            val nonChecklist = items.filter { it.isPlainText }
            val unchecked = items.filter { !it.isPlainText && !it.isChecked }
            val checked = items.filter { !it.isPlainText && it.isChecked }
            return nonChecklist + unchecked + checked
        }
        return items
    }

    /**
     * Returns the note content with frontmatter and/or pre-heading content stripped,
     * for use in the plain-text preview path (non-checklist notes).
     */
    fun getDisplayContent(): String? {
        val content = readWidgetNote() ?: return null
        val lines = content.lines()

        val fmEndLine = if (hideFrontmatter) findFrontmatterEndLine(lines) else -1
        val afterFm = lines.drop(fmEndLine + 1)

        // Smart tabs: return only the active section
        if (smartTabsEnabled) {
            val heading = if (activeTab == "focus") taskHeading else notesHeading
            return getSection(afterFm.joinToString("\n"), heading)
        }

        if (!startAfterFirstHeading) {
            return afterFm.joinToString("\n").trimStart()
        }

        // Skip everything up to and including the first heading
        val headingIdx = afterFm.indexOfFirst { HEADING_REGEX.matchEntire(it) != null }
        return if (headingIdx == -1) {
            afterFm.joinToString("\n").trimStart()
        } else {
            afterFm.drop(headingIdx + 1).joinToString("\n").trimStart()
        }
    }

    // ── Smart Daily helpers ─────────────────────────────────────────────────────

    /**
     * Returns the (startLine, endLine) bounds (inclusive) of the content
     * under [heading] within [lines], or (-1, MAX_VALUE) if not found.
     * startLine points to the line AFTER the heading marker;
     * endLine points to the last non-empty line before the next ## heading or EOF.
     * Callers use these bounds to filter during forEachIndexed so raw line
     * indices are preserved and toggleChecklistItem() keeps working correctly.
     */
    fun getSectionBounds(lines: List<String>, heading: String): Pair<Int, Int> {
        val headingPattern = Regex("""^##\s+${Regex.escape(heading)}\s*$""", RegexOption.IGNORE_CASE)
        val nextHeadingPattern = Regex("""^#{1,6}\s+.+""")
        var start = -1
        for (i in lines.indices) {
            if (start == -1) {
                if (headingPattern.matches(lines[i])) start = i + 1
            } else {
                // Stop at the next heading of any level
                if (nextHeadingPattern.matches(lines[i])) return Pair(start, i - 1)
            }
        }
        return if (start == -1) Pair(-1, Int.MAX_VALUE) else Pair(start, lines.lastIndex)
    }

    /**
     * Extracts the text content under [heading] as a trimmed string.
     * Used for the plain-text preview path.
     */
    fun getSection(content: String, heading: String): String {
        val lines = content.lines()
        val (start, end) = getSectionBounds(lines, heading)
        if (start == -1) return ""
        return lines.subList(start, minOf(end + 1, lines.size)).joinToString("\n").trim()
    }

    /**
     * Inserts [line] under [heading] in [content].
     *
     * @param atTop true  → insert directly under the heading (newest first; used for Notes)
     *              false → append at the bottom of the section (used for Focus tasks)
     * If the heading is missing and [createIfMissing] is true, appends the section at EOF.
     */
    fun insertUnderHeading(
        content: String,
        heading: String,
        line: String,
        createIfMissing: Boolean = createMissingHeading,
        atTop: Boolean = true
    ): String {
        val lines = content.lines().toMutableList()
        val headingPattern = Regex("""^##\s+${Regex.escape(heading)}\s*$""", RegexOption.IGNORE_CASE)
        val nextHeadingPattern = Regex("""^#{1,6}\s+.+""")

        var headingIdx = -1
        for (i in lines.indices) {
            if (headingPattern.matches(lines[i])) {
                headingIdx = i
                break
            }
        }

        if (headingIdx == -1) {
            return if (createIfMissing) {
                content.trimEnd() + "\n\n## $heading\n$line\n"
            } else {
                content
            }
        }

        // Last line index of this section (before the next heading, or EOF)
        var sectionEnd = lines.lastIndex
        for (i in (headingIdx + 1)..lines.lastIndex) {
            if (nextHeadingPattern.matches(lines[i])) {
                sectionEnd = i - 1
                break
            }
        }

        if (atTop) {
            // Collapse blank lines right under the heading, then insert at top
            var insertAt = headingIdx + 1
            while (insertAt <= sectionEnd && insertAt < lines.size && lines[insertAt].isBlank()) {
                lines.removeAt(insertAt)
                sectionEnd--
            }
            lines.add(insertAt.coerceAtMost(lines.size), line)
        } else {
            // Append after the last non-blank content line in the section
            var lastContent = headingIdx
            for (i in (headingIdx + 1)..sectionEnd) {
                if (i < lines.size && lines[i].isNotBlank()) lastContent = i
            }
            lines.add((lastContent + 1).coerceAtMost(lines.size), line)
        }

        return lines.joinToString("\n")
    }

    /** Current time formatted with the widget's configured timestampFormat. */
    fun currentTimeHHmm(): String {
        val fmt = try {
            java.time.format.DateTimeFormatter.ofPattern(timestampFormat)
        } catch (_: Exception) {
            java.time.format.DateTimeFormatter.ofPattern("HH:mm")
        }
        return java.time.LocalTime.now().format(fmt)
    }

    /**
     * Tab-aware smart capture:
     * - "focus" tab  → strips leading dash/task syntax → inserts as "- [ ] task" under taskHeading
     * - "notes" tab  → inserts as "- HH:mm text" under notesHeading (newest first)
     * Falls back gracefully to plain append if headings can't be found and createMissingHeading is off.
     */
    fun smartCapture(content: String, input: String, tab: String = activeTab): String {
        val text = input.trim()
        if (text.isEmpty()) return content
        return when (tab) {
            "focus" -> {
                val clean = text.replace(Regex("""^-\s*(\[[ x]\])?\s*"""), "").trim()
                // Tasks append at the bottom of the Focus block
                insertUnderHeading(content, taskHeading, "- [ ] $clean", atTop = false)
            }
            "notes" -> {
                val time = currentTimeHHmm()
                // Newest notes at top: plain bullet + time + text (e.g. "- 16:29 Bought milk")
                insertUnderHeading(content, notesHeading, "- $time $text", atTop = true)
            }
            else -> content
        }
    }

    /**
     * Reads the current widget note, applies smartCapture(), and writes the result back.
     * Creates today's daily note if it does not exist yet.
     */
    fun writeSmartCapture(input: String): Boolean {
        val noteUri = getOrCreateWidgetNoteUri() ?: return false
        val content = readFileContent(noteUri) ?: ""
        val newContent = smartCapture(content, input)
        return writeFileContent(noteUri, newContent)
    }

    /**
     * URI of the widget note, creating today's daily note file when needed.
     */
    fun getOrCreateWidgetNoteUri(): Uri? {
        getWidgetNoteUri()?.let { return it }
        if (noteMode != NoteMode.DAILY) return null

        val uri = vaultUri ?: return null
        val rootDoc = DocumentFile.fromTreeUri(context, uri) ?: return null
        val todayFileName = getTodayFileName()
        val targetDir = if (dailyFolder.isNotBlank()) {
            findOrCreateSubDirectory(rootDoc, dailyFolder)
        } else {
            rootDoc
        } ?: return null

        val existing = targetDir.findFile(todayFileName)
        if (existing != null) return existing.uri
        val created = targetDir.createFile("text/markdown", todayFileName) ?: return null
        writeFileContent(created.uri, "")
        return created.uri
    }

    /**
     * Toggle a checklist item by its line index in the current widget note.
     */
    fun toggleChecklistItem(lineIndex: Int): Boolean {
        val noteUri = getWidgetNoteUri() ?: return false
        val content = readFileContent(noteUri) ?: return false
        val lines = content.lines().toMutableList()

        if (lineIndex < 0 || lineIndex >= lines.size) return false

        val line = lines[lineIndex]
        val match = CHECKLIST_REGEX.matchEntire(line) ?: return false

        val indent = match.groupValues[1]
        val currentState = match.groupValues[2]
        val text = match.groupValues[3]

        val newState = if (currentState.lowercase() == "x") " " else "x"
        lines[lineIndex] = "$indent- [$newState] $text"

        return writeFileContent(noteUri, lines.joinToString("\n"))
    }

    /**
     * Get the URI of the current widget note file.
     */
    fun getWidgetNoteUri(): Uri? {
        return when (noteMode) {
            NoteMode.PINNED -> getCurrentPinnedNoteUri()
            NoteMode.DAILY -> getDailyNoteUri()
        }
    }

    private fun getDailyNoteUri(): Uri? {
        val uri = vaultUri ?: return null
        val rootDoc = DocumentFile.fromTreeUri(context, uri) ?: return null
        val todayFileName = getTodayFileName()
        val targetDir = if (dailyFolder.isNotBlank()) {
            findSubDirectory(rootDoc, dailyFolder)
        } else {
            rootDoc
        } ?: return null
        return targetDir.findFile(todayFileName)?.uri
    }
}
