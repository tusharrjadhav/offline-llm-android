package com.android.gguf_llama_jin.data.websearch

data class WebSearchHit(
    val title: String,
    val url: String,
    val snippet: String,
    val publishedAt: Long? = null,
    val source: String? = null
)

