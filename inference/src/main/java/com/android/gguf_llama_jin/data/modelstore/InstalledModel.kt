package com.android.gguf_llama_jin.data.modelstore

import com.android.gguf_llama_jin.core.ModelRuntime

data class InstalledModel(
    val id: String,
    val runtime: ModelRuntime,
    val variant: String,
    val path: String,
    val sizeBytes: Long,
    val sha256: String?,
    val installedAt: Long,
    val lastUsedAt: Long,
    val assetDir: String? = null
)
