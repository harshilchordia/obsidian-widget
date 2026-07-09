package com.obsidianwidget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
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
import androidx.core.graphics.ColorUtils

class WidgetConfigActivity : AppCompatActivity() {

    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private var isPickerOpen = false
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
    private lateinit var hideFrontmatterToggle: Switch
    private lateinit var startAfterHeadingToggle: Switch
    private lateinit var hapticFeedbackToggle: Switch
    private lateinit var autoSyncToggle: Switch
    // Smart Daily
    private lateinit var smartTabsToggle: Switch
    private lateinit var smartCaptureToggle: Switch
    private lateinit var createMissingHeadingToggle: Switch
    private lateinit var taskHeadingInput: EditText
    private lateinit var notesHeadingInput: EditText
    private lateinit var timestampFormatInput: EditText
    private lateinit var defaultTabGroup: RadioGroup
    private lateinit var dateFormatInput: EditText
    private lateinit var themeGroup: RadioGroup
    private lateinit var saveButton: Button
    private lateinit var previewSwatch: View
    private lateinit var previewThemeLabel: TextView
    private lateinit var previewTitle: TextView
    private lateinit var appearanceLabel: TextView
    private var selectedAccentColor: String = "#D97757"
    private val colorSwatchIds = listOf(
        R.id.color_terracotta, R.id.color_purple, R.id.color_blue, R.id.color_green,
        R.id.color_red, R.id.color_teal, R.id.color_pink, R.id.color_amber
    )
    private val colorNames = mapOf(
        "#D97757" to "Terracotta",
        "#7C5FC7" to "Purple",
        "#4A9EF5" to "Blue",
        "#4CAF50" to "Green",
        "#E53935" to "Red",
        "#009688" to "Teal",
        "#E91E63" to "Pink",
        "#FFB300" to "Amber"
    )

