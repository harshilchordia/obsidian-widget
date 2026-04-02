package com.obsidianwidget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.Switch
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
    private lateinit var noteListContainer: LinearLayout
    private lateinit var transparencySeekBar: SeekBar
    private lateinit var transparencyLabel: TextView
    private lateinit var showButtonsToggle: Switch
    private lateinit var sortUncheckedToggle: Switch
    private lateinit var tapCheckboxOnlyToggle: Switch
    private lateinit var showAddToTopToggle: Switch
    private lateinit var showTodoCountToggle: Switch
    private lateinit var dateFormatInput: EditText
    private lateinit var themeGroup: RadioGroup
    private var selectedAccentColor: String = "#D97757"
    private val colorSwatchIds = listOf(
        R.id.color_terracotta, R.id.color_purple, R.id.color_blue, R.id.color_green,
        R.id.color_red, R.id.color_teal, R.id.color_pink, R.id.color_amber
    )

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

        // Get the widget ID from the intent FIRST
        appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        vaultManager = VaultManager(this, appWidgetId)
        vaultPathText = findViewById(R.id.config_vault_path)
        dailyFolderInput = findViewById(R.id.config_daily_folder)
        noteModeGroup = findViewById(R.id.config_note_mode_group)
        pinnedSection = findViewById(R.id.config_pinned_section)
        dailySection = findViewById(R.id.config_daily_section)
        noteListContainer = findViewById(R.id.config_note_list_container)
        transparencySeekBar = findViewById(R.id.config_transparency)
        transparencyLabel = findViewById(R.id.config_transparency_label)
        showButtonsToggle = findViewById(R.id.config_show_buttons)
        sortUncheckedToggle = findViewById(R.id.config_sort_unchecked)
        tapCheckboxOnlyToggle = findViewById(R.id.config_tap_checkbox_only)
        showAddToTopToggle = findViewById(R.id.config_show_add_to_top)
        showTodoCountToggle = findViewById(R.id.config_show_todo_count)
        dateFormatInput = findViewById(R.id.config_date_format)
        themeGroup = findViewById(R.id.config_theme_group)

        // Set up accent color swatches
        setupColorSwatches()

        // Load saved settings into UI
        loadSettings()

        transparencySeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                transparencyLabel.text = "$progress%"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        findViewById<Button>(R.id.config_select_vault).setOnClickListener {
            folderPicker.launch(null)
        }

        findViewById<Button>(R.id.config_add_note).setOnClickListener {
            notePicker.launch(arrayOf("text/*", "application/octet-stream"))
        }

        noteModeGroup.setOnCheckedChangeListener { _, checkedId ->
            updateModeSections(checkedId == R.id.config_radio_pinned)
        }

        findViewById<Button>(R.id.config_save).setOnClickListener {
            saveAndFinish()
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)

        val newWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        if (newWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            appWidgetId = newWidgetId
            vaultManager = VaultManager(this, appWidgetId)
            loadSettings()
        }
    }

    private fun loadSettings() {
        if (vaultManager.isVaultConfigured) {
            vaultPathText.text = vaultManager.vaultName ?: "Selected"
        }
        dailyFolderInput.setText(vaultManager.dailyFolder)

        val isPinned = vaultManager.noteMode == VaultManager.NoteMode.PINNED
        noteModeGroup.check(if (isPinned) R.id.config_radio_pinned else R.id.config_radio_daily)
        updateModeSections(isPinned)

        refreshNoteList()
        showButtonsToggle.isChecked = vaultManager.showButtons
        sortUncheckedToggle.isChecked = vaultManager.sortUnchecked
        tapCheckboxOnlyToggle.isChecked = vaultManager.tapCheckboxOnly
        showAddToTopToggle.isChecked = vaultManager.showAddToTop
        showTodoCountToggle.isChecked = vaultManager.showTodoCount
        dateFormatInput.setText(vaultManager.dateFormat)

        transparencySeekBar.progress = vaultManager.widgetAlpha
        transparencyLabel.text = "${vaultManager.widgetAlpha}%"

        // Theme
        themeGroup.check(if (vaultManager.widgetTheme == "light") R.id.config_theme_light else R.id.config_theme_dark)

        // Accent color
        selectedAccentColor = vaultManager.accentColor
        highlightSelectedColor()
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
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        val fileName = uri.lastPathSegment?.substringAfterLast('/') ?: "Note"
        vaultManager.addPinnedNote(uri, fileName)
        refreshNoteList()
    }

    private fun refreshNoteList() {
        noteListContainer.removeAllViews()
        val names = vaultManager.pinnedNoteNameList
        names.forEachIndexed { index, name ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(36, 24, 36, 24)
                background = getDrawable(R.drawable.input_background)
                val params = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                params.bottomMargin = 8
                layoutParams = params
            }

            val nameText = TextView(this).apply {
                text = name.removeSuffix(".md")
                setTextColor(getColor(R.color.obsidian_text))
                textSize = 14f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val removeBtn = TextView(this).apply {
                text = "\u2715"
                setTextColor(getColor(R.color.obsidian_text_secondary))
                textSize = 16f
                setPadding(16, 0, 0, 0)
                setOnClickListener {
                    vaultManager.removePinnedNote(index)
                    refreshNoteList()
                }
            }

            row.addView(nameText)
            row.addView(removeBtn)
            noteListContainer.addView(row)
        }
    }

    private fun setupColorSwatches() {
        for (id in colorSwatchIds) {
            val swatch = findViewById<View>(id)
            val colorHex = swatch.tag as String
            val color = Color.parseColor(colorHex)
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
            }
            swatch.background = bg
            swatch.setOnClickListener {
                selectedAccentColor = colorHex
                highlightSelectedColor()
            }
        }
    }

    private fun highlightSelectedColor() {
        for (id in colorSwatchIds) {
            val swatch = findViewById<View>(id)
            val colorHex = swatch.tag as String
            val color = Color.parseColor(colorHex)
            val isSelected = colorHex.equals(selectedAccentColor, ignoreCase = true)
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
                if (isSelected) {
                    setStroke(4, Color.WHITE)
                } else {
                    setStroke(0, Color.TRANSPARENT)
                }
            }
            swatch.background = bg
        }
    }

    private fun saveAndFinish() {
        // Use batch commit for reliable persistence
        vaultManager.saveWidgetSettings(
            dailyFolder = dailyFolderInput.text.toString().trim(),
            dateFormat = dateFormatInput.text.toString().trim().ifBlank { "yyyy-MM-dd" },
            noteMode = if (noteModeGroup.checkedRadioButtonId == R.id.config_radio_pinned)
                VaultManager.NoteMode.PINNED else VaultManager.NoteMode.DAILY,
            showButtons = showButtonsToggle.isChecked,
            sortUnchecked = sortUncheckedToggle.isChecked,
            widgetAlpha = transparencySeekBar.progress,
            tapCheckboxOnly = tapCheckboxOnlyToggle.isChecked,
            addToTop = vaultManager.addToTop,
            showAddToTop = showAddToTopToggle.isChecked,
            widgetTheme = if (themeGroup.checkedRadioButtonId == R.id.config_theme_light) "light" else "dark",
            accentColor = selectedAccentColor,
            showTodoCount = showTodoCountToggle.isChecked
        )

        // Trigger update for this specific widget
        val appWidgetManager = AppWidgetManager.getInstance(this)
        val provider = ObsidianWidgetProvider()
        provider.onUpdate(this, appWidgetManager, intArrayOf(appWidgetId))

        // Also refresh all other widgets so they don't go stale
        ObsidianWidgetProvider.updateAllWidgets(this)

        // Return success
        val resultValue = Intent().apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        }
        setResult(RESULT_OK, resultValue)
        finish()
    }
}
