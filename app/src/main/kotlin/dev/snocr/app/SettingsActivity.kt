package dev.snocr.app

import android.os.Bundle
import android.os.Environment
import android.widget.Button
import android.widget.EditText
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import dev.snocr.claudekit.ClaudeConfig
import dev.snocr.claudekit.ClaudeConversation
import dev.snocr.claudekit.ClaudeModel
import java.io.File
import kotlin.concurrent.thread

class SettingsActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var apiKeyInput: EditText
    private lateinit var modelGroup: RadioGroup
    private lateinit var maxPagesInput: EditText
    private lateinit var statusView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        prefs = Prefs(this)

        apiKeyInput = findViewById(R.id.api_key_input)
        modelGroup = findViewById(R.id.model_group)
        maxPagesInput = findViewById(R.id.max_pages_input)
        statusView = findViewById(R.id.settings_status)

        apiKeyInput.setText(prefs.apiKey)
        maxPagesInput.setText(prefs.maxPages.toString())

        for (model in ClaudeModel.entries) {
            val radio = RadioButton(this)
            radio.text = model.displayName
            radio.tag = model.id
            radio.id = model.ordinal + 1000
            radio.textSize = 16f
            modelGroup.addView(radio)
            if (model.id == prefs.modelId) modelGroup.check(radio.id)
        }

        findViewById<Button>(R.id.save_button).setOnClickListener { save() }
        findViewById<Button>(R.id.test_key_button).setOnClickListener { testKey() }
        findViewById<Button>(R.id.import_key_button).setOnClickListener { importKeyFromFile() }
    }

    private fun save() {
        prefs.apiKey = apiKeyInput.text.toString()
        val checked = findViewById<RadioButton?>(modelGroup.checkedRadioButtonId)
        prefs.modelId = (checked?.tag as? String) ?: ClaudeModel.DEFAULT.id
        prefs.maxPages = maxPagesInput.text.toString().toIntOrNull() ?: Prefs.DEFAULT_MAX_PAGES
        maxPagesInput.setText(prefs.maxPages.toString())
        Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun testKey() {
        val key = apiKeyInput.text.toString().trim()
        if (key.isEmpty()) {
            statusView.text = getString(R.string.api_key_missing)
            return
        }
        statusView.text = getString(R.string.testing_key)
        thread {
            val error = ClaudeConversation.testApiKey(ClaudeConfig(apiKey = key))
            runOnUiThread {
                statusView.text = error ?: getString(R.string.key_valid)
            }
        }
    }

    /**
     * Typing a 100+ character key on an e-ink keyboard is painful; allow
     * dropping it into a text file over USB instead.
     */
    private fun importKeyFromFile() {
        val file = File(Environment.getExternalStorageDirectory(), KEY_FILE_NAME)
        if (!file.isFile) {
            statusView.text = getString(R.string.key_file_missing, file.absolutePath)
            return
        }
        val key = file.readText().trim()
        if (key.isEmpty()) {
            statusView.text = getString(R.string.key_file_empty)
            return
        }
        apiKeyInput.setText(key)
        statusView.text = getString(R.string.key_imported)
    }

    companion object {
        private const val KEY_FILE_NAME = "claude_api_key.txt"
    }
}
