package com.android.gguf_llama_jin.data.modelstore

data class InstalledModel(
    val id: String,
    val quant: String,
    val path: String,
    val sizeBytes: Long,
    val sha256: String?,
    val installedAt: Long,
    val lastUsedAt: Long
)
