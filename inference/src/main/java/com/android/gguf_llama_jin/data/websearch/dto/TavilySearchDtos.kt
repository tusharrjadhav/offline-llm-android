package com.android.gguf_llama_jin.data.websearch.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class TavilySearchRequestDto(
    @SerialName("api_key")
    val apiKey: String,
    @SerialName("query")
    val query: String,
    @SerialName("search_depth")
    val searchDepth: String = "basic",
    @SerialName("include_answer")
    val includeAnswer: Boolean = false,
    @SerialName("include_raw_content")
    val includeRawContent: Boolean = false,
    @SerialName("max_results")
    val maxResults: Int
)

@Serializable
data class TavilySearchResponseDto(
    @SerialName("results")
    val results: List<TavilyResultDto> = emptyList()
)

@Serializable
data class TavilyResultDto(
    @SerialName("title")
    val title: String? = null,
    @SerialName("url")
    val url: String? = null,
    @SerialName("content")
    val content: String? = null,
    @SerialName("score")
    val score: Double? = null
)
