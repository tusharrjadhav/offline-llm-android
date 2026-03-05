package com.android.gguf_llama_jin.data.websearch

import com.android.gguf_llama_jin.core.AppLogger
import com.android.gguf_llama_jin.core.AppResult
import com.android.gguf_llama_jin.data.network.AppHttpClient
import com.android.gguf_llama_jin.data.websearch.dto.TavilySearchRequestDto
import com.android.gguf_llama_jin.data.websearch.dto.TavilySearchResponseDto
import com.android.gguf_llama_jin.inference_module.BuildConfig
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

class TavilyWebSearchProvider(
    private val apiKey: String = BuildConfig.TAVILY_API_KEY,
    private val endpoint: String = "https://api.tavily.com/search"
) : WebSearchProvider {

    override suspend fun search(query: String, limit: Int): AppResult<List<WebSearchHit>> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            AppLogger.e("Tavily search skipped: missing API key")
            return@withContext AppResult.Error("Missing Tavily API key")
        }

        val safeLimit = limit.coerceIn(1, 5)
        AppLogger.i("Tavily search start: endpoint=$endpoint queryLen=${query.length} limit=$safeLimit")

        return@withContext try {
            val response = withTimeout(8_000) {
                AppHttpClient.client.post {
                    url(endpoint)
                    setBody(
                        TavilySearchRequestDto(
                            apiKey = apiKey,
                            query = query,
                            maxResults = safeLimit
                        )
                    )
                }
            }

            AppLogger.i("Tavily search HTTP response: code=${response.status.value}")
            if (response.status.value !in 200..299) {
                AppLogger.e("Tavily search failed with HTTP ${response.status.value}")
                AppResult.Error("Web search failed: HTTP ${response.status.value}")
            } else {
                try {
                    val payload = response.body<TavilySearchResponseDto>()
                    val mapped = mapResponse(payload, safeLimit)
                    AppLogger.i("Tavily search success: hits=${mapped.size}")
                    AppResult.Success(mapped)
                } catch (t: Throwable) {
                    AppLogger.e("Tavily search parse error", t)
                    val bodyPreview = response.bodyAsText().take(180)
                    AppLogger.i("Tavily parse payload preview: $bodyPreview")
                    AppResult.Error("Web search parse error")
                }
            }
        } catch (t: Throwable) {
            AppLogger.e("Tavily search request failed", t)
            AppResult.Error("Web search request failed")
        }
    }

    companion object {
        internal fun mapResponse(body: TavilySearchResponseDto, limit: Int): List<WebSearchHit> {
            val dedupe = linkedSetOf<String>()
            val mapped = mutableListOf<WebSearchHit>()

            for (entry in body.results) {
                val url = entry.url?.trim().orEmpty()
                if (url.isBlank() || !dedupe.add(url)) continue

                val title = entry.title?.trim().orEmpty()
                val content = entry.content?.trim().orEmpty()
                val snippet = content.replace(Regex("\\s+"), " ").take(320)

                mapped += WebSearchHit(
                    title = if (title.isBlank()) "Untitled" else title,
                    url = url,
                    snippet = snippet,
                    source = "Tavily"
                )
                if (mapped.size >= limit) break
            }
            return mapped
        }
    }
}
