package com.android.gguf_llama_jin.data.catalog

import com.android.gguf_llama_jin.core.AppResult
import com.android.gguf_llama_jin.core.ModelRuntime

interface ModelSource {
    val runtime: ModelRuntime
    suspend fun fetchCatalog(): AppResult<List<CatalogRuntimeModel>>
}

class HuggingFaceModelSource(
    override val runtime: ModelRuntime,
    private val api: HfCatalogApi = HfCatalogApi()
) : ModelSource {
    override suspend fun fetchCatalog(): AppResult<List<CatalogRuntimeModel>> = api.searchModels(runtime)
}
