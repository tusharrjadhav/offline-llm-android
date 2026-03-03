package com.android.gguf_llama_jin.data.catalog

import com.android.gguf_llama_jin.core.AppLogger
import com.android.gguf_llama_jin.core.AppResult
import com.android.gguf_llama_jin.core.Constants
import com.android.gguf_llama_jin.core.ModelRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

class HuggingFaceGgufModelSource(
    private val api: HfCatalogApi = HfCatalogApi()
) : ModelSource {
    override val runtime: ModelRuntime = ModelRuntime.LLAMA_CPP_GGUF
    override suspend fun fetchCatalog(): AppResult<List<CatalogModel>> = api.searchGgufModels()
}

class HuggingFaceOnnxModelSource(
    private val api: HfCatalogApi = HfCatalogApi()
) : ModelSource {
    override val runtime: ModelRuntime = ModelRuntime.ONNX_RUNTIME
    override suspend fun fetchCatalog(): AppResult<List<CatalogModel>> = api.searchOnnxModels()
}

class HfCatalogApi {
    suspend fun searchGgufModels(limit: Int = 16): AppResult<List<CatalogModel>> = withContext(Dispatchers.IO) {
        try {
            val queryUrl =
                "${Constants.HF_API_BASE}/models?search=gguf%20instruct&sort=downloads&direction=-1&limit=$limit"
            val modelIds = extractModelIds(getJsonArray(queryUrl))
            val semaphore = Semaphore(4)
            val models = coroutineScope {
                modelIds.map { modelId ->
                    async { semaphore.withPermit { fetchGgufModelDetails(modelId) } }
                }.awaitAll().filterNotNull()
            }
            AppResult.Success(models)
        } catch (t: Throwable) {
            AppLogger.e("Failed GGUF catalog fetch", t)
            AppResult.Error("Failed to fetch GGUF models", t)
        }
    }

    suspend fun searchOnnxModels(limit: Int = 16): AppResult<List<CatalogModel>> = withContext(Dispatchers.IO) {
        try {
            val primaryUrl =
                "${Constants.HF_API_BASE}/models?search=onnx%20instruct&sort=downloads&direction=-1&limit=${limit.coerceAtLeast(24)}"
            val fallbackUrl =
                "${Constants.HF_API_BASE}/models?library=onnx&pipeline_tag=text-generation&sort=downloads&direction=-1&limit=${limit.coerceAtLeast(24)}"
            val modelIds = (extractModelIds(getJsonArray(primaryUrl)) + extractModelIds(getJsonArray(fallbackUrl)))
                .distinct()
            val semaphore = Semaphore(4)
            val models = coroutineScope {
                modelIds.map { modelId ->
                    async { semaphore.withPermit { fetchOnnxModelDetails(modelId) } }
                }.awaitAll().filterNotNull()
            }
            AppResult.Success(models)
        } catch (t: Throwable) {
            AppLogger.e("Failed ONNX catalog fetch", t)
            AppResult.Error("Failed to fetch ONNX models", t)
        }
    }

    private fun extractModelIds(json: JSONArray): List<String> {
        val modelIds = mutableListOf<String>()
        for (i in 0 until json.length()) {
            val modelObj = json.optJSONObject(i) ?: continue
            val modelId = modelObj.optString("id")
            if (modelId.isNotBlank()) modelIds += modelId
        }
        return modelIds
    }

    private suspend fun fetchGgufModelDetails(modelId: String): CatalogModel? = withContext(Dispatchers.IO) {
        val obj = getJsonObject("${Constants.HF_API_BASE}/models/$modelId")
        val tags = extractTags(obj)
        if (!tags.any { it.contains("instruct", true) || it.contains("chat", true) }) return@withContext null

        val siblings = obj.optJSONArray("siblings") ?: JSONArray()
        val variants = mutableListOf<ModelVariant>()
        for (i in 0 until siblings.length()) {
            val sibling = siblings.optJSONObject(i) ?: continue
            val fileName = sibling.optString("rfilename")
            if (!fileName.endsWith(".gguf", true)) continue

            val quant = extractQuant(fileName)
            val lfs = sibling.optJSONObject("lfs")
            val size = when {
                sibling.has("size") && !sibling.isNull("size") -> sibling.optLong("size", 0L)
                lfs != null && lfs.has("size") && !lfs.isNull("size") -> lfs.optLong("size", 0L)
                else -> 0L
            }
            val sha = if (lfs != null && lfs.has("sha256") && !lfs.isNull("sha256")) lfs.optString("sha256") else null
            val file = RemoteFileRef(
                fileName = fileName,
                downloadUrl = "https://huggingface.co/$modelId/resolve/main/$fileName",
                sizeBytes = size,
                sha256 = sha,
                role = RemoteFileRole.MODEL
            )
            variants += ModelVariant(
                variantId = quant,
                sizeBytes = size,
                downloadFiles = listOf(file)
            )
        }

        if (variants.isEmpty()) return@withContext null
        val display = modelId.substringAfterLast('/')
        CatalogModel(
            id = modelId,
            displayName = display,
            repo = modelId,
            runtime = ModelRuntime.LLAMA_CPP_GGUF,
            paramsApprox = estimateParamSize(display),
            tags = tags,
            license = if (obj.has("license") && !obj.isNull("license")) obj.optString("license") else null,
            variants = variants.sortedBy { it.sizeBytes },
            recommendedTier = recommendTierFromSize(variants.minOf { it.sizeBytes })
        )
    }

