package com.obsidianwidget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

/**
 * Shared plumbing for widget configuration screens: widget-id/result handling,
 * vault folder selection, accent color swatches and save-on-leave behavior.
 */
abstract class BaseWidgetConfigActivity : AppCompatActivity() {

    protected var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
        private set
    protected var isPickerOpen = false
    protected lateinit var vaultManager: VaultManager
    protected var selectedAccentColor: String = "#D97757"

    protected abstract val colorSwatchIds: List<Int>

    private val folderPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        isPickerOpen = false
        uri?.let { onVaultSelected(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Default result = cancelled (so pressing back cancels widget add)
        setResult(RESULT_CANCELED)

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

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (!isPickerOpen) {
            saveAndFinish()
        }
    }

    protected fun launchVaultPicker() {
        isPickerOpen = true
        folderPicker.launch(null)
    }

    private fun onVaultSelected(uri: Uri) {
        contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        vaultManager.vaultUri = uri
        vaultManager.vaultName = uri.lastPathSegment?.substringAfterLast(':') ?: "Vault"
        onVaultNameChanged(vaultManager.vaultName ?: "Vault")
    }

    /** Update the on-screen vault name after the user picks a folder. */
    protected abstract fun onVaultNameChanged(name: String)

    /** Populate the UI from the current widget's saved settings. */
    protected abstract fun loadSettings()

    /** Persist the UI state and finish the activity. */
    protected abstract fun saveAndFinish()

    protected fun setupColorSwatches() {
        for (id in colorSwatchIds) {
            val swatch = findViewById<View>(id)
            swatch.background = swatchDrawable(swatch.tag as String, isSelected = false)
            swatch.setOnClickListener {
                selectedAccentColor = swatch.tag as String
                highlightSelectedColor()
            }
        }
    }

    protected fun highlightSelectedColor() {
        for (id in colorSwatchIds) {
            val swatch = findViewById<View>(id)
            val colorHex = swatch.tag as String
            swatch.background =
                swatchDrawable(colorHex, colorHex.equals(selectedAccentColor, ignoreCase = true))
        }
    }

    private fun swatchDrawable(colorHex: String, isSelected: Boolean) =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor(colorHex))
            if (isSelected) {
                setStroke(4, Color.WHITE)
            } else {
                setStroke(0, Color.TRANSPARENT)
            }
        }
}
