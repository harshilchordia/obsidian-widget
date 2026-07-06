package com.obsidianwidget

import android.appwidget.AppWidgetManager
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView

class QuickCaptureConfigActivity : BaseWidgetConfigActivity() {

    private lateinit var vaultPathText: TextView
    private lateinit var dateFormatInput: EditText
    private lateinit var timestampEnabledToggle: Switch
    private lateinit var timestampFormatInput: EditText
    private lateinit var insertModeGroup: RadioGroup
    private lateinit var placeholderInput: EditText
    private lateinit var transparencySeekBar: SeekBar
    private lateinit var transparencyLabel: TextView
    private lateinit var themeGroup: RadioGroup

    override val colorSwatchIds = listOf(
        R.id.capture_color_terracotta,
        R.id.capture_color_purple,
        R.id.capture_color_blue,
        R.id.capture_color_green
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (isFinishing) return

        setContentView(R.layout.activity_quick_capture_config)

        vaultPathText = findViewById(R.id.capture_config_vault_path)
        dateFormatInput = findViewById(R.id.capture_config_date_format)
        timestampEnabledToggle = findViewById(R.id.capture_config_timestamp_enabled)
        timestampFormatInput = findViewById(R.id.capture_config_timestamp_format)
        insertModeGroup = findViewById(R.id.capture_config_insert_mode)
        placeholderInput = findViewById(R.id.capture_config_placeholder)
        transparencySeekBar = findViewById(R.id.capture_config_transparency)
        transparencyLabel = findViewById(R.id.capture_config_transparency_label)
        themeGroup = findViewById(R.id.capture_config_theme_group)

        setupColorSwatches()
        loadSettings()

        transparencySeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                transparencyLabel.text = getString(R.string.opacity_label, progress)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        findViewById<Button>(R.id.capture_config_select_vault).setOnClickListener {
            launchVaultPicker()
        }

        findViewById<Button>(R.id.capture_config_save).setOnClickListener {
            saveAndFinish()
        }
    }

    override fun onVaultNameChanged(name: String) {
        vaultPathText.text = name
    }

    override fun loadSettings() {
        if (vaultManager.isVaultConfigured) {
            vaultPathText.text = vaultManager.vaultName ?: getString(R.string.select_vault)
        }
        dateFormatInput.setText(vaultManager.getDailyNotePathTemplate())
        timestampEnabledToggle.isChecked = vaultManager.captureTimestampEnabled
        timestampFormatInput.setText(vaultManager.captureTimestampFormat)
        placeholderInput.setText(vaultManager.capturePlaceholder)

        when (vaultManager.captureInsertMode) {
            VaultManager.CaptureInsertMode.START ->
                insertModeGroup.check(R.id.capture_config_insert_start)
            VaultManager.CaptureInsertMode.END ->
                insertModeGroup.check(R.id.capture_config_insert_end)
            VaultManager.CaptureInsertMode.PLACEHOLDER ->
                insertModeGroup.check(R.id.capture_config_insert_placeholder)
        }

        themeGroup.check(
            if (vaultManager.widgetTheme == "light") R.id.capture_config_theme_light
            else R.id.capture_config_theme_dark
        )
        selectedAccentColor = vaultManager.accentColor
        highlightSelectedColor()

        transparencySeekBar.progress = vaultManager.widgetAlpha
        transparencyLabel.text = getString(R.string.opacity_label, vaultManager.widgetAlpha)
    }

    override fun saveAndFinish() {
        val insertMode = when (insertModeGroup.checkedRadioButtonId) {
            R.id.capture_config_insert_start -> VaultManager.CaptureInsertMode.START
            R.id.capture_config_insert_placeholder -> VaultManager.CaptureInsertMode.PLACEHOLDER
            else -> VaultManager.CaptureInsertMode.END
        }

        vaultManager.saveCaptureWidgetSettings(
            dailyNotePathTemplate = dateFormatInput.text.toString(),
            captureTimestampEnabled = timestampEnabledToggle.isChecked,
            captureTimestampFormat = timestampFormatInput.text.toString().trim().ifBlank { "HH:mm" },
            captureInsertMode = insertMode,
            capturePlaceholder = placeholderInput.text.toString(),
            widgetAlpha = transparencySeekBar.progress,
            widgetTheme = if (themeGroup.checkedRadioButtonId == R.id.capture_config_theme_light) "light" else "dark",
            accentColor = selectedAccentColor
        )

        // Trigger update for this specific widget (its theme/alpha may have changed)
        QuickCaptureWidgetProvider().onUpdate(
            this,
            AppWidgetManager.getInstance(this),
            intArrayOf(appWidgetId)
        )

        finish()
    }
}
