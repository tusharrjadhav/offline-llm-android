package com.android.gguf_llama_jin.data.websearch

import com.android.gguf_llama_jin.core.AppResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

class MockWebSearchProvider : WebSearchProvider {
    override suspend fun search(query: String, limit: Int): AppResult<List<WebSearchHit>> = withContext(Dispatchers.IO) {
        delay(180)
        val seed = query.trim().ifBlank { "query" }
        val base = listOf(
            WebSearchHit(
                title = "$seed - Overview",
                url = "https://example.com/search/overview",
                snippet = "Overview context for \"$seed\" from a mock source. Replace with provider integration.",
                source = "Example"
            ),
            WebSearchHit(
                title = "$seed - Latest",
                url = "https://example.com/search/latest",
                snippet = "Latest updates related to \"$seed\". Mock content for search pipeline validation.",
                source = "Example"
            ),
            WebSearchHit(
                title = "$seed - Reference",
                url = "https://example.com/search/reference",
                snippet = "Reference details for \"$seed\" with citation-ready text snippets.",
                source = "Example"
            )
        )
        AppResult.Success(base.take(limit.coerceAtLeast(1)))
    }
}

