package com.obsidianwidget

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.ColorUtils
import androidx.core.widget.doAfterTextChanged

class QuickCaptureActivity : AppCompatActivity() {

    private lateinit var vaultManager: VaultManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportRequestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        setContentView(R.layout.activity_quick_capture)

        // Keep dialog above keyboard and auto-show IME
        window.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        )
        // Wider, more comfortable dialog on phones
        window.setLayout(
            (resources.displayMetrics.widthPixels * 0.92f).toInt().coerceAtMost(
                (400 * resources.displayMetrics.density).toInt()
            ),
            WindowManager.LayoutParams.WRAP_CONTENT
        )

        vaultManager = VaultManager(this)

        val appendToWidget = intent.getBooleanExtra(ObsidianWidgetProvider.EXTRA_APPEND_TO_WIDGET, false)
        val widgetId = intent.getIntExtra(ObsidianWidgetProvider.EXTRA_WIDGET_ID, -1)
        val widgetVaultManager = if (widgetId >= 0) VaultManager(this, widgetId) else vaultManager
        val captureInput = findViewById<EditText>(R.id.capture_input)
        val captureTitle = findViewById<TextView>(R.id.capture_title)
        val captureSubtitle = findViewById<TextView>(R.id.capture_subtitle)
        val addToTopToggle = findViewById<Switch>(R.id.capture_add_to_top)
        val addToTopRow = findViewById<View>(R.id.capture_add_to_top_row)
        val saveBtn = findViewById<Button>(R.id.btn_save)
        val cancelBtn = findViewById<Button>(R.id.btn_cancel)

        val smartMode = widgetVaultManager.smartTabsEnabled || widgetVaultManager.smartCaptureEnabled

        val targetTab = when {
            appendToWidget -> widgetVaultManager.activeTab.ifBlank { "focus" }
            smartMode -> "notes"
            else -> "notes"
        }

        if (smartMode) {
            addToTopRow.visibility = View.GONE
        } else if (widgetVaultManager.showAddToTop) {
            addToTopRow.visibility = View.VISIBLE
            addToTopToggle.isChecked = widgetVaultManager.addToTop
        } else {
            addToTopRow.visibility = View.GONE
        }

        applyAccent(widgetVaultManager, addToTopToggle, saveBtn, cancelBtn)
        setupMultilineInput(captureInput)

        if (appendToWidget || smartMode) {
            if (smartMode) {
                val heading = if (targetTab == "focus") {
                    widgetVaultManager.taskHeading
                } else {
                    widgetVaultManager.notesHeading
                }
                captureTitle.text = if (targetTab == "focus") "New task" else "New note"
                captureSubtitle.visibility = View.VISIBLE
                captureSubtitle.text = "Saves under ## $heading"
                captureInput.hint = if (targetTab == "focus") {
                    "One task per line…\nBuy eggs\nDrink water"
                } else {
                    "What's on your mind? (one note per line)"
                }
            } else {
                captureTitle.text = "Add to note"
                captureSubtitle.visibility = View.VISIBLE
                captureSubtitle.text = widgetVaultManager.getWidgetTitle()
                captureInput.hint = "Type something to add…"
            }
        } else {
            captureTitle.text = "Quick note"
            captureSubtitle.visibility = View.GONE
            captureInput.hint = "Capture a thought…"
        }

        // Enable Save only when there's real text
        saveBtn.isEnabled = false
        saveBtn.alpha = 0.45f
        captureInput.doAfterTextChanged { text ->
            val hasText = !text.isNullOrBlank()
            saveBtn.isEnabled = hasText
            saveBtn.alpha = if (hasText) 1f else 0.45f
        }

        cancelBtn.setOnClickListener { finish() }

        saveBtn.setOnClickListener {
            saveAndClose(
                captureInput.text.toString().trim(),
                widgetId,
                widgetVaultManager,
                smartMode,
                targetTab,
                appendToWidget,
                addToTopToggle.isChecked
            )
        }

        // Done key on keyboard saves when single-paragraph; Shift+Enter still newlines via multiline
        captureInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE && saveBtn.isEnabled) {
                saveBtn.performClick()
                true
            } else {
                false
            }
        }

        if (intent?.action == android.content.Intent.ACTION_SEND) {
            intent.getStringExtra(android.content.Intent.EXTRA_TEXT)?.let {
                captureInput.setText(it)
                captureInput.setSelection(it.length)
            }
        }

        // Focus + show keyboard after layout
        captureInput.post {
            captureInput.requestFocus()
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(captureInput, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    /**
     * Proper growing multiline field — never clamp to icon size / single-line width.
     */
    private fun setupMultilineInput(input: EditText) {
        input.inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
            InputType.TYPE_TEXT_FLAG_AUTO_CORRECT
        input.isSingleLine = false
        input.setHorizontallyScrolling(false) // wrap lines instead of scrolling sideways
        input.minLines = 5
        input.maxLines = 12
        input.setRawInputType(
            InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        )
        // Ensure wrap_content height is used (XML minHeight handles empty size)
        input.layoutParams = input.layoutParams.apply {
            height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        }
    }

    private fun saveAndClose(
        text: String,
        widgetId: Int,
        widgetVaultManager: VaultManager,
        smartMode: Boolean,
        targetTab: String,
        appendToWidget: Boolean,
        addToTop: Boolean
    ) {
        if (text.isBlank()) {
            finish()
            return
        }

        val vm = if (widgetId >= 0) widgetVaultManager else vaultManager
        if (!vm.isVaultConfigured) {
            Toast.makeText(this, R.string.vault_not_configured, Toast.LENGTH_SHORT).show()
            return
        }

        val success = if (smartMode) {
            vm.writeSmartCapture(text, targetTab)
        } else if (appendToWidget) {
            vm.addToTop = addToTop
            vm.appendToWidgetNote(text)
        } else {
            vm.addToTop = addToTop
            vm.appendToDailyNote(text)
        }

        if (success) {
            Toast.makeText(this, R.string.note_saved, Toast.LENGTH_SHORT).show()
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
            cornerRadius = 22f * density
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
            ColorStateList.valueOf(ColorUtils.setAlphaComponent(Color.WHITE, 18))
    }
}
