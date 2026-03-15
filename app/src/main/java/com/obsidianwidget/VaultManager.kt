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
class VaultManager(private val context: Context) {

    enum class NoteMode { DAILY, PINNED }

    data class ChecklistItem(
        val lineIndex: Int,
        val text: String,
        val isChecked: Boolean
    )

    companion object {
        private const val PREFS_NAME = "obsidian_widget_prefs"
        private const val KEY_VAULT_URI = "vault_uri"
        private const val KEY_VAULT_NAME = "vault_name"
        private const val KEY_DAILY_FOLDER = "daily_folder"
        private const val KEY_DATE_FORMAT = "date_format"
        private const val KEY_NOTE_MODE = "note_mode"
        private const val KEY_PINNED_NOTE_URI = "pinned_note_uri"
        private const val KEY_PINNED_NOTE_NAME = "pinned_note_name"
        private const val DEFAULT_DATE_FORMAT = "yyyy-MM-dd"

        private val CHECKLIST_REGEX = Regex("""^(\s*)-\s*\[([ xX])\]\s*(.*)$""")
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var vaultUri: Uri?
        get() = prefs.getString(KEY_VAULT_URI, null)?.let { Uri.parse(it) }
        set(value) = prefs.edit().putString(KEY_VAULT_URI, value?.toString()).apply()

    var vaultName: String?
        get() = prefs.getString(KEY_VAULT_NAME, null)
        set(value) = prefs.edit().putString(KEY_VAULT_NAME, value).apply()

    var dailyFolder: String
        get() = prefs.getString(KEY_DAILY_FOLDER, "") ?: ""
        set(value) = prefs.edit().putString(KEY_DAILY_FOLDER, value).apply()

    var dateFormat: String
        get() = prefs.getString(KEY_DATE_FORMAT, DEFAULT_DATE_FORMAT) ?: DEFAULT_DATE_FORMAT
        set(value) = prefs.edit().putString(KEY_DATE_FORMAT, value).apply()

    var noteMode: NoteMode
        get() = if (prefs.getString(KEY_NOTE_MODE, "DAILY") == "PINNED") NoteMode.PINNED else NoteMode.DAILY
        set(value) = prefs.edit().putString(KEY_NOTE_MODE, value.name).apply()

    var pinnedNoteUri: Uri?
        get() = prefs.getString(KEY_PINNED_NOTE_URI, null)?.let { Uri.parse(it) }
        set(value) = prefs.edit().putString(KEY_PINNED_NOTE_URI, value?.toString()).apply()

    var pinnedNoteName: String?
        get() = prefs.getString(KEY_PINNED_NOTE_NAME, null)
        set(value) = prefs.edit().putString(KEY_PINNED_NOTE_NAME, value).apply()

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

    enum class AppendResult { SUCCESS, NOTE_NOT_FOUND, ERROR }

    /**
     * Append text to today's daily note. Does NOT create the file —
     * if the daily note doesn't exist, returns NOTE_NOT_FOUND so the caller
     * can trigger Obsidian to create it with the proper template.
     */
    fun appendToDailyNote(text: String): AppendResult {
        val uri = vaultUri ?: return AppendResult.ERROR
        val rootDoc = DocumentFile.fromTreeUri(context, uri) ?: return AppendResult.ERROR

        val todayFileName = getTodayFileName()
        val targetDir = if (dailyFolder.isNotBlank()) {
            findSubDirectory(rootDoc, dailyFolder)
        } else {
            rootDoc
        } ?: return AppendResult.NOTE_NOT_FOUND

        val noteFile = targetDir.findFile(todayFileName) ?: return AppendResult.NOTE_NOT_FOUND

        val existing = readFileContent(noteFile.uri) ?: ""
        val newContent = if (existing.isNotBlank()) {
            "$existing\n\n$text"
        } else {
            text
        }
        return if (writeFileContent(noteFile.uri, newContent)) AppendResult.SUCCESS else AppendResult.ERROR
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
            if (match != null) {
                val checked = match.groupValues[2].lowercase() == "x"
                val text = match.groupValues[3].trim()
                items.add(ChecklistItem(lineIndex = index, text = text, isChecked = checked))
            }
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
