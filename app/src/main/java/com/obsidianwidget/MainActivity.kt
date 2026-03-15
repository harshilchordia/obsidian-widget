package com.obsidianwidget

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var vaultManager: VaultManager
    private lateinit var vaultPathText: TextView
    private lateinit var dailyFolderInput: EditText
    private lateinit var dateFormatInput: EditText
    private lateinit var statusText: TextView
    private lateinit var noteModeGroup: RadioGroup
    private lateinit var pinnedNoteSection: LinearLayout
    private lateinit var dailyNoteSection: LinearLayout
    private lateinit var pinnedNoteText: TextView

    private val folderPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri?.let { onVaultSelected(it) }
    }

    private val notePicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { onNoteSelected(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        vaultManager = VaultManager(this)
        vaultPathText = findViewById(R.id.vault_path_text)
        dailyFolderInput = findViewById(R.id.daily_notes_folder)
        dateFormatInput = findViewById(R.id.date_format_input)
        statusText = findViewById(R.id.status_text)
        noteModeGroup = findViewById(R.id.note_mode_group)
        pinnedNoteSection = findViewById(R.id.pinned_note_section)
        dailyNoteSection = findViewById(R.id.daily_note_section)
        pinnedNoteText = findViewById(R.id.pinned_note_text)

        findViewById<Button>(R.id.btn_select_vault).setOnClickListener {
            folderPicker.launch(null)
        }

        findViewById<Button>(R.id.btn_select_note).setOnClickListener {
            notePicker.launch(arrayOf("text/*", "application/octet-stream"))
        }

        noteModeGroup.setOnCheckedChangeListener { _, checkedId ->
            updateModeSections(checkedId == R.id.radio_pinned)
        }

        loadCurrentSettings()
    }

    override fun onPause() {
        super.onPause()
        saveSettings()
    }

    private fun loadCurrentSettings() {
        if (vaultManager.isVaultConfigured) {
            vaultPathText.text = getString(R.string.vault_selected, vaultManager.vaultName ?: "Selected")
            statusText.text = "✅ Vault configured. Add the widget to your home screen!"
        }
        dailyFolderInput.setText(vaultManager.dailyFolder)
        dateFormatInput.setText(vaultManager.dateFormat)

        // Set mode toggle
        val isPinned = vaultManager.noteMode == VaultManager.NoteMode.PINNED
        noteModeGroup.check(if (isPinned) R.id.radio_pinned else R.id.radio_daily)
        updateModeSections(isPinned)

        // Set pinned note name
        vaultManager.pinnedNoteName?.let {
            pinnedNoteText.text = it.removeSuffix(".md")
        }
    }

    private fun updateModeSections(isPinned: Boolean) {
        pinnedNoteSection.visibility = if (isPinned) View.VISIBLE else View.GONE
        dailyNoteSection.visibility = if (isPinned) View.GONE else View.VISIBLE
    }

    private fun saveSettings() {
        vaultManager.dailyFolder = dailyFolderInput.text.toString().trim()
        vaultManager.dateFormat = dateFormatInput.text.toString().trim().ifBlank { "yyyy-MM-dd" }
        vaultManager.noteMode = if (noteModeGroup.checkedRadioButtonId == R.id.radio_pinned)
            VaultManager.NoteMode.PINNED else VaultManager.NoteMode.DAILY
        ObsidianWidgetProvider.updateAllWidgets(this)
    }

    private fun onVaultSelected(uri: Uri) {
        // Persist permission across reboots
        contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )

        vaultManager.vaultUri = uri
        vaultManager.vaultName = uri.lastPathSegment?.substringAfterLast(':') ?: "Vault"

        vaultPathText.text = getString(R.string.vault_selected, vaultManager.vaultName)
        statusText.text = "✅ Vault configured. Add the widget to your home screen!"

        ObsidianWidgetProvider.updateAllWidgets(this)
    }

    private fun onNoteSelected(uri: Uri) {
        contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION
        )

        val fileName = uri.lastPathSegment?.substringAfterLast('/') ?: "Note"
        vaultManager.pinnedNoteUri = uri
        vaultManager.pinnedNoteName = fileName
        pinnedNoteText.text = fileName.removeSuffix(".md")

        ObsidianWidgetProvider.updateAllWidgets(this)
    }
}
