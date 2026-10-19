package com.obsidianwidget

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.net.Uri
import android.provider.DocumentsContract
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

    enum class CaptureInsertMode { START, END, PLACEHOLDER }

    enum class CaptureResult { SUCCESS, ERROR, PLACEHOLDER_NOT_FOUND }

    data class NoteHeading(
        val lineIndex: Int,
        val level: Int,
        val title: String
    )

    data class ChecklistItem(
        val lineIndex: Int,
        val text: String,
        val isChecked: Boolean,
        val isPlainText: Boolean = false,
        val isHeading: Boolean = false,
        val isBullet: Boolean = false,
        val isHorizontalRule: Boolean = false,
        val indentLevel: Int = 0,
        val propertyName: String? = null
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
        private const val KEY_SHOW_ADD_BUTTON = "show_add_button"
        private const val KEY_SHOW_NOTE_BUTTON = "show_note_button"
        private const val KEY_SORT_UNCHECKED = "sort_unchecked"
        private const val KEY_HIDE_PROPERTIES = "hide_properties"
        private const val KEY_PINNED_NOTE_URIS = "pinned_note_uris"
        private const val KEY_PINNED_NOTE_NAMES = "pinned_note_names"
        private const val KEY_PINNED_NOTE_PATHS = "pinned_note_paths"
        private const val KEY_CURRENT_NOTE_INDEX = "current_note_index"
        private const val KEY_WIDGET_ALPHA = "widget_alpha"
        private const val KEY_TAP_CHECKBOX_ONLY = "tap_checkbox_only"
        private const val KEY_ADD_TO_TOP = "add_to_top"
        private const val KEY_SHOW_ADD_TO_TOP = "show_add_to_top"
        private const val KEY_WIDGET_THEME = "widget_theme"
        private const val KEY_ACCENT_COLOR = "accent_color"
        private const val KEY_SHOW_TODO_COUNT = "show_todo_count"
        private const val KEY_CAPTURE_TIMESTAMP_ENABLED = "capture_timestamp_enabled"
        private const val KEY_CAPTURE_TIMESTAMP_FORMAT = "capture_timestamp_format"
        private const val KEY_CAPTURE_INSERT_MODE = "capture_insert_mode"
        private const val KEY_CAPTURE_PLACEHOLDER = "capture_placeholder"
        private const val DEFAULT_DATE_FORMAT = "yyyy-MM-dd"
        private const val DEFAULT_CAPTURE_TIMESTAMP_FORMAT = "HH:mm"

        private val CHECKLIST_REGEX = Regex("""^(\s*)-\s*\[([ xX])\]\s*(.*)$""")
        private val CAPTURE_TASK_PREFIX = Regex("""^(?:[-*+]\s*)?(?:\[[ xX]\]\s*)?""")
        private val HEADING_REGEX = Regex("""^(#{1,6})\s+(.+)$""")
        private val BULLET_REGEX = Regex("""^(\s*)[*+-]\s+(.+)$""")
        private val PROPERTY_REGEX = Regex("""^([A-Za-z0-9_-]+):\s*(.*)$""")

        fun deleteWidgetPrefs(context: Context, widgetId: Int) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            // Per-widget keys are exactly those wk() suffixes with "_<widgetId>";
            // no global key ends in "_<digits>", so a suffix sweep is safe and
            // automatically covers keys added later.
            val editor = prefs.edit()
            prefs.all.keys
                .filter { it.endsWith("_$widgetId") }
                .forEach { editor.remove(it) }
            editor.apply()
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

    private var pinnedNotePathList: List<String>
        get() = prefs.getString(wk(KEY_PINNED_NOTE_PATHS), null)
            ?.split("|||")
            ?.map(Uri::decode)
            ?: emptyList()
        set(value) {
            prefs.edit()
                .putString(wk(KEY_PINNED_NOTE_PATHS), value.joinToString("|||") { Uri.encode(it) })
                .commit()
        }

    var currentNoteIndex: Int
        get() = prefs.getInt(wk(KEY_CURRENT_NOTE_INDEX), 0)
        set(value) { prefs.edit().putInt(wk(KEY_CURRENT_NOTE_INDEX), value).commit() }

    fun addPinnedNote(uri: Uri, name: String) {
        val uris = pinnedNoteUriList.toMutableList()
        val names = pinnedNoteNameList.toMutableList()
        val paths = pinnedNotePathList.toMutableList()
        while (paths.size < uris.size) paths.add("")
        uris.add(uri.toString())
        names.add(name)
        paths.add(getVaultRelativePath(uri).orEmpty())
        pinnedNoteUriList = uris
        pinnedNoteNameList = names
        pinnedNotePathList = paths
    }

    fun removePinnedNote(index: Int) {
        val uris = pinnedNoteUriList.toMutableList()
        val names = pinnedNoteNameList.toMutableList()
        val paths = pinnedNotePathList.toMutableList()
        if (index in uris.indices) {
            uris.removeAt(index)
            names.removeAt(index)
            if (index in paths.indices) paths.removeAt(index)
            pinnedNoteUriList = uris
            pinnedNoteNameList = names
            pinnedNotePathList = paths
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
        val storedUri = Uri.parse(uris[idx])
        val relativePath = pinnedNotePathList.getOrNull(idx)?.takeIf { it.isNotBlank() }
            ?: getVaultRelativePath(storedUri)
        val resolvedUri = relativePath?.let(::findNoteByRelativePath)?.uri ?: return storedUri

        if (resolvedUri != storedUri) {
            val updatedUris = uris.toMutableList().apply { this[idx] = resolvedUri.toString() }
            pinnedNoteUriList = updatedUris
        }
        if (relativePath != pinnedNotePathList.getOrNull(idx)) {
            val updatedPaths = pinnedNotePathList.toMutableList()
            while (updatedPaths.size <= idx) updatedPaths.add("")
            updatedPaths[idx] = relativePath
            pinnedNotePathList = updatedPaths
        }
        return resolvedUri
    }

    fun getCurrentPinnedNoteName(): String? {
        val names = pinnedNoteNameList
        if (names.isEmpty()) return null
        val idx = currentNoteIndex.coerceIn(0, names.lastIndex)
        return names[idx]
    }

    var showAddButton: Boolean
        get() = prefs.getBoolean(
            wk(KEY_SHOW_ADD_BUTTON),
            prefs.getBoolean(wk(KEY_SHOW_BUTTONS), prefs.getBoolean(KEY_SHOW_BUTTONS, true))
        )
        set(value) = prefs.edit().putBoolean(wk(KEY_SHOW_ADD_BUTTON), value).apply()

    var showNoteButton: Boolean
        get() = prefs.getBoolean(
            wk(KEY_SHOW_NOTE_BUTTON),
            prefs.getBoolean(wk(KEY_SHOW_BUTTONS), prefs.getBoolean(KEY_SHOW_BUTTONS, true))
        )
        set(value) = prefs.edit().putBoolean(wk(KEY_SHOW_NOTE_BUTTON), value).apply()

    var sortUnchecked: Boolean
        get() = prefs.getBoolean(wk(KEY_SORT_UNCHECKED), prefs.getBoolean(KEY_SORT_UNCHECKED, false))
        set(value) = prefs.edit().putBoolean(wk(KEY_SORT_UNCHECKED), value).apply()

    var hideProperties: Boolean
        get() = prefs.getBoolean(wk(KEY_HIDE_PROPERTIES), false)
        set(value) = prefs.edit().putBoolean(wk(KEY_HIDE_PROPERTIES), value).apply()

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
        get() = prefs.getString(wk(KEY_WIDGET_THEME), "system") ?: "system"
        set(value) = prefs.edit().putString(wk(KEY_WIDGET_THEME), value).apply()

    var accentColor: String
        get() = prefs.getString(wk(KEY_ACCENT_COLOR), "#D97757") ?: "#D97757"
        set(value) = prefs.edit().putString(wk(KEY_ACCENT_COLOR), value).apply()

    var showTodoCount: Boolean
        get() = prefs.getBoolean(wk(KEY_SHOW_TODO_COUNT), false)
        set(value) = prefs.edit().putBoolean(wk(KEY_SHOW_TODO_COUNT), value).apply()

    var captureTimestampEnabled: Boolean
        get() = prefs.getBoolean(wk(KEY_CAPTURE_TIMESTAMP_ENABLED), true)
        set(value) = prefs.edit().putBoolean(wk(KEY_CAPTURE_TIMESTAMP_ENABLED), value).apply()

    var captureTimestampFormat: String
        get() = prefs.getString(
            wk(KEY_CAPTURE_TIMESTAMP_FORMAT),
            DEFAULT_CAPTURE_TIMESTAMP_FORMAT
        ) ?: DEFAULT_CAPTURE_TIMESTAMP_FORMAT
        set(value) = prefs.edit().putString(wk(KEY_CAPTURE_TIMESTAMP_FORMAT), value).apply()

    var captureInsertMode: CaptureInsertMode
        get() {
            val stored = prefs.getString(wk(KEY_CAPTURE_INSERT_MODE), CaptureInsertMode.END.name)
            return try {
                CaptureInsertMode.valueOf(stored ?: CaptureInsertMode.END.name)
            } catch (_: IllegalArgumentException) {
                CaptureInsertMode.END
            }
        }
        set(value) = prefs.edit().putString(wk(KEY_CAPTURE_INSERT_MODE), value.name).apply()

    var capturePlaceholder: String
        get() = prefs.getString(wk(KEY_CAPTURE_PLACEHOLDER), "") ?: ""
        set(value) = prefs.edit().putString(wk(KEY_CAPTURE_PLACEHOLDER), value).apply()

    fun getThemeColors(): ThemeColors {
        val isDark = isDarkTheme()
        val accent = try { android.graphics.Color.parseColor(accentColor) } catch (_: Exception) { 0xFFD97757.toInt() }
        return if (isDark) {
            ThemeColors(
                bg = 0xFF1A1A1E.toInt(),
                text = 0xFFE8E6E3.toInt(),
                textSecondary = 0xFF8B8B8F.toInt(),
                accent = accent,
                buttonText = 0xFFFFFFFF.toInt()
            )
        } else {
            ThemeColors(
                bg = 0xFFF5F5F5.toInt(),
                text = 0xFF1A1A1E.toInt(),
                textSecondary = 0xFF6B6B6F.toInt(),
                accent = accent,
                buttonText = 0xFFFFFFFF.toInt()
            )
        }
    }

    fun isDarkTheme(): Boolean = when (widgetTheme) {
        "dark" -> true
        "light" -> false
        else -> context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
    }

    data class ThemeColors(
        val bg: Int,
        val text: Int,
        val textSecondary: Int,
        val accent: Int,
        val buttonText: Int
    )

    data class DailyNoteLocation(
        val directoryPath: String,
        val fileName: String
    )

    /**
     * Batch save all widget settings using commit() for reliable persistence.
     */
    fun saveWidgetSettings(
        dailyFolder: String,
        dateFormat: String,
        noteMode: NoteMode,
        showAddButton: Boolean,
        showNoteButton: Boolean,
        sortUnchecked: Boolean,
        hideProperties: Boolean,
        widgetAlpha: Int,
        tapCheckboxOnly: Boolean,
        addToTop: Boolean,
        showAddToTop: Boolean,
        widgetTheme: String,
        accentColor: String,
        showTodoCount: Boolean
    ) {
        prefs.edit()
            .putString(wk(KEY_DAILY_FOLDER), dailyFolder)
            .putString(wk(KEY_DATE_FORMAT), dateFormat)
            .putString(wk(KEY_NOTE_MODE), noteMode.name)
            .putBoolean(wk(KEY_SHOW_ADD_BUTTON), showAddButton)
            .putBoolean(wk(KEY_SHOW_NOTE_BUTTON), showNoteButton)
            .putBoolean(wk(KEY_SORT_UNCHECKED), sortUnchecked)
            .putBoolean(wk(KEY_HIDE_PROPERTIES), hideProperties)
            .putInt(wk(KEY_WIDGET_ALPHA), widgetAlpha)
            .putBoolean(wk(KEY_TAP_CHECKBOX_ONLY), tapCheckboxOnly)
            .putBoolean(wk(KEY_ADD_TO_TOP), addToTop)
            .putBoolean(wk(KEY_SHOW_ADD_TO_TOP), showAddToTop)
            .putString(wk(KEY_WIDGET_THEME), widgetTheme)
            .putString(wk(KEY_ACCENT_COLOR), accentColor)
            .putBoolean(wk(KEY_SHOW_TODO_COUNT), showTodoCount)
            .commit()
    }

    /**
     * Batch save quick-capture widget settings using commit() for reliable persistence.
     * The whole daily-note path template (folders + date tokens) is stored in the
     * date-format pref; the folder pref is cleared so it can't double-apply.
     */
    fun saveCaptureWidgetSettings(
        dailyNotePathTemplate: String,
        captureTimestampEnabled: Boolean,
        captureTimestampFormat: String,
        captureInsertMode: CaptureInsertMode,
        capturePlaceholder: String,
        widgetAlpha: Int,
        widgetTheme: String,
        accentColor: String
    ) {
        prefs.edit()
            .putString(wk(KEY_DAILY_FOLDER), "")
            .putString(wk(KEY_DATE_FORMAT), normalizeDateFormat(dailyNotePathTemplate))
            .putBoolean(wk(KEY_CAPTURE_TIMESTAMP_ENABLED), captureTimestampEnabled)
            .putString(wk(KEY_CAPTURE_TIMESTAMP_FORMAT), captureTimestampFormat)
            .putString(wk(KEY_CAPTURE_INSERT_MODE), captureInsertMode.name)
            .putString(wk(KEY_CAPTURE_PLACEHOLDER), capturePlaceholder)
            .putInt(wk(KEY_WIDGET_ALPHA), widgetAlpha)
            .putString(wk(KEY_WIDGET_THEME), widgetTheme)
            .putString(wk(KEY_ACCENT_COLOR), accentColor)
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

    fun readWidgetNoteForDisplay(): String? {
        val content = readWidgetNote() ?: return null
        if (!hideProperties) return content

        val lines = content.lines()
        val frontmatterEnd = findFrontmatterEnd(lines) ?: return content
        return lines.drop(frontmatterEnd + 1).joinToString("\n").trimStart('\n', '\r')
    }

    private fun findFrontmatterEnd(lines: List<String>): Int? {
        if (lines.firstOrNull()?.trim() != "---") return null
        val closingOffset = lines.drop(1).indexOfFirst { it.trim() == "---" }
        return if (closingOffset >= 0) closingOffset + 1 else null
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
        val location = resolveDailyNoteLocation()
        val targetDir = if (location.directoryPath.isNotBlank()) {
            findSubDirectory(rootDoc, location.directoryPath)
        } else {
            rootDoc
        } ?: return null

        val noteFile = targetDir.findFile(location.fileName) ?: return null
        return readFileContent(noteFile.uri)
    }

    /**
     * Append text to today's daily note (creates the file if it doesn't exist).
     */
    fun appendToWidgetNote(text: String, headingLineIndex: Int? = null): Boolean {
        val inputLines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (inputLines.isEmpty()) return false

        val formatted = if (parseChecklist().any { !it.isPlainText }) {
            inputLines.mapNotNull { line ->
                CAPTURE_TASK_PREFIX.replace(line, "").trim()
                    .takeIf { it.isNotEmpty() }
                    ?.let { "- [ ] $it" }
            }.joinToString("\n")
        } else {
            inputLines.joinToString("\n")
        }
        if (formatted.isEmpty()) return false

        if (headingLineIndex != null) {
            return appendToHeading(formatted, headingLineIndex)
        }
        return when (noteMode) {
            NoteMode.DAILY -> appendToDailyNote(formatted)
            NoteMode.PINNED -> appendToPinnedNote(formatted)
        }
    }

    fun getNoteHeadings(): List<NoteHeading> {
        val content = readWidgetNote() ?: return emptyList()
        val lines = content.lines()
        val frontmatterEnd = findFrontmatterEnd(lines)
        return lines.mapIndexedNotNull { index, line ->
            if (frontmatterEnd != null && index <= frontmatterEnd) return@mapIndexedNotNull null
            val match = HEADING_REGEX.matchEntire(line) ?: return@mapIndexedNotNull null
            NoteHeading(
                lineIndex = index,
                level = match.groupValues[1].length,
                title = match.groupValues[2].trim()
            )
        }
    }

    private fun appendToHeading(text: String, headingLineIndex: Int): Boolean {
        val noteUri = getWidgetNoteUri() ?: return false
        val content = readFileContent(noteUri) ?: return false
        val lines = content.lines().toMutableList()
        val heading = lines.getOrNull(headingLineIndex)?.let(HEADING_REGEX::matchEntire)
            ?: return false
        val headingLevel = heading.groupValues[1].length

        var insertionIndex = headingLineIndex + 1
        if (!addToTop) {
            insertionIndex = lines.indexOfFirstFrom(headingLineIndex + 1) { line ->
                val nextHeading = HEADING_REGEX.matchEntire(line)
                nextHeading != null && nextHeading.groupValues[1].length <= headingLevel
            }.let { if (it >= 0) it else lines.size }

            while (insertionIndex > headingLineIndex + 1 && lines[insertionIndex - 1].isBlank()) {
                insertionIndex--
            }
        }

        lines.add(insertionIndex, text)
        return writeFileContent(noteUri, lines.joinToString("\n"))
    }

    private inline fun List<String>.indexOfFirstFrom(
        startIndex: Int,
        predicate: (String) -> Boolean
    ): Int {
        for (index in startIndex until size) {
            if (predicate(this[index])) return index
        }
        return -1
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
        return insertIntoDailyNote(text, addToTop, null) == CaptureResult.SUCCESS
    }

    /**
     * Append captured text to today's daily note using quick-capture widget settings.
     */
    fun captureToDailyNote(text: String): CaptureResult {
        val mode = captureInsertMode
        val placeholder = if (mode == CaptureInsertMode.PLACEHOLDER) {
            capturePlaceholder.takeIf { it.isNotBlank() }
                ?: return CaptureResult.PLACEHOLDER_NOT_FOUND
        } else {
            null
        }
        val entry = formatCaptureEntry(text)
        return insertIntoDailyNote(entry, mode == CaptureInsertMode.START, placeholder)
    }

    private fun formatCaptureEntry(text: String): String {
        if (!captureTimestampEnabled) return text
        val pattern = captureTimestampFormat.ifBlank { DEFAULT_CAPTURE_TIMESTAMP_FORMAT }
        val timestamp = ObsidianDateFormat.formatDateTime(pattern) ?: return text
        return if (text.contains('\n')) {
            "$timestamp\n$text"
        } else {
            "$timestamp - $text"
        }
    }

    private fun insertIntoDailyNote(
        text: String,
        insertAtStart: Boolean,
        placeholder: String?
    ): CaptureResult {
        val uri = vaultUri ?: return CaptureResult.ERROR
        val rootDoc = DocumentFile.fromTreeUri(context, uri) ?: return CaptureResult.ERROR
        val location = resolveDailyNoteLocation()
        val targetDir = if (location.directoryPath.isNotBlank()) {
            findOrCreateSubDirectory(rootDoc, location.directoryPath)
        } else {
            rootDoc
        } ?: return CaptureResult.ERROR

        val noteFile = targetDir.findFile(location.fileName)

        val written = if (noteFile != null) {
            val existing = readFileContent(noteFile.uri) ?: ""
            val newContent = when {
                existing.isBlank() -> newNoteContent(text, placeholder)
                placeholder != null && existing.contains(placeholder) -> {
                    existing.replaceFirst(placeholder, "$text\n\n$placeholder")
                }
                placeholder != null -> return CaptureResult.PLACEHOLDER_NOT_FOUND
                insertAtStart -> "$text\n\n$existing"
                else -> "$existing\n\n$text"
            }
            writeFileContent(noteFile.uri, newContent)
        } else {
            val newFile = targetDir.createFile("text/markdown", location.fileName)
                ?: return CaptureResult.ERROR
            writeFileContent(newFile.uri, newNoteContent(text, placeholder))
        }
        return if (written) CaptureResult.SUCCESS else CaptureResult.ERROR
    }

    /**
     * Content for a fresh/blank daily note: in placeholder mode the placeholder is
     * written into the note so later captures still have an anchor to insert at.
     */
    private fun newNoteContent(text: String, placeholder: String?): String =
        if (placeholder != null) "$text\n\n$placeholder" else text

    private fun normalizeDateFormat(raw: String): String {
        val trimmed = raw.trim()
        val withoutExt = if (trimmed.endsWith(".md", ignoreCase = true)) {
            trimmed.dropLast(3)
        } else {
            trimmed
        }
        return withoutExt.ifBlank { DEFAULT_DATE_FORMAT }
    }

    fun getDailyNotePathTemplate(): String {
        val folder = dailyFolder.trim().trim('/')
        val format = normalizeDateFormat(dateFormat)
        return if (folder.isBlank()) format else "$folder/$format"
    }

    /**
     * Resolve today's daily note folder and filename using Obsidian-style date tokens.
     * The configured folder is used verbatim; only the date-format part may contain
     * tokens (and slashes, which create nested folders).
     *
     * Examples:
     * - folder="", format="yyyy-MM-dd" -> ("", "2026-07-06.md")
     * - folder="Daily Notes", format="YYYY/MM/YYYY-MM-DD" -> ("Daily Notes/2026/07", "2026-07-06.md")
     */
    fun resolveDailyNoteLocation(date: LocalDate = LocalDate.now()): DailyNoteLocation {
        val folder = dailyFolder.trim().trim('/')
        val format = normalizeDateFormat(dateFormat)
        var resolvedFormat = ObsidianDateFormat.format(format, date).trim('/')
        if (resolvedFormat.isBlank()) {
            resolvedFormat = date.format(DateTimeFormatter.ofPattern(DEFAULT_DATE_FORMAT))
        }
        val resolved = if (folder.isBlank()) resolvedFormat else "$folder/$resolvedFormat"

        val slash = resolved.lastIndexOf('/')
        val baseName = if (slash >= 0) resolved.substring(slash + 1) else resolved
        val directoryPath = if (slash >= 0) resolved.substring(0, slash) else ""
        val fileName = if (baseName.endsWith(".md", ignoreCase = true)) baseName else "$baseName.md"
        return DailyNoteLocation(directoryPath, fileName)
    }

    fun getDailyNoteRelativePath(date: LocalDate = LocalDate.now()): String {
        val location = resolveDailyNoteLocation(date)
        val nameWithoutExt = location.fileName.removeSuffix(".md")
        return if (location.directoryPath.isBlank()) {
            nameWithoutExt
        } else {
            "${location.directoryPath}/$nameWithoutExt"
        }
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

    private fun getVaultRelativePath(noteUri: Uri): String? {
        val rootUri = vaultUri ?: return null
        return try {
            val treeId = DocumentsContract.getTreeDocumentId(rootUri)
            val documentId = DocumentsContract.getDocumentId(noteUri)
            if (documentId.startsWith("$treeId/")) {
                documentId.removePrefix("$treeId/")
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun findNoteByRelativePath(path: String): DocumentFile? {
        val rootUri = vaultUri ?: return null
        var current = DocumentFile.fromTreeUri(context, rootUri) ?: return null
        for (segment in path.split("/")) {
            if (segment.isBlank()) continue
            current = current.findFile(segment) ?: return null
        }
        return current.takeIf { it.isFile }
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
     */
    fun parseChecklist(): List<ChecklistItem> {
        val content = readWidgetNote() ?: return emptyList()
        val lines = content.lines()
        val frontmatterEnd = findFrontmatterEnd(lines)
        val items = mutableListOf<ChecklistItem>()
        if (frontmatterEnd != null && !hideProperties) {
            items.addAll(parseFrontmatterProperties(lines, frontmatterEnd))
        }
        lines.forEachIndexed { index, line ->
            if (frontmatterEnd != null && index <= frontmatterEnd) return@forEachIndexed
            val match = CHECKLIST_REGEX.matchEntire(line)
            val headingMatch = HEADING_REGEX.matchEntire(line)
            if (match != null) {
                val indent = match.groupValues[1]
                val indentLevel = indent.count { it == ' ' } / 2 + indent.count { it == '\t' }
                val checked = match.groupValues[2].lowercase() == "x"
                val text = match.groupValues[3].trim()
                items.add(ChecklistItem(lineIndex = index, text = text, isChecked = checked, indentLevel = indentLevel))
            } else if (line.trim() == "---") {
                items.add(ChecklistItem(lineIndex = index, text = "", isChecked = false, isPlainText = true, isHorizontalRule = true))
            } else if (headingMatch != null) {
                val text = headingMatch.groupValues[2].trim()
                items.add(ChecklistItem(lineIndex = index, text = text, isChecked = false, isPlainText = true, isHeading = true))
            } else {
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

    private fun parseFrontmatterProperties(
        lines: List<String>,
        frontmatterEnd: Int
    ): List<ChecklistItem> {
        val properties = mutableListOf<ChecklistItem>()
        var propertyName: String? = null
        var propertyLineIndex = -1
        val values = mutableListOf<String>()

        fun addProperty() {
            val name = propertyName ?: return
            properties.add(
                ChecklistItem(
                    lineIndex = propertyLineIndex,
                    text = values.joinToString(", "),
                    isChecked = false,
                    isPlainText = true,
                    propertyName = name
                )
            )
        }

        lines.subList(1, frontmatterEnd).forEachIndexed { offset, line ->
            val match = PROPERTY_REGEX.matchEntire(line)
            if (match != null) {
                addProperty()
                propertyName = match.groupValues[1]
                propertyLineIndex = offset + 1
                values.clear()
                cleanPropertyValue(match.groupValues[2]).takeIf { it.isNotEmpty() }?.let(values::add)
            } else if (propertyName != null) {
                cleanPropertyValue(line).takeIf { it.isNotEmpty() }?.let(values::add)
            }
        }
        addProperty()
        return properties
    }

    private fun cleanPropertyValue(value: String): String {
        var cleaned = value.trim().removePrefix("-").trim()
        if (cleaned.startsWith("[") && cleaned.endsWith("]")) {
            cleaned = cleaned.substring(1, cleaned.length - 1).trim()
        }
        return cleaned.removeSurrounding("\"").removeSurrounding("'")
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
        val location = resolveDailyNoteLocation()
        val targetDir = if (location.directoryPath.isNotBlank()) {
            findSubDirectory(rootDoc, location.directoryPath)
        } else {
            rootDoc
        } ?: return null
        return targetDir.findFile(location.fileName)?.uri
    }
}
