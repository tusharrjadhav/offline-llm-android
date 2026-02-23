package com.android.gguf_llama_jin

import com.android.gguf_llama_jin.data.websearch.MockWebSearchProvider
import com.android.gguf_llama_jin.data.websearch.TavilyWebSearchProvider
import com.android.gguf_llama_jin.data.websearch.WebSearchProviderFactory
import org.junit.Assert.assertTrue
import org.junit.Test

class WebSearchProviderFactoryTest {
    @Test
    fun create_returns_mock_when_key_missing() {
        val provider = WebSearchProviderFactory.create(apiKey = "")
        assertTrue(provider is MockWebSearchProvider)
    }

    @Test
    fun create_returns_tavily_when_key_present() {
        val provider = WebSearchProviderFactory.create(apiKey = "abc")
        assertTrue(provider is TavilyWebSearchProvider)
    }
}

