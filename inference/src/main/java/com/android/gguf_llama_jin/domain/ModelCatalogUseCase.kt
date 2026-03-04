package com.android.gguf_llama_jin.domain

import com.android.gguf_llama_jin.core.AppResult
import com.android.gguf_llama_jin.core.ModelRuntime
import com.android.gguf_llama_jin.data.catalog.CatalogRepoModel
import com.android.gguf_llama_jin.data.catalog.HfCatalogApi

class ModelCatalogUseCase(
    private val api: HfCatalogApi = HfCatalogApi()
) {
    suspend fun execute(runtimes: Set<ModelRuntime>): AppResult<List<CatalogRepoModel>> {
        return api.searchRepoModels(runtimes = runtimes)
    }
}
