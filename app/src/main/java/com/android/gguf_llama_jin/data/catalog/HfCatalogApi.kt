package com.android.gguf_llama_jin.data.catalog

import com.android.gguf_llama_jin.core.AppLogger
import com.android.gguf_llama_jin.core.AppResult
import com.android.gguf_llama_jin.core.Constants
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

class HfCatalogApi {
    suspend fun searchModels(limit: Int = 16): AppResult<List<CatalogModel>> = withContext(Dispatchers.IO) {
        try {
            val queryUrl =
                "${Constants.HF_API_BASE}/models?search=gguf%20instruct&sort=downloads&direction=-1&limit=$limit"
            val json = getJsonArray(queryUrl)
            val modelIds = mutableListOf<String>()
            for (i in 0 until json.length()) {
                val modelObj = json.optJSONObject(i) ?: continue
                val modelId = modelObj.optString("id")
                if (modelId.isBlank()) continue
                modelIds += modelId
            }

            val semaphore = Semaphore(4)
            val models = coroutineScope {
                modelIds.map { modelId ->
                    async {
                        semaphore.withPermit {
                            fetchModelDetails(modelId)
                        }
                    }
                }.awaitAll().filterNotNull()
            }.filter { it.ggufFiles.isNotEmpty() }

            AppResult.Success(models)
        } catch (t: Throwable) {
            AppLogger.e("Failed catalog fetch", t)
            AppResult.Error("Failed to fetch models", t)
        }
    }

    private suspend fun fetchModelDetails(modelId: String): CatalogModel? = withContext(Dispatchers.IO) {
        val url = "${Constants.HF_API_BASE}/models/$modelId"
        val obj = getJsonObject(url)

        val tagsJson = obj.optJSONArray("tags") ?: JSONArray()
        val tags = buildList {
            for (i in 0 until tagsJson.length()) {
                add(tagsJson.optString(i))
            }
        }

        if (!tags.any { it.contains("instruct", ignoreCase = true) || it.contains("chat", ignoreCase = true) }) {
            return@withContext null
        }

        val siblings = obj.optJSONArray("siblings") ?: JSONArray()
        val baseFiles = mutableListOf<QuantFile>()
        for (i in 0 until siblings.length()) {
            val sibling = siblings.optJSONObject(i) ?: continue
            val fileName = sibling.optString("rfilename")
            if (!fileName.endsWith(".gguf", ignoreCase = true)) continue

            val quant = extractQuant(fileName)
            val lfs = sibling.optJSONObject("lfs")
            val size = when {
                sibling.has("size") && !sibling.isNull("size") -> sibling.optLong("size", 0L)
                lfs != null && lfs.has("size") && !lfs.isNull("size") -> lfs.optLong("size", 0L)
                else -> 0L
            }
            val sha = if (lfs != null && lfs.has("sha256") && !lfs.isNull("sha256")) lfs.optString("sha256") else null

            baseFiles += QuantFile(
                fileName = fileName,
                quant = quant,
                sizeBytes = size,
                downloadUrl = "https://huggingface.co/$modelId/resolve/main/$fileName",
                sha256 = sha
            )
        }

        if (baseFiles.isEmpty()) return@withContext null
        val files = enrichMissingSizesFromTree(modelId, baseFiles)

        val display = modelId.substringAfterLast('/')
        val params = estimateParamSize(display)
        val tier = recommendTierFromSize(files.minOf { it.sizeBytes })

        CatalogModel(
            id = modelId,
            displayName = display,
            repo = modelId,
            paramsApprox = params,
            tags = tags,
            license = if (obj.has("license") && !obj.isNull("license")) obj.optString("license") else null,
            ggufFiles = files.sortedBy { it.sizeBytes },
            recommendedTier = tier
        )
    }

    private fun estimateParamSize(name: String): String {
        val lower = name.lowercase()
        return when {
            "1b" in lower || "2b" in lower || "3b" in lower -> "1-3B"
            "7b" in lower || "8b" in lower -> "7-8B"
            else -> "Unknown"
        }
    }

    private fun recommendTierFromSize(sizeBytes: Long): String {
        if (sizeBytes <= 0L) return "Unknown"
        val gb = sizeBytes / (1024.0 * 1024.0 * 1024.0)
        return when {
            gb <= 2.0 -> "Fast"
            gb <= 5.0 -> "Medium"
            else -> "Slow"
        }
    }

    private fun extractQuant(fileName: String): String {
        val upper = fileName.uppercase()
        val known = listOf("Q2_K", "Q3_K", "Q4_K_M", "Q4_K_S", "Q5_K_M", "Q5_K_S", "Q6_K", "Q8_0")
        known.firstOrNull { upper.contains(it) }?.let { return it }

        val tokens = upper.split(Regex("[^A-Z0-9_]+"))
        val generic = tokens.firstOrNull { it.matches(Regex("I?Q\\d+[A-Z0-9_]*")) }
        if (!generic.isNullOrBlank()) return generic

        return "GGUF"
    }

    private fun enrichMissingSizesFromTree(modelId: String, files: List<QuantFile>): List<QuantFile> {
        if (files.none { it.sizeBytes <= 0L }) return files
        return try {
            val tree = getJsonArray("${Constants.HF_API_BASE}/models/$modelId/tree/main?recursive=1&expand=true")
            val sizeByPath = mutableMapOf<String, Pair<Long, String?>>()
            for (i in 0 until tree.length()) {
                val node = tree.optJSONObject(i) ?: continue
                val path = node.optString("path")
                if (path.isBlank()) continue
                val lfs = node.optJSONObject("lfs")
                val size = when {
                    node.has("size") && !node.isNull("size") -> node.optLong("size", 0L)
                    lfs != null && lfs.has("size") && !lfs.isNull("size") -> lfs.optLong("size", 0L)
                    else -> 0L
                }
                val sha = if (lfs != null && lfs.has("sha256") && !lfs.isNull("sha256")) lfs.optString("sha256") else null
                sizeByPath[path] = size to sha
            }

            files.map { file ->
                val fromTree = sizeByPath[file.fileName]
                if (file.sizeBytes > 0L || fromTree == null) {
                    file
                } else {
                    file.copy(
                        sizeBytes = fromTree.first,
                        sha256 = file.sha256 ?: fromTree.second
                    )
                }
            }
        } catch (_: Throwable) {
            files
        }
    }

    private fun getJsonArray(url: String): JSONArray {
        val body = request(url)
        return JSONArray(body)
    }

    private fun getJsonObject(url: String): JSONObject {
        val body = request(url)
        return JSONObject(body)
    }

    private fun request(urlString: String): String {
        val connection = URL(urlString).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", Constants.USER_AGENT)
            connection.setRequestProperty("Accept-Language", Locale.US.toLanguageTag())
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000

            val code = connection.responseCode
            if (code !in 200..299) {
                val err = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                throw IllegalStateException("HTTP $code ${connection.responseMessage}. ${err.take(180)}")
            }
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
}
