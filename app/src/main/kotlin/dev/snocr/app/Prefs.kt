package dev.snocr.app

import android.content.Context
import android.content.SharedPreferences
import dev.snocr.claudekit.ClaudeConfig
import dev.snocr.claudekit.ClaudeModel

/** App settings stored in private SharedPreferences. */
class Prefs(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var apiKey: String
        get() = prefs.getString(KEY_API_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_API_KEY, value.trim()).apply()

    var modelId: String
        get() = prefs.getString(KEY_MODEL, ClaudeModel.DEFAULT.id) ?: ClaudeModel.DEFAULT.id
        set(value) = prefs.edit().putString(KEY_MODEL, value).apply()

    var maxPages: Int
        get() = prefs.getInt(KEY_MAX_PAGES, DEFAULT_MAX_PAGES)
        set(value) = prefs.edit().putInt(KEY_MAX_PAGES, value.coerceIn(1, 100)).apply()

    val model: ClaudeModel get() = ClaudeModel.fromId(modelId)

    fun claudeConfig(): ClaudeConfig = ClaudeConfig(apiKey = apiKey, model = model)

    companion object {
        private const val KEY_API_KEY = "api_key"
        private const val KEY_MODEL = "model"
        private const val KEY_MAX_PAGES = "max_pages"
        const val DEFAULT_MAX_PAGES = 20
    }
}