    private val folderPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        isPickerOpen = false
        uri?.let { onVaultSelected(it) }
    }

    private val notePicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        isPickerOpen = false
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

        // Set result eagerly so ANY future finish() delivers success to the launcher
        setResult(RESULT_OK, Intent().apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        })

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
        hideFrontmatterToggle = findViewById(R.id.config_hide_frontmatter)
        startAfterHeadingToggle = findViewById(R.id.config_start_after_heading)
        hapticFeedbackToggle = findViewById(R.id.config_haptic_feedback)
        autoSyncToggle = findViewById(R.id.config_auto_sync)
        smartTabsToggle = findViewById(R.id.config_smart_tabs)
        smartCaptureToggle = findViewById(R.id.config_smart_capture)
        createMissingHeadingToggle = findViewById(R.id.config_create_missing_heading)
        taskHeadingInput = findViewById(R.id.config_task_heading)
        notesHeadingInput = findViewById(R.id.config_notes_heading)
        timestampFormatInput = findViewById(R.id.config_timestamp_format)
        defaultTabGroup = findViewById(R.id.config_default_tab_group)
        dateFormatInput = findViewById(R.id.config_date_format)
        themeGroup = findViewById(R.id.config_theme_group)
        saveButton = findViewById(R.id.config_save)
        previewSwatch = findViewById(R.id.config_theme_preview_swatch)
        previewThemeLabel = findViewById(R.id.config_theme_preview_theme)
        previewTitle = findViewById(R.id.config_theme_preview_title)
        appearanceLabel = findViewById(R.id.config_appearance_label)

        // Set up accent color swatches
        setupColorSwatches()

        // Live theme updates
        themeGroup.setOnCheckedChangeListener { _, _ ->
            updateAppearancePreview()
        }

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
            isPickerOpen = true
            folderPicker.launch(null)
        }

        findViewById<Button>(R.id.config_add_note).setOnClickListener {
            isPickerOpen = true
            notePicker.launch(arrayOf("text/*", "application/octet-stream"))
        }

        noteModeGroup.setOnCheckedChangeListener { _, checkedId ->
            updateModeSections(checkedId == R.id.config_radio_pinned)
        }

        saveButton.setOnClickListener {
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
        hideFrontmatterToggle.isChecked = vaultManager.hideFrontmatter
        startAfterHeadingToggle.isChecked = vaultManager.startAfterFirstHeading
        hapticFeedbackToggle.isChecked = vaultManager.hapticFeedback
        autoSyncToggle.isChecked = vaultManager.autoSync
        smartTabsToggle.isChecked = vaultManager.smartTabsEnabled
        smartCaptureToggle.isChecked = vaultManager.smartCaptureEnabled
        createMissingHeadingToggle.isChecked = vaultManager.createMissingHeading
        taskHeadingInput.setText(vaultManager.taskHeading)
        notesHeadingInput.setText(vaultManager.notesHeading)
        timestampFormatInput.setText(vaultManager.timestampFormat)
        defaultTabGroup.check(
            if (vaultManager.defaultTab == "notes") R.id.config_default_tab_notes
            else R.id.config_default_tab_focus
        )
        dateFormatInput.setText(vaultManager.dateFormat)

        transparencySeekBar.progress = vaultManager.widgetAlpha
        transparencyLabel.text = "${vaultManager.widgetAlpha}%"

        // Theme — clear listener side-effects during load, then re-apply
        themeGroup.check(
            if (vaultManager.widgetTheme == "light") R.id.config_theme_light
            else R.id.config_theme_dark
        )

        // Accent color — normalize casing for reliable swatch match
        selectedAccentColor = vaultManager.accentColor.uppercase().let { raw ->
            // Prefer the canonical swatch hex when possible
            colorSwatchIds.mapNotNull { id ->
                findViewById<View>(id).tag?.toString()?.uppercase()
            }.firstOrNull { it.equals(raw, ignoreCase = true) }
                ?: vaultManager.accentColor
        }
        // Keep original tag form for parseColor
        selectedAccentColor = colorSwatchIds
            .mapNotNull { findViewById<View>(it).tag?.toString() }
            .firstOrNull { it.equals(vaultManager.accentColor, ignoreCase = true) }
            ?: vaultManager.accentColor

        highlightSelectedColor()
        updateAppearancePreview()
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
            val colorHex = swatch.tag?.toString() ?: continue
            val color = Color.parseColor(colorHex)
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
            }
            swatch.background = bg
            swatch.setOnClickListener {
                selectedAccentColor = colorHex
                highlightSelectedColor()
                updateAppearancePreview()
            }
        }
    }

    private fun highlightSelectedColor() {
        for (id in colorSwatchIds) {
            val swatch = findViewById<View>(id)
            val colorHex = swatch.tag?.toString() ?: continue
            val color = Color.parseColor(colorHex)
            val isSelected = colorHex.equals(selectedAccentColor, ignoreCase = true)
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
                if (isSelected) {
                    setStroke(5, Color.WHITE)
                } else {
                    setStroke(0, Color.TRANSPARENT)
                }
            }
            swatch.background = bg
        }
    }

    /** Updates live preview + tints interactive config controls to the selected accent. */
    private fun updateAppearancePreview() {
        val accent = try {
            Color.parseColor(selectedAccentColor)
        } catch (_: Exception) {
            Color.parseColor("#D97757")
        }
        val isLight = themeGroup.checkedRadioButtonId == R.id.config_theme_light
        val themeName = if (isLight) "Light" else "Dark"
        val accentName = colorNames.entries
            .firstOrNull { it.key.equals(selectedAccentColor, ignoreCase = true) }
            ?.value
            ?: selectedAccentColor

        previewThemeLabel.text = "$themeName · $accentName"
        previewTitle.setTextColor(if (isLight) 0xFF1A1A1E.toInt() else 0xFFE8E6E3.toInt())
        previewThemeLabel.setTextColor(if (isLight) 0xFF1A1A1E.toInt() else 0xFFE8E6E3.toInt())

        val previewBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 16f * resources.displayMetrics.density
            setColor(if (isLight) 0xFFF0F0F2.toInt() else 0xFF2A2A30.toInt())
        }
        findViewById<View>(R.id.config_theme_preview).background = previewBg

        val swatchBg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(accent)
            setStroke(3, ColorUtils.setAlphaComponent(Color.WHITE, 180))
        }
        previewSwatch.background = swatchBg

        // Tint save button + section labels + switches to selected accent
        appearanceLabel.setTextColor(accent)
        applyAccentToControls(accent)
    }

    private fun applyAccentToControls(accent: Int) {
        val tint = ColorStateList.valueOf(accent)
        val track = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(ColorUtils.setAlphaComponent(accent, 120), 0xFF5A5A5E.toInt())
        )

        val switches = listOf(
            showButtonsToggle, sortUncheckedToggle, tapCheckboxOnlyToggle,
            showAddToTopToggle, showTodoCountToggle, hideFrontmatterToggle,
            startAfterHeadingToggle, hapticFeedbackToggle, autoSyncToggle,
            smartTabsToggle, smartCaptureToggle, createMissingHeadingToggle
        )
        for (sw in switches) {
            sw.thumbTintList = tint
            sw.trackTintList = track
        }

        findViewById<android.widget.RadioButton>(R.id.config_theme_dark).buttonTintList = tint
        findViewById<android.widget.RadioButton>(R.id.config_theme_light).buttonTintList = tint
        findViewById<android.widget.RadioButton>(R.id.config_radio_daily).buttonTintList = tint
        findViewById<android.widget.RadioButton>(R.id.config_radio_pinned).buttonTintList = tint
        findViewById<android.widget.RadioButton>(R.id.config_default_tab_focus).buttonTintList = tint
        findViewById<android.widget.RadioButton>(R.id.config_default_tab_notes).buttonTintList = tint

        transparencySeekBar.progressTintList = tint
        transparencySeekBar.thumbTintList = tint

        // Save button background tinted to accent
        val saveBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 20f * resources.displayMetrics.density
            setColor(accent)
        }
        saveButton.background = saveBg

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Keep select vault button accented too when possible
            findViewById<Button>(R.id.config_select_vault).backgroundTintList = tint
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (!isPickerOpen) {
            saveAndFinish()
        }
    }

    private fun currentThemeValue(): String =
        if (themeGroup.checkedRadioButtonId == R.id.config_theme_light) "light" else "dark"

    private fun saveAndFinish() {
        // Persist theme/accent first with explicit property writes so they cannot
        // be lost if a later step fails, then batch-save the rest.
        vaultManager.widgetTheme = currentThemeValue()
        vaultManager.accentColor = selectedAccentColor
        vaultManager.hapticFeedback = hapticFeedbackToggle.isChecked
        vaultManager.autoSync = autoSyncToggle.isChecked

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
            widgetTheme = currentThemeValue(),
            accentColor = selectedAccentColor,
            showTodoCount = showTodoCountToggle.isChecked,
            hideFrontmatter = hideFrontmatterToggle.isChecked,
            startAfterFirstHeading = startAfterHeadingToggle.isChecked,
            smartTabsEnabled = smartTabsToggle.isChecked,
            smartCaptureEnabled = smartCaptureToggle.isChecked,
            taskHeading = taskHeadingInput.text.toString(),
            notesHeading = notesHeadingInput.text.toString(),
            timestampFormat = timestampFormatInput.text.toString(),
            createMissingHeading = createMissingHeadingToggle.isChecked,
            defaultTab = if (defaultTabGroup.checkedRadioButtonId == R.id.config_default_tab_notes) "notes" else "focus",
            hapticFeedback = hapticFeedbackToggle.isChecked,
            autoSync = autoSyncToggle.isChecked
        )

        // Force a full widget redraw (chrome + remote list data) so theme/accent apply immediately
        val appWidgetManager = AppWidgetManager.getInstance(this)
        val provider = ObsidianWidgetProvider()
        provider.onUpdate(this, appWidgetManager, intArrayOf(appWidgetId))
        appWidgetManager.notifyAppWidgetViewDataChanged(appWidgetId, R.id.widget_checklist)
        appWidgetManager.notifyAppWidgetViewDataChanged(appWidgetId, R.id.widget_checklist_focus)
        appWidgetManager.notifyAppWidgetViewDataChanged(appWidgetId, R.id.widget_checklist_notes)

        // Also refresh all other widgets so they don't go stale
        ObsidianWidgetProvider.updateAllWidgets(this)

        finish()
    }
}
