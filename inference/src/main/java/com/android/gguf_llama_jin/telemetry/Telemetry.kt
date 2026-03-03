package com.android.gguf_llama_jin.telemetry

import android.content.Context
import com.android.gguf_llama_jin.core.Constants

class Telemetry(private val context: Context) {
    fun isEnabled(): Boolean {
        return prefs().getBoolean(KEY_TELEMETRY, false)
    }

    fun setEnabled(enabled: Boolean) {
        prefs().edit().putBoolean(KEY_TELEMETRY, enabled).apply()
    }

    fun track(name: String, payload: Map<String, String> = emptyMap()) {
        if (!isEnabled()) return
        // Local-only stub: integrate Crashlytics/Sentry later with explicit no-prompt-content rule.
        android.util.Log.i("Telemetry", "event=$name keys=${payload.keys}")
    }

    private fun prefs() = context.getSharedPreferences(Constants.SETTINGS_PREFS, Context.MODE_PRIVATE)

    companion object {
        const val KEY_TELEMETRY = "telemetry_enabled"
    }
}
