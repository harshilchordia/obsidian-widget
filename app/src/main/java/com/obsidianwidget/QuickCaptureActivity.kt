package com.obsidianwidget

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

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
        val captureTitle = findViewById<android.widget.TextView>(R.id.capture_title)
        val addToTopToggle = findViewById<Switch>(R.id.capture_add_to_top)
        val addToTopRow = findViewById<View>(R.id.capture_add_to_top_row)

        // Show/hide add-to-top toggle based on widget config
        if (widgetVaultManager.showAddToTop) {
            addToTopRow.visibility = View.VISIBLE
            addToTopToggle.isChecked = widgetVaultManager.addToTop
        }

        if (appendToWidget) {
            val noteName = widgetVaultManager.getWidgetTitle()
            captureTitle.text = "Add to $noteName"
            captureInput.hint = "Type something to add..."
            captureInput.inputType = android.text.InputType.TYPE_CLASS_TEXT
            captureInput.layoutParams.height = resources.getDimensionPixelSize(
                android.R.dimen.app_icon_size) // ~48dp, compact
            captureInput.setSingleLine(false)
            captureInput.maxLines = 3
        } else {
            captureTitle.text = "Quick Note"
            captureInput.hint = "Capture a thought..."
        }

        findViewById<Button>(R.id.btn_cancel).setOnClickListener {
            finish()
        }

        findViewById<Button>(R.id.btn_save).setOnClickListener {
            val text = captureInput.text.toString().trim()
            if (text.isBlank()) {
                finish()
                return@setOnClickListener
            }

            if (!vaultManager.isVaultConfigured) {
                Toast.makeText(this, R.string.vault_not_configured, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val success = if (appendToWidget) {
                widgetVaultManager.addToTop = addToTopToggle.isChecked
                widgetVaultManager.appendToWidgetNote(text)
            } else {
                vaultManager.addToTop = addToTopToggle.isChecked
                vaultManager.appendToDailyNote(text)
            }
            if (success) {
                Toast.makeText(this, R.string.note_saved, Toast.LENGTH_SHORT).show()
                ObsidianWidgetProvider.updateAllWidgets(this)
            } else {
                Toast.makeText(this, R.string.error_saving, Toast.LENGTH_SHORT).show()
            }
            finish()
        }

        // Handle shared text from other apps
        if (intent?.action == android.content.Intent.ACTION_SEND) {
            intent.getStringExtra(android.content.Intent.EXTRA_TEXT)?.let {
                captureInput.setText(it)
            }
        }
    }
}
