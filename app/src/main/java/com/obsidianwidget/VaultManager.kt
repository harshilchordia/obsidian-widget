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
        val isHeading: Boolean = false
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
        private const val DEFAULT_DATE_FORMAT = "yyyy-MM-dd"

        private val CHECKLIST_REGEX = Regex("""^(\s*)-\s*\[([ xX])\]\s*(.*)$""")
        private val HEADING_REGEX = Regex("""^(#{1,6})\s+(.+)$""")

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

    var showButtons: Boolean
        get() = prefs.getBoolean(wk(KEY_SHOW_BUTTONS), prefs.getBoolean(KEY_SHOW_BUTTONS, true))
        set(value) = prefs.edit().putBoolean(wk(KEY_SHOW_BUTTONS), value).apply()

    var sortUnchecked: Boolean
        get() = prefs.getBoolean(wk(KEY_SORT_UNCHECKED), prefs.getBoolean(KEY_SORT_UNCHECKED, false))
        set(value) = prefs.edit().putBoolean(wk(KEY_SORT_UNCHECKED), value).apply()

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
            NoteMode.PINNED -> pinnedNoteName?.removeSuffix(".md") ?: "Pinned Note"
        }
    }

    /**
     * Read the pinned note content.
     */
    fun readPinnedNote(): String? {
        val uri = pinnedNoteUri ?: return null
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
        val uri = pinnedNoteUri ?: return false
        val existing = readFileContent(uri) ?: ""
        val newContent = if (existing.isNotBlank()) "$existing\n$text" else text
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
            // Append to existing file
            val existing = readFileContent(noteFile.uri) ?: ""
            val newContent = if (existing.isNotBlank()) {
                "$existing\n\n$text"
            } else {
                text
            }
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
     */
    fun parseChecklist(): List<ChecklistItem> {
        val content = readWidgetNote() ?: return emptyList()
        val items = mutableListOf<ChecklistItem>()
        content.lines().forEachIndexed { index, line ->
            val match = CHECKLIST_REGEX.matchEntire(line)
            val headingMatch = HEADING_REGEX.matchEntire(line)
            if (match != null) {
                val checked = match.groupValues[2].lowercase() == "x"
                val text = match.groupValues[3].trim()
                items.add(ChecklistItem(lineIndex = index, text = text, isChecked = checked))
            } else if (headingMatch != null) {
                val text = headingMatch.groupValues[2].trim()
                items.add(ChecklistItem(lineIndex = index, text = text, isChecked = false, isPlainText = true, isHeading = true))
            } else if (line.isNotBlank()) {
                items.add(ChecklistItem(lineIndex = index, text = line.trim(), isChecked = false, isPlainText = true))
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
            NoteMode.PINNED -> pinnedNoteUri
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
