package com.android.gguf_llama_jin.data.catalog

import com.android.gguf_llama_jin.core.AppResult

interface ModelSource {
    suspend fun fetchCatalog(): AppResult<List<CatalogModel>>
}

class HuggingFaceModelSource(
    private val api: HfCatalogApi = HfCatalogApi()
) : ModelSource {
    override suspend fun fetchCatalog(): AppResult<List<CatalogModel>> = api.searchModels()
}
