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
        val isQuickCaptureWidget = intent.getBooleanExtra(
            QuickCaptureWidgetProvider.EXTRA_QUICK_CAPTURE_WIDGET, false
        )
        val widgetId = intent.getIntExtra(ObsidianWidgetProvider.EXTRA_WIDGET_ID, -1)
        val widgetVaultManager = if (widgetId >= 0) VaultManager(this, widgetId) else vaultManager
        val captureInput = findViewById<EditText>(R.id.capture_input)
        val captureTitle = findViewById<android.widget.TextView>(R.id.capture_title)
        val addToTopToggle = findViewById<Switch>(R.id.capture_add_to_top)
        val addToTopRow = findViewById<View>(R.id.capture_add_to_top_row)

        // Show/hide add-to-top toggle based on widget config (not for quick-capture widget)
        if (!isQuickCaptureWidget && widgetVaultManager.showAddToTop) {
            addToTopRow.visibility = View.VISIBLE
            addToTopToggle.isChecked = widgetVaultManager.addToTop
        }

        if (isQuickCaptureWidget) {
            captureTitle.text = getString(R.string.quick_capture)
            captureInput.hint = getString(R.string.capture_hint)
        } else if (appendToWidget) {
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

        val saveButton = findViewById<Button>(R.id.btn_save)
        saveButton.setOnClickListener {
            val text = captureInput.text.toString().trim()
            if (text.isBlank()) {
                finish()
                return@setOnClickListener
            }

            if (!vaultManager.isVaultConfigured) {
                Toast.makeText(this, R.string.vault_not_configured, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // The insert does SAF directory traversal plus a full note read/rewrite —
            // run it off the main thread. Disable Save so a second tap can't
            // double-insert while the write is in flight.
            saveButton.isEnabled = false
            val addToTop = addToTopToggle.isChecked
            Thread {
                val result = if (isQuickCaptureWidget) {
                    widgetVaultManager.captureToDailyNote(text)
                } else {
                    widgetVaultManager.addToTop = addToTop
                    val appended = if (appendToWidget) {
                        widgetVaultManager.appendToWidgetNote(text)
                    } else {
                        widgetVaultManager.appendToDailyNote(text)
                    }
                    if (appended) VaultManager.CaptureResult.SUCCESS else VaultManager.CaptureResult.ERROR
                }
                runOnUiThread {
                    if (result == VaultManager.CaptureResult.SUCCESS) {
                        Toast.makeText(applicationContext, R.string.note_saved, Toast.LENGTH_SHORT).show()
                        ObsidianWidgetProvider.updateAllWidgets(applicationContext)
                        finish()
                    } else {
                        val errorRes = when (result) {
                            VaultManager.CaptureResult.PLACEHOLDER_NOT_FOUND ->
                                R.string.error_placeholder_not_found
                            else -> R.string.error_saving
                        }
                        // Keep the activity (and typed text) open so the note isn't lost
                        Toast.makeText(applicationContext, errorRes, Toast.LENGTH_SHORT).show()
                        saveButton.isEnabled = true
                    }
                }
            }.start()
        }

        // Handle shared text from other apps
        if (intent?.action == android.content.Intent.ACTION_SEND) {
            intent.getStringExtra(android.content.Intent.EXTRA_TEXT)?.let {
                captureInput.setText(it)
            }
        }
    }
}
