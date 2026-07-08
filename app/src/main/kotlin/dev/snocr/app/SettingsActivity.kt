package dev.snocr.app

import android.os.Bundle
import android.os.Environment
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import dev.snocr.claudekit.AiConfig
import dev.snocr.claudekit.AiConversation
import dev.snocr.claudekit.AiProvider
import dev.snocr.claudekit.Models
import java.io.File
import kotlin.concurrent.thread

class SettingsActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var providerGroup: RadioGroup
    private lateinit var keyLabel: TextView
    private lateinit var keyInput: EditText
    private lateinit var anthropicModelGroup: RadioGroup
    private lateinit var openRouterModelGroup: RadioGroup
    private lateinit var openRouterBox: View
    private lateinit var customModelInput: EditText
    private lateinit var maxPagesInput: EditText
    private lateinit var statusView: TextView

    // Buffer both providers' keys so switching the radio doesn't lose a typed key.
    private var anthropicKeyBuf = ""
    private var openRouterKeyBuf = ""
    private var shownProvider = AiProvider.ANTHROPIC

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        prefs = Prefs(this)

        providerGroup = findViewById(R.id.provider_group)
        keyLabel = findViewById(R.id.key_label)
        keyInput = findViewById(R.id.api_key_input)
        anthropicModelGroup = findViewById(R.id.anthropic_model_group)
        openRouterModelGroup = findViewById(R.id.openrouter_model_group)
        openRouterBox = findViewById(R.id.openrouter_box)
        customModelInput = findViewById(R.id.custom_model_input)
        maxPagesInput = findViewById(R.id.max_pages_input)
        statusView = findViewById(R.id.settings_status)

        anthropicKeyBuf = prefs.anthropicKey
        openRouterKeyBuf = prefs.openRouterKey
        shownProvider = prefs.provider

        buildProviderRadios()
        buildAnthropicModelRadios()
        buildOpenRouterModelRadios()
        maxPagesInput.setText(prefs.maxPages.toString())

        applyProvider(shownProvider, initial = true)

        findViewById<Button>(R.id.save_button).setOnClickListener { save() }
        findViewById<Button>(R.id.test_key_button).setOnClickListener { testKey() }
        findViewById<Button>(R.id.import_key_button).setOnClickListener { importKeyFromFile() }
    }

    private fun buildProviderRadios() {
        for (provider in AiProvider.entries) {
            val radio = RadioButton(this)
            radio.text = provider.displayName
            radio.tag = provider
            radio.id = View.generateViewId()
            radio.textSize = 16f
            providerGroup.addView(radio)
            if (provider == shownProvider) providerGroup.check(radio.id)
        }
        providerGroup.setOnCheckedChangeListener { _, checkedId ->
            val chosen = findViewById<RadioButton>(checkedId)?.tag as? AiProvider ?: return@setOnCheckedChangeListener
            bufferCurrentKey()
            applyProvider(chosen, initial = false)
        }
    }

    private fun buildAnthropicModelRadios() {
        for (model in Models.ANTHROPIC) {
            val radio = RadioButton(this)
            radio.text = model.displayName
            radio.tag = model.id
            radio.id = View.generateViewId()
            radio.textSize = 16f
            anthropicModelGroup.addView(radio)
            if (model.id == prefs.anthropicModelId) anthropicModelGroup.check(radio.id)
        }
        if (anthropicModelGroup.checkedRadioButtonId == -1 && anthropicModelGroup.childCount > 0) {
            anthropicModelGroup.check(anthropicModelGroup.getChildAt(0).id)
        }
    }

    private fun buildOpenRouterModelRadios() {
        val stored = prefs.openRouterModelId
        var matched = false
        for (model in Models.OPENROUTER) {
            val radio = RadioButton(this)
            radio.text = model.displayName
            radio.tag = model.id
            radio.id = View.generateViewId()
            radio.textSize = 16f
            openRouterModelGroup.addView(radio)
            if (model.id == stored) {
                openRouterModelGroup.check(radio.id)
                matched = true
            }
        }
        val customRadio = RadioButton(this)
        customRadio.text = getString(R.string.custom_model)
        customRadio.tag = TAG_CUSTOM
        customRadio.id = View.generateViewId()
        customRadio.textSize = 16f
        openRouterModelGroup.addView(customRadio)

        openRouterModelGroup.setOnCheckedChangeListener { _, checkedId ->
            val isCustom = findViewById<RadioButton>(checkedId)?.tag == TAG_CUSTOM
            customModelInput.visibility = if (isCustom) View.VISIBLE else View.GONE
        }
        if (!matched) {
            openRouterModelGroup.check(customRadio.id)
            customModelInput.setText(stored)
            customModelInput.visibility = View.VISIBLE
        } else {
            customModelInput.visibility = View.GONE
        }
    }

    private fun bufferCurrentKey() {
        if (shownProvider == AiProvider.ANTHROPIC) anthropicKeyBuf = keyInput.text.toString()
        else openRouterKeyBuf = keyInput.text.toString()
    }

    private fun applyProvider(provider: AiProvider, initial: Boolean) {
        shownProvider = provider
        val anthropic = provider == AiProvider.ANTHROPIC
        keyLabel.text = getString(if (anthropic) R.string.anthropic_key_label else R.string.openrouter_key_label)
        keyInput.setText(if (anthropic) anthropicKeyBuf else openRouterKeyBuf)
        keyInput.hint = if (anthropic) getString(R.string.api_key_hint) else getString(R.string.openrouter_key_hint)
        anthropicModelGroup.visibility = if (anthropic) View.VISIBLE else View.GONE
        openRouterBox.visibility = if (anthropic) View.GONE else View.VISIBLE
        if (!initial) statusView.text = ""
    }

    private fun currentModelId(): String {
        return if (shownProvider == AiProvider.ANTHROPIC) {
            (findViewById<RadioButton?>(anthropicModelGroup.checkedRadioButtonId)?.tag as? String)
                ?: Models.DEFAULT_ANTHROPIC.id
        } else {
            val checked = findViewById<RadioButton?>(openRouterModelGroup.checkedRadioButtonId)
            when (val tag = checked?.tag) {
                TAG_CUSTOM -> customModelInput.text.toString().trim().ifEmpty { Models.DEFAULT_OPENROUTER.id }
                is String -> tag
                else -> Models.DEFAULT_OPENROUTER.id
            }
        }
    }

    private fun save() {
        bufferCurrentKey()
        prefs.provider = shownProvider
        prefs.anthropicKey = anthropicKeyBuf
        prefs.openRouterKey = openRouterKeyBuf
        if (shownProvider == AiProvider.ANTHROPIC) {
            prefs.anthropicModelId = currentModelId()
        } else {
            prefs.openRouterModelId = currentModelId()
        }
        prefs.maxPages = maxPagesInput.text.toString().toIntOrNull() ?: Prefs.DEFAULT_MAX_PAGES
        Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun testKey() {
        val key = keyInput.text.toString().trim()
        if (key.isEmpty()) {
            statusView.text = getString(R.string.api_key_missing)
            return
        }
        statusView.text = getString(R.string.testing_key)
        val config = AiConfig(provider = shownProvider, apiKey = key, modelId = currentModelId())
        thread {
            val error = AiConversation.testApiKey(config)
            runOnUiThread { statusView.text = error ?: getString(R.string.key_valid) }
        }
    }

    /** Load a key from a text file on storage — easier than typing on e-ink. */
    private fun importKeyFromFile() {
        val name = if (shownProvider == AiProvider.ANTHROPIC) ANTHROPIC_KEY_FILE else OPENROUTER_KEY_FILE
        val file = File(Environment.getExternalStorageDirectory(), name)
        if (!file.isFile) {
            statusView.text = getString(R.string.key_file_missing, file.absolutePath)
            return
        }
        val key = file.readText().trim()
        if (key.isEmpty()) {
            statusView.text = getString(R.string.key_file_empty)
            return
        }
        keyInput.setText(key)
        statusView.text = getString(R.string.key_imported)
    }

    companion object {
        private const val TAG_CUSTOM = "__custom__"
        private const val ANTHROPIC_KEY_FILE = "claude_api_key.txt"
        private const val OPENROUTER_KEY_FILE = "openrouter_api_key.txt"
    }
}
