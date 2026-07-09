package com.obsidianwidget

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.ColorUtils
import androidx.core.widget.doAfterTextChanged

/**
 * Edit or delete a single checklist task from the home-screen widget.
 */
class TaskEditActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportRequestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        setContentView(R.layout.activity_task_edit)

        window.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        )
        window.setLayout(
            (resources.displayMetrics.widthPixels * 0.92f).toInt().coerceAtMost(
                (400 * resources.displayMetrics.density).toInt()
            ),
            WindowManager.LayoutParams.WRAP_CONTENT
        )

        val widgetId = intent.getIntExtra(ObsidianWidgetProvider.EXTRA_WIDGET_ID, -1)
        val lineIndex = intent.getIntExtra(ObsidianWidgetProvider.EXTRA_LINE_INDEX, -1)
        if (widgetId < 0 || lineIndex < 0) {
            finish()
            return
        }

        val vaultManager = VaultManager(this, widgetId)
        val existing = vaultManager.getChecklistItemText(lineIndex)
        if (existing == null) {
            Toast.makeText(this, "Task not found", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val input = findViewById<EditText>(R.id.task_edit_input)
        val saveBtn = findViewById<Button>(R.id.task_edit_save)
        val cancelBtn = findViewById<Button>(R.id.task_edit_cancel)
        val deleteBtn = findViewById<Button>(R.id.task_edit_delete)
        val title = findViewById<TextView>(R.id.task_edit_title)

        title.text = "Edit task"
        input.setText(existing)
        input.setSelection(existing.length)
        input.inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        input.isSingleLine = false
        input.setHorizontallyScrolling(false)
        input.minLines = 3
        input.maxLines = 8

        applyAccent(vaultManager, saveBtn, cancelBtn, deleteBtn)

        saveBtn.isEnabled = existing.isNotBlank()
        input.doAfterTextChanged {
            saveBtn.isEnabled = !it.isNullOrBlank()
            saveBtn.alpha = if (saveBtn.isEnabled) 1f else 0.45f
        }

        cancelBtn.setOnClickListener { finish() }

        saveBtn.setOnClickListener {
            val text = input.text.toString().trim()
            if (text.isEmpty()) {
                confirmDelete(vaultManager, widgetId, lineIndex)
                return@setOnClickListener
            }
            val ok = vaultManager.updateChecklistItemText(lineIndex, text)
            if (ok) {
                Toast.makeText(this, "Task updated", Toast.LENGTH_SHORT).show()
                ObsidianWidgetProvider.refreshWidget(this, widgetId)
            } else {
                Toast.makeText(this, R.string.error_saving, Toast.LENGTH_SHORT).show()
            }
            finish()
        }

        deleteBtn.setOnClickListener {
            confirmDelete(vaultManager, widgetId, lineIndex)
        }

        input.post {
            input.requestFocus()
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun confirmDelete(vaultManager: VaultManager, widgetId: Int, lineIndex: Int) {
        AlertDialog.Builder(this)
            .setTitle("Delete task?")
            .setMessage("This removes the task from your note permanently.")
            .setPositiveButton("Delete") { _, _ ->
                val ok = vaultManager.deleteChecklistItem(lineIndex)
                if (ok) {
                    Toast.makeText(this, "Task deleted", Toast.LENGTH_SHORT).show()
                    ObsidianWidgetProvider.refreshWidget(this, widgetId)
                } else {
                    Toast.makeText(this, R.string.error_saving, Toast.LENGTH_SHORT).show()
                }
                finish()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun applyAccent(
        vm: VaultManager,
        saveBtn: Button,
        cancelBtn: Button,
        deleteBtn: Button
    ) {
        val accent = try {
            Color.parseColor(vm.accentColor)
        } catch (_: Exception) {
            Color.parseColor("#D97757")
        }
        val density = resources.displayMetrics.density
        saveBtn.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 22f * density
            setColor(accent)
        }
        saveBtn.backgroundTintList = ColorStateList.valueOf(accent)
        cancelBtn.backgroundTintList =
            ColorStateList.valueOf(ColorUtils.setAlphaComponent(Color.WHITE, 18))
        deleteBtn.backgroundTintList =
            ColorStateList.valueOf(ColorUtils.setAlphaComponent(0xFFE57373.toInt(), 40))
    }
}
