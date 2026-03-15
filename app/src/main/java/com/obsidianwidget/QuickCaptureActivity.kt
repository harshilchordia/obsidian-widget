package com.obsidianwidget

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class QuickCaptureActivity : AppCompatActivity() {

    private lateinit var vaultManager: VaultManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_quick_capture)

        vaultManager = VaultManager(this)

        val captureInput = findViewById<EditText>(R.id.capture_input)

        findViewById<Button>(R.id.btn_cancel).setOnClickListener {
            finish()
        }

        findViewById<Button>(R.id.btn_save).setOnClickListener {
            val text = captureInput.text.toString().trim()
            if (text.isBlank()) {
                finish()
                return@setOnClickListener
            }

            val vaultName = vaultManager.vaultName
            if (vaultName == null) {
                Toast.makeText(this, R.string.vault_not_configured, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            appendViaObsidian(vaultName, text)
            Toast.makeText(this, R.string.note_saved, Toast.LENGTH_SHORT).show()
            finish()
        }

        // Handle shared text from other apps
        if (intent?.action == Intent.ACTION_SEND) {
            intent.getStringExtra(Intent.EXTRA_TEXT)?.let {
                captureInput.setText(it)
            }
        }
    }

    /**
     * Append text to today's daily note via Obsidian URI.
     * Uses obsidian://new with append=true — Obsidian handles
     * creating the note (with template if configured) or appending.
     */
    private fun appendViaObsidian(vaultName: String, text: String) {
        val folder = vaultManager.dailyFolder
        val date = LocalDate.now()
            .format(DateTimeFormatter.ofPattern(vaultManager.dateFormat))
        val filePath = if (folder.isNotBlank()) "$folder/$date" else date

        val uri = Uri.Builder()
            .scheme("obsidian")
            .authority("new")
            .appendQueryParameter("vault", vaultName)
            .appendQueryParameter("file", filePath)
            .appendQueryParameter("content", "\n$text")
            .appendQueryParameter("append", "true")
            .build()

        try {
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(this, R.string.error_saving, Toast.LENGTH_SHORT).show()
        }
    }
}
