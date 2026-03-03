package com.android.gguf_llama_jin.data.websearch

import com.android.gguf_llama_jin.inference_module.BuildConfig
import com.android.gguf_llama_jin.core.AppResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.HttpURLConnection
import java.net.URL

class TavilyWebSearchProvider(
    private val apiKey: String = BuildConfig.TAVILY_API_KEY,
    private val endpoint: String = "https://api.tavily.com/search"
) : WebSearchProvider {

    override suspend fun search(query: String, limit: Int): AppResult<List<WebSearchHit>> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            return@withContext AppResult.Error("Missing Tavily API key")
        }

        val safeLimit = limit.coerceIn(1, 5)
        val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 8_000
            readTimeout = 8_000
            doInput = true
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
        }

        return@withContext try {
            val requestBody = buildJsonObject {
                put("api_key", apiKey)
                put("query", query)
                put("search_depth", "basic")
                put("include_answer", false)
                put("include_raw_content", false)
                put("max_results", safeLimit)
            }.toString()

            conn.outputStream.use { os ->
                os.write(requestBody.toByteArray(Charsets.UTF_8))
            }

            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()
                ?.use { it.readText() }
                .orEmpty()

            if (code !in 200..299) {
                AppResult.Error("Web search failed: HTTP $code")
            } else {
                try {
                    AppResult.Success(mapResponse(body, safeLimit))
                } catch (_: Throwable) {
                    AppResult.Error("Web search parse error")
                }
            }
        } catch (_: Throwable) {
            AppResult.Error("Web search request failed")
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        internal fun mapResponse(body: String, limit: Int): List<WebSearchHit> {
            val root = Json.parseToJsonElement(body).jsonObject
            val results = root["results"]?.jsonArray.orEmpty()
            val dedupe = linkedSetOf<String>()
            val mapped = mutableListOf<WebSearchHit>()

            for (entry in results) {
                val obj = entry.jsonObject
                val url = obj["url"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                if (url.isBlank() || !dedupe.add(url)) continue

                val title = obj["title"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                val content = obj["content"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
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
