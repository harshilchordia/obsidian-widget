package com.obsidianwidget

import android.appwidget.AppWidgetManager
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

class WidgetConfigActivity : AppCompatActivity() {

    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private lateinit var vaultManager: VaultManager
    private lateinit var vaultPathText: TextView
    private lateinit var dailyFolderInput: EditText
    private lateinit var noteModeGroup: RadioGroup
    private lateinit var pinnedSection: LinearLayout
    private lateinit var dailySection: LinearLayout
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

        // Default result = cancelled (so pressing back cancels widget add)
        setResult(RESULT_CANCELED)

        setContentView(R.layout.activity_widget_config)

        vaultManager = VaultManager(this)
        vaultPathText = findViewById(R.id.config_vault_path)
        dailyFolderInput = findViewById(R.id.config_daily_folder)
        noteModeGroup = findViewById(R.id.config_note_mode_group)
        pinnedSection = findViewById(R.id.config_pinned_section)
        dailySection = findViewById(R.id.config_daily_section)
        pinnedNoteText = findViewById(R.id.config_pinned_note_text)

        // Get the widget ID from the intent
        appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        // Load existing settings
        if (vaultManager.isVaultConfigured) {
            vaultPathText.text = vaultManager.vaultName ?: "Selected"
        }
        dailyFolderInput.setText(vaultManager.dailyFolder)

        val isPinned = vaultManager.noteMode == VaultManager.NoteMode.PINNED
        noteModeGroup.check(if (isPinned) R.id.config_radio_pinned else R.id.config_radio_daily)
        updateModeSections(isPinned)

        vaultManager.pinnedNoteName?.let {
            pinnedNoteText.text = it.removeSuffix(".md")
        }

        findViewById<Button>(R.id.config_select_vault).setOnClickListener {
            folderPicker.launch(null)
        }

        findViewById<Button>(R.id.config_select_note).setOnClickListener {
            notePicker.launch(arrayOf("text/*", "application/octet-stream"))
        }

        noteModeGroup.setOnCheckedChangeListener { _, checkedId ->
            updateModeSections(checkedId == R.id.config_radio_pinned)
        }

        findViewById<Button>(R.id.config_save).setOnClickListener {
            saveAndFinish()
        }
    }

    private fun updateModeSections(isPinned: Boolean) {
        pinnedSection.visibility = if (isPinned) View.VISIBLE else View.GONE
        dailySection.visibility = if (isPinned) View.GONE else View.VISIBLE
    }

    private fun onVaultSelected(uri: Uri) {
        contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        vaultManager.vaultUri = uri
        vaultManager.vaultName = uri.lastPathSegment?.substringAfterLast(':') ?: "Vault"
        vaultPathText.text = vaultManager.vaultName
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
    }

    private fun saveAndFinish() {
        vaultManager.dailyFolder = dailyFolderInput.text.toString().trim()
        vaultManager.noteMode = if (noteModeGroup.checkedRadioButtonId == R.id.config_radio_pinned)
            VaultManager.NoteMode.PINNED else VaultManager.NoteMode.DAILY

        // Trigger widget update
        val appWidgetManager = AppWidgetManager.getInstance(this)
        ObsidianWidgetProvider().onUpdate(this, appWidgetManager, intArrayOf(appWidgetId))

        // Return success
        val resultValue = Intent().apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        }
        setResult(RESULT_OK, resultValue)
        finish()
    }
}
