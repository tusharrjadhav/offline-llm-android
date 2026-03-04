package com.android.gguf_llama_jin.data.modelstore

import android.content.Context
import com.android.gguf_llama_jin.core.Constants
import com.android.gguf_llama_jin.core.ModelRuntime

class AppSettings(private val context: Context) {
    fun chatHistoryEnabled(): Boolean = prefs().getBoolean(KEY_CHAT_HISTORY, true)
    fun setChatHistoryEnabled(value: Boolean) = prefs().edit().putBoolean(KEY_CHAT_HISTORY, value).apply()

    fun wifiOnlyDownloads(): Boolean = prefs().getBoolean(KEY_WIFI_ONLY, true)
    fun setWifiOnlyDownloads(value: Boolean) = prefs().edit().putBoolean(KEY_WIFI_ONLY, value).apply()

    fun defaultModelId(runtime: ModelRuntime): String? = when (runtime) {
        ModelRuntime.LLAMA_CPP_GGUF -> prefs().getString(KEY_DEFAULT_MODEL_ID_LLAMA, null)
        ModelRuntime.ONNX -> prefs().getString(KEY_DEFAULT_MODEL_ID_ONNX, null)
    }

    fun setDefaultModelId(runtime: ModelRuntime, value: String?) {
        val key = when (runtime) {
            ModelRuntime.LLAMA_CPP_GGUF -> KEY_DEFAULT_MODEL_ID_LLAMA
            ModelRuntime.ONNX -> KEY_DEFAULT_MODEL_ID_ONNX
        }
        prefs().edit().putString(key, value).apply()
    }

    fun preferredRuntime(): ModelRuntime {
        val value = prefs().getString(KEY_PREFERRED_RUNTIME, ModelRuntime.LLAMA_CPP_GGUF.name)
        return runCatching { ModelRuntime.valueOf(value ?: ModelRuntime.LLAMA_CPP_GGUF.name) }
            .getOrDefault(ModelRuntime.LLAMA_CPP_GGUF)
    }

    fun setPreferredRuntime(runtime: ModelRuntime) {
        prefs().edit().putString(KEY_PREFERRED_RUNTIME, runtime.name).apply()
    }

    fun migrateLegacyDefaultsIfNeeded() {
        val p = prefs()
        if (!p.contains(KEY_DEFAULT_MODEL_ID_LEGACY)) return
        val legacy = p.getString(KEY_DEFAULT_MODEL_ID_LEGACY, null)
        if (!legacy.isNullOrBlank() && !p.contains(KEY_DEFAULT_MODEL_ID_LLAMA)) {
            p.edit().putString(KEY_DEFAULT_MODEL_ID_LLAMA, legacy).apply()
        }
        p.edit().remove(KEY_DEFAULT_MODEL_ID_LEGACY).apply()
    }

    fun webSearchAllowed(): Boolean = prefs().getBoolean(KEY_WEB_SEARCH_ALLOWED, false)
    fun setWebSearchAllowed(value: Boolean) = prefs().edit().putBoolean(KEY_WEB_SEARCH_ALLOWED, value).apply()

    private fun prefs() = context.getSharedPreferences(Constants.SETTINGS_PREFS, Context.MODE_PRIVATE)

    companion object {
        const val KEY_CHAT_HISTORY = "chat_history_enabled"
        const val KEY_WIFI_ONLY = "wifi_only_download"
        const val KEY_DEFAULT_MODEL_ID_LEGACY = "default_model_id"
        const val KEY_DEFAULT_MODEL_ID_LLAMA = "default_model_id_llama_cpp"
        const val KEY_DEFAULT_MODEL_ID_ONNX = "default_model_id_onnx"
        const val KEY_PREFERRED_RUNTIME = "preferred_runtime"
        const val KEY_WEB_SEARCH_ALLOWED = "web_search_allowed"
    }
}
