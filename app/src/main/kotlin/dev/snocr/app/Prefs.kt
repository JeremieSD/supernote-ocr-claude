package dev.snocr.app

import android.content.Context
import android.content.SharedPreferences
import dev.snocr.claudekit.AiConfig
import dev.snocr.claudekit.AiProvider
import dev.snocr.claudekit.Models

/** App settings stored in private SharedPreferences. */
class Prefs(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var provider: AiProvider
        get() = runCatching { AiProvider.valueOf(prefs.getString(KEY_PROVIDER, "") ?: "") }
            .getOrDefault(AiProvider.ANTHROPIC)
        set(value) = prefs.edit().putString(KEY_PROVIDER, value.name).apply()

    var anthropicKey: String
        get() = prefs.getString(KEY_ANTHROPIC_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_ANTHROPIC_KEY, value.trim()).apply()

    var openRouterKey: String
        get() = prefs.getString(KEY_OPENROUTER_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_OPENROUTER_KEY, value.trim()).apply()

    var anthropicModelId: String
        get() = prefs.getString(KEY_ANTHROPIC_MODEL, null) ?: Models.DEFAULT_ANTHROPIC.id
        set(value) = prefs.edit().putString(KEY_ANTHROPIC_MODEL, value).apply()

    var openRouterModelId: String
        get() = prefs.getString(KEY_OPENROUTER_MODEL, null) ?: Models.DEFAULT_OPENROUTER.id
        set(value) = prefs.edit().putString(KEY_OPENROUTER_MODEL, value.trim()).apply()

    var maxPages: Int
        get() = prefs.getInt(KEY_MAX_PAGES, DEFAULT_MAX_PAGES)
        set(value) = prefs.edit().putInt(KEY_MAX_PAGES, value.coerceIn(1, MAX_MAX_PAGES)).apply()

    /** The API key for the currently selected provider. */
    val activeKey: String
        get() = if (provider == AiProvider.ANTHROPIC) anthropicKey else openRouterKey

    /** The model id for the currently selected provider. */
    val activeModelId: String
        get() = if (provider == AiProvider.ANTHROPIC) anthropicModelId else openRouterModelId

    fun aiConfig(): AiConfig = AiConfig(
        provider = provider,
        apiKey = activeKey,
        modelId = activeModelId,
    )

    companion object {
        private const val KEY_PROVIDER = "provider"
        private const val KEY_ANTHROPIC_KEY = "api_key" // legacy name; keeps existing keys
        private const val KEY_OPENROUTER_KEY = "openrouter_key"
        private const val KEY_ANTHROPIC_MODEL = "model"
        private const val KEY_OPENROUTER_MODEL = "openrouter_model"
        private const val KEY_MAX_PAGES = "max_pages"
        const val DEFAULT_MAX_PAGES = 20
        const val MAX_MAX_PAGES = 40
    }
}
