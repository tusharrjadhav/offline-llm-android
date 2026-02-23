package com.android.gguf_llama_jin.data.modelstore

import android.content.Context
import com.android.gguf_llama_jin.core.Constants

class AppSettings(private val context: Context) {
    fun chatHistoryEnabled(): Boolean = prefs().getBoolean(KEY_CHAT_HISTORY, true)
    fun setChatHistoryEnabled(value: Boolean) = prefs().edit().putBoolean(KEY_CHAT_HISTORY, value).apply()

    fun wifiOnlyDownloads(): Boolean = prefs().getBoolean(KEY_WIFI_ONLY, true)
    fun setWifiOnlyDownloads(value: Boolean) = prefs().edit().putBoolean(KEY_WIFI_ONLY, value).apply()

    fun defaultModelId(): String? = prefs().getString(KEY_DEFAULT_MODEL_ID, null)
    fun setDefaultModelId(value: String?) = prefs().edit().putString(KEY_DEFAULT_MODEL_ID, value).apply()

    fun webSearchAllowed(): Boolean = prefs().getBoolean(KEY_WEB_SEARCH_ALLOWED, false)
    fun setWebSearchAllowed(value: Boolean) = prefs().edit().putBoolean(KEY_WEB_SEARCH_ALLOWED, value).apply()

    private fun prefs() = context.getSharedPreferences(Constants.SETTINGS_PREFS, Context.MODE_PRIVATE)

    companion object {
        const val KEY_CHAT_HISTORY = "chat_history_enabled"
        const val KEY_WIFI_ONLY = "wifi_only_download"
        const val KEY_DEFAULT_MODEL_ID = "default_model_id"
        const val KEY_WEB_SEARCH_ALLOWED = "web_search_allowed"
    }
}
