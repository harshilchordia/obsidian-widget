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

    data class NoteHeading(
        val lineIndex: Int,
        val level: Int,
        val title: String
    )

    enum class BlockType {
        TASK, PARAGRAPH, HEADING, BULLET, ORDERED, HORIZONTAL_RULE, CODE, MATH,
        TABLE_ROW, CALLOUT_TITLE, FOOTNOTE, PROPERTY;

        val isListItem: Boolean get() = this == TASK || this == BULLET || this == ORDERED
    }

    data class ChecklistItem(
        val lineIndex: Int,
        val text: String,
        val type: BlockType,
        val isChecked: Boolean = false,
        val indentLevel: Int = 0,
        val headingLevel: Int = 0,
        /** "•", "3." or, for footnotes, the footnote label. */
        val listMarker: String? = null,
        val quoteDepth: Int = 0,
        val calloutType: String? = null,
        val calloutFold: String = "",
        val codeLanguage: String = "",
        val tableCells: List<String> = emptyList(),
        val tableAlignments: List<Char> = emptyList(),
        val isTableHeader: Boolean = false,
        val propertyName: String? = null
    ) {
        /** Everything except tasks is display-only content. */
        val isPlainText: Boolean get() = type != BlockType.TASK
    }

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
        private const val DEFAULT_DATE_FORMAT = "yyyy-MM-dd"

        private val CAPTURE_TASK_PREFIX = Regex("""^(?:[-*+]\s*)?(?:\[[ xX]\]\s*)?""")
        private val HEADING_REGEX = Regex("""^(#{1,6})\s+(.+)$""")
        private val PROPERTY_REGEX = Regex("""^([A-Za-z0-9_-]+):\s*(.*)$""")

        fun deleteWidgetPrefs(context: Context, widgetId: Int) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit()
                .remove("${KEY_DAILY_FOLDER}_$widgetId")
                .remove("${KEY_DATE_FORMAT}_$widgetId")
                .remove("${KEY_NOTE_MODE}_$widgetId")
                .remove("${KEY_PINNED_NOTE_URI}_$widgetId")
                .remove("${KEY_PINNED_NOTE_NAME}_$widgetId")
                .remove("${KEY_SHOW_BUTTONS}_$widgetId")
                .remove("${KEY_SHOW_ADD_BUTTON}_$widgetId")
                .remove("${KEY_SHOW_NOTE_BUTTON}_$widgetId")
                .remove("${KEY_SORT_UNCHECKED}_$widgetId")
                .remove("${KEY_HIDE_PROPERTIES}_$widgetId")
                .remove("${KEY_PINNED_NOTE_URIS}_$widgetId")
                .remove("${KEY_PINNED_NOTE_NAMES}_$widgetId")
                .remove("${KEY_PINNED_NOTE_PATHS}_$widgetId")
                .remove("${KEY_CURRENT_NOTE_INDEX}_$widgetId")
                .remove("${KEY_WIDGET_ALPHA}_$widgetId")
                .remove("${KEY_TAP_CHECKBOX_ONLY}_$widgetId")
                .remove("${KEY_ADD_TO_TOP}_$widgetId")
                .remove("${KEY_SHOW_ADD_TO_TOP}_$widgetId")
                .remove("${KEY_WIDGET_THEME}_$widgetId")
                .remove("${KEY_ACCENT_COLOR}_$widgetId")
                .remove("${KEY_SHOW_TODO_COUNT}_$widgetId")
                .apply()
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
        items.addAll(MarkdownParser.parse(lines, frontmatterEnd?.plus(1) ?: 0))
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
                    type = BlockType.PROPERTY,
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
        val match = MarkdownParser.TASK_LINE.matchEntire(line) ?: return false

        val newState = if (match.groupValues[2] == " ") "x" else " "
        lines[lineIndex] = match.groupValues[1] + newState + match.groupValues[3]

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
