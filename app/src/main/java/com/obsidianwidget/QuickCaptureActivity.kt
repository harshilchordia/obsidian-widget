package com.obsidianwidget

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.ColorUtils

class QuickCaptureActivity : AppCompatActivity() {

    private lateinit var vaultManager: VaultManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportRequestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        setContentView(R.layout.activity_quick_capture)

        vaultManager = VaultManager(this)

        val appendToWidget = intent.getBooleanExtra(ObsidianWidgetProvider.EXTRA_APPEND_TO_WIDGET, false)
        val widgetId = intent.getIntExtra(ObsidianWidgetProvider.EXTRA_WIDGET_ID, -1)
        val widgetVaultManager = if (widgetId >= 0) VaultManager(this, widgetId) else vaultManager
        val captureInput = findViewById<EditText>(R.id.capture_input)
        val captureTitle = findViewById<TextView>(R.id.capture_title)
        val addToTopToggle = findViewById<Switch>(R.id.capture_add_to_top)
        val addToTopRow = findViewById<View>(R.id.capture_add_to_top_row)
        val saveBtn = findViewById<Button>(R.id.btn_save)
        val cancelBtn = findViewById<Button>(R.id.btn_cancel)

        // Smart routing when Smart Tabs or Smart Capture is on
        val smartMode = widgetVaultManager.smartTabsEnabled || widgetVaultManager.smartCaptureEnabled

        // Snapshot tab at open so we insert into the tab the user was viewing
        val targetTab = when {
            // + Add → active tab (focus tasks / notes log)
            appendToWidget -> widgetVaultManager.activeTab.ifBlank { "focus" }
            // Note button → always notes section when smart mode is on
            smartMode -> "notes"
            else -> "notes"
        }

        // "Add to top" only applies to plain append (not smart heading routing)
        if (smartMode) {
            addToTopRow.visibility = View.GONE
        } else if (widgetVaultManager.showAddToTop) {
            addToTopRow.visibility = View.VISIBLE
            addToTopToggle.isChecked = widgetVaultManager.addToTop
        } else {
            addToTopRow.visibility = View.GONE
        }

        applyAccent(widgetVaultManager, addToTopToggle, saveBtn, cancelBtn)

        if (appendToWidget || smartMode) {
            if (smartMode) {
                val heading = if (targetTab == "focus") {
                    widgetVaultManager.taskHeading
                } else {
                    widgetVaultManager.notesHeading
                }
                captureTitle.text = "Add to $heading"
                captureInput.hint = if (targetTab == "focus") "New task..." else "Quick note..."
            } else {
                captureTitle.text = "Add to ${widgetVaultManager.getWidgetTitle()}"
                captureInput.hint = "Type something to add..."
            }
            captureInput.inputType = android.text.InputType.TYPE_CLASS_TEXT
            captureInput.layoutParams.height = resources.getDimensionPixelSize(
                android.R.dimen.app_icon_size
            )
            captureInput.setSingleLine(false)
            captureInput.maxLines = 3
        } else {
            captureTitle.text = "Quick Note"
            captureInput.hint = "Capture a thought..."
        }

        cancelBtn.setOnClickListener { finish() }

        saveBtn.setOnClickListener {
            val text = captureInput.text.toString().trim()
            if (text.isBlank()) {
                finish()
                return@setOnClickListener
            }

            val vm = if (widgetId >= 0) widgetVaultManager else vaultManager
            if (!vm.isVaultConfigured) {
                Toast.makeText(this, R.string.vault_not_configured, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val success = if (smartMode) {
                // Always heading-route when smart mode is on (both Add and Note buttons)
                vm.writeSmartCapture(text, targetTab)
            } else if (appendToWidget) {
                vm.addToTop = addToTopToggle.isChecked
                vm.appendToWidgetNote(text)
            } else {
                vm.addToTop = addToTopToggle.isChecked
                vm.appendToDailyNote(text)
            }

            if (success) {
                Toast.makeText(this, R.string.note_saved, Toast.LENGTH_SHORT).show()
                // Force list rebind so the new bullet appears immediately
                if (widgetId >= 0) {
                    ObsidianWidgetProvider.refreshWidget(this, widgetId)
                } else {
                    ObsidianWidgetProvider.updateAllWidgets(this)
                }
            } else {
                Toast.makeText(this, R.string.error_saving, Toast.LENGTH_SHORT).show()
            }
            finish()
        }

        if (intent?.action == android.content.Intent.ACTION_SEND) {
            intent.getStringExtra(android.content.Intent.EXTRA_TEXT)?.let {
                captureInput.setText(it)
            }
        }
    }

    private fun applyAccent(
        vm: VaultManager,
        addToTopToggle: Switch,
        saveBtn: Button,
        cancelBtn: Button
    ) {
        val accent = try {
            Color.parseColor(vm.accentColor)
        } catch (_: Exception) {
            Color.parseColor("#D97757")
        }
        val tint = ColorStateList.valueOf(accent)
        val track = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(ColorUtils.setAlphaComponent(accent, 140), 0xFF5A5A5E.toInt())
        )
        addToTopToggle.thumbTintList = tint
        addToTopToggle.trackTintList = track

        val density = resources.displayMetrics.density
        val saveBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 18f * density
            setColor(accent)
        }
        saveBtn.background = saveBg
        saveBtn.backgroundTintList = ColorStateList.valueOf(accent)

        try {
            val material = saveBtn as? com.google.android.material.button.MaterialButton
            material?.backgroundTintList = ColorStateList.valueOf(accent)
            material?.rippleColor =
                ColorStateList.valueOf(ColorUtils.setAlphaComponent(accent, 80))
        } catch (_: Exception) { }

        cancelBtn.backgroundTintList =
            ColorStateList.valueOf(ColorUtils.setAlphaComponent(accent, 40))
    }
}