    private suspend fun fetchOnnxModelDetails(modelId: String): CatalogModel? = withContext(Dispatchers.IO) {
        val obj = getJsonObject("${Constants.HF_API_BASE}/models/$modelId")
        val tags = extractTags(obj)
        val siblings = obj.optJSONArray("siblings") ?: JSONArray()

        val allFiles = mutableListOf<RemoteFileRef>()
        var modelFile: RemoteFileRef? = null
        var hasTokenizerAsset = false

        for (i in 0 until siblings.length()) {
            val sibling = siblings.optJSONObject(i) ?: continue
            val fileName = sibling.optString("rfilename")
            val lower = fileName.lowercase(Locale.US)
            val lfs = sibling.optJSONObject("lfs")
            val size = when {
                sibling.has("size") && !sibling.isNull("size") -> sibling.optLong("size", 0L)
                lfs != null && lfs.has("size") && !lfs.isNull("size") -> lfs.optLong("size", 0L)
                else -> 0L
            }
            val sha = if (lfs != null && lfs.has("sha256") && !lfs.isNull("sha256")) lfs.optString("sha256") else null

            val role = when {
                lower.endsWith(".onnx") -> RemoteFileRole.MODEL
                lower.endsWith("tokenizer.json") || lower.endsWith("tokenizer.model") -> RemoteFileRole.TOKENIZER
                lower.endsWith("config.json") || lower.endsWith("generation_config.json") || lower.endsWith("tokenizer_config.json") -> RemoteFileRole.CONFIG
                else -> RemoteFileRole.OTHER
            }
            if (role == RemoteFileRole.OTHER) continue

            val file = RemoteFileRef(
                fileName = fileName,
                downloadUrl = "https://huggingface.co/$modelId/resolve/main/$fileName",
                sizeBytes = size,
                sha256 = sha,
                role = role
            )
            allFiles += file
            if (role == RemoteFileRole.MODEL && modelFile == null) modelFile = file
            if (
                role == RemoteFileRole.TOKENIZER ||
                lower.endsWith("vocab.json") ||
                lower.endsWith("tokenizer_config.json")
            ) {
                hasTokenizerAsset = true
            }
        }

        if (modelFile == null || !hasTokenizerAsset) return@withContext null

        val variant = ModelVariant(
            variantId = "ONNX",
            sizeBytes = allFiles.sumOf { it.sizeBytes.coerceAtLeast(0L) },
            downloadFiles = allFiles.sortedBy { it.role.ordinal },
            metadata = mapOf(
                "files" to allFiles.size.toString(),
                "model_file" to (modelFile?.fileName ?: "")
            )
        )

        val display = modelId.substringAfterLast('/')
        CatalogModel(
            id = modelId,
            displayName = display,
            repo = modelId,
            runtime = ModelRuntime.ONNX_RUNTIME,
            paramsApprox = estimateParamSize(display),
            tags = tags,
            license = if (obj.has("license") && !obj.isNull("license")) obj.optString("license") else null,
            variants = listOf(variant),
            recommendedTier = recommendTierFromSize(variant.sizeBytes)
        )
    }

    private fun extractTags(obj: JSONObject): List<String> {
        val tagsJson = obj.optJSONArray("tags") ?: JSONArray()
        return buildList {
            for (i in 0 until tagsJson.length()) add(tagsJson.optString(i))
        }
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

    private fun getJsonArray(url: String): JSONArray = JSONArray(request(url))

    private fun getJsonObject(url: String): JSONObject = JSONObject(request(url))

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
