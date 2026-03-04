package com.android.gguf_llama_jin.domain

import com.android.gguf_llama_jin.core.AppResult
import com.android.gguf_llama_jin.core.ModelRuntime
import com.android.gguf_llama_jin.data.catalog.CatalogModel
import com.android.gguf_llama_jin.data.catalog.HuggingFaceGgufModelSource
import com.android.gguf_llama_jin.data.catalog.HuggingFaceOnnxModelSource
import com.android.gguf_llama_jin.data.catalog.ModelSource

class ModelCatalogUseCase(
    private val ggufSource: ModelSource = HuggingFaceGgufModelSource(),
    private val onnxSource: ModelSource = HuggingFaceOnnxModelSource()
) {
    suspend fun execute(runtime: ModelRuntime?): AppResult<List<CatalogModel>> {
        val sources = when (runtime) {
            ModelRuntime.LLAMA_CPP_GGUF -> listOf(ggufSource)
            ModelRuntime.ONNX -> listOf(onnxSource)
            null -> listOf(ggufSource, onnxSource)
        }

        val merged = mutableListOf<CatalogModel>()
        for (source in sources) {
            when (val result = source.fetchCatalog()) {
                is AppResult.Success -> merged += result.value
                is AppResult.Error -> return result
            }
        }
        return AppResult.Success(merged)
    }
}
