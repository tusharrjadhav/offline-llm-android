package com.android.gguf_llama_jin.data.catalog

data class QuantFile(
    val fileName: String,
    val quant: String,
    val sizeBytes: Long,
    val downloadUrl: String,
    val sha256: String? = null
)

data class CatalogModel(
    val id: String,
    val displayName: String,
    val repo: String,
    val paramsApprox: String,
    val tags: List<String>,
    val license: String?,
    val ggufFiles: List<QuantFile>,
    val recommendedTier: String
)
