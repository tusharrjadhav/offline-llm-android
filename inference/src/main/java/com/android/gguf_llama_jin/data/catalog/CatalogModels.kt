package com.android.gguf_llama_jin.data.catalog

import com.android.gguf_llama_jin.core.ModelRuntime

enum class RemoteFileRole {
    MODEL,
    TOKENIZER,
    CONFIG,
    OTHER
}

data class RemoteFileRef(
    val fileName: String,
    val downloadUrl: String,
    val sizeBytes: Long,
    val sha256: String? = null,
    val role: RemoteFileRole
)

data class ModelVariant(
    val variantId: String,
    val sizeBytes: Long,
    val downloadFiles: List<RemoteFileRef>,
    val metadata: Map<String, String> = emptyMap()
)

data class CatalogRuntimeModel(
    val id: String,
    val displayName: String,
    val repo: String,
    val runtime: ModelRuntime,
    val paramsApprox: String,
    val tags: List<String>,
    val license: String?,
    val variants: List<ModelVariant>,
    val recommendedTier: String,
    val unsupportedReason: String? = null
)

data class RuntimeOption(
    val runtime: ModelRuntime,
    val variants: List<ModelVariant>,
    val recommendedTier: String,
    val unsupportedReason: String? = null
)

data class CatalogRepoModel(
    val id: String,
    val displayName: String,
    val repo: String,
    val paramsApprox: String,
    val tags: List<String>,
    val license: String?,
    val runtimeOptions: Map<ModelRuntime, RuntimeOption>
) {
    val supportedRuntimes: Set<ModelRuntime>
        get() = runtimeOptions.keys
}
