package com.android.gguf_llama_jin.data.websearch

import com.android.gguf_llama_jin.core.AppResult

interface WebSearchProvider {
    suspend fun search(query: String, limit: Int = 3): AppResult<List<WebSearchHit>>
}

