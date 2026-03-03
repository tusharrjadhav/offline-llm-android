package com.android.gguf_llama_jin.domain

import com.android.gguf_llama_jin.core.AppResult
import com.android.gguf_llama_jin.data.catalog.CatalogModel
import com.android.gguf_llama_jin.data.catalog.ModelSource

class ModelCatalogUseCase(private val source: ModelSource) {
    suspend fun execute(): AppResult<List<CatalogModel>> = source.fetchCatalog()
}
