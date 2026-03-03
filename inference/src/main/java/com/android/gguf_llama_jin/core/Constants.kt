package com.android.gguf_llama_jin.core

object Constants {
    const val HF_API_BASE = "https://huggingface.co/api"
    const val USER_AGENT = "GGUF_llama_JIN/1.0"
    const val DOWNLOAD_BUFFER_SIZE = 64 * 1024
    const val MODELS_DIR = "models"
    const val REGISTRY_FILE = "model_registry.json"
    const val SETTINGS_PREFS = "gguf_settings"
    const val DOWNLOAD_CHANNEL_ID = "model_downloads"
    const val DOWNLOAD_NOTIFICATION_ID = 7001
    const val DEFAULT_QUANT = "Q4_K_M"
}
