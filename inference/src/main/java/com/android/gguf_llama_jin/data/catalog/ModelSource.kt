package com.android.gguf_llama_jin.data.catalog

import com.android.gguf_llama_jin.core.AppResult
import com.android.gguf_llama_jin.core.ModelRuntime

interface ModelSource {
    val runtime: ModelRuntime
    suspend fun fetchCatalog(): AppResult<List<CatalogModel>>
}
