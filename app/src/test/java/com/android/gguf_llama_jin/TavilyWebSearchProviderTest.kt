package com.android.gguf_llama_jin

import com.android.gguf_llama_jin.data.websearch.TavilyWebSearchProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TavilyWebSearchProviderTest {
    @Test
    fun mapResponse_maps_and_dedupes_urls() {
        val json = """
            {
              "results": [
                {"title":"A","url":"https://a.com","content":"alpha content"},
                {"title":"B","url":"https://b.com","content":"beta content"},
                {"title":"A2","url":"https://a.com","content":"duplicate content"}
              ]
            }
        """.trimIndent()

        val hits = TavilyWebSearchProvider.mapResponse(json, limit = 3)
        assertEquals(2, hits.size)
        assertEquals("https://a.com", hits[0].url)
        assertEquals("https://b.com", hits[1].url)
        assertEquals("Tavily", hits[0].source)
    }

    @Test
    fun mapResponse_skips_empty_url() {
        val json = """
            {
              "results": [
                {"title":"No URL","url":"","content":"x"},
                {"title":"Ok","url":"https://ok.com","content":"y"}
              ]
            }
        """.trimIndent()

        val hits = TavilyWebSearchProvider.mapResponse(json, limit = 3)
        assertEquals(1, hits.size)
        assertEquals("https://ok.com", hits.first().url)
    }

    @Test
    fun mapResponse_respects_limit() {
        val json = """
            {
              "results": [
                {"title":"1","url":"https://1.com","content":"1"},
                {"title":"2","url":"https://2.com","content":"2"},
                {"title":"3","url":"https://3.com","content":"3"}
              ]
            }
        """.trimIndent()

        val hits = TavilyWebSearchProvider.mapResponse(json, limit = 2)
        assertEquals(2, hits.size)
        assertTrue(hits.all { it.snippet.isNotBlank() })
    }
}

