package com.android.gguf_llama_jin.data.websearch

import com.android.gguf_llama_jin.inference_module.BuildConfig

object WebSearchProviderFactory {
    fun create(apiKey: String = BuildConfig.TAVILY_API_KEY): WebSearchProvider {
        return if (apiKey.isBlank()) {
            MockWebSearchProvider()
        } else {
            TavilyWebSearchProvider(apiKey = apiKey)
        }
    }
}
