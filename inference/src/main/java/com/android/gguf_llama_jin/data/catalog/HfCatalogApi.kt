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
import java.net.URLEncoder
import java.net.URL
import java.util.Locale

class HfCatalogApi {
    suspend fun searchRepoModels(
        runtimes: Set<ModelRuntime>,
        limit: Int = 24
    ): AppResult<List<CatalogRepoModel>> = withContext(Dispatchers.IO) {
        try {
            val selected = runtimes.ifEmpty {
                setOf(ModelRuntime.LLAMA_CPP_GGUF, ModelRuntime.ONNX)
            }
            val libraries = selected.map {
                when (it) {
                    ModelRuntime.LLAMA_CPP_GGUF -> "gguf"
                    ModelRuntime.ONNX -> "onnx"
                }
            }.toSet().sorted()
            val pipelineTag = "text-generation"
            val normalizedLimit = limit.coerceAtLeast(24)
            AppLogger.i("HF catalog search start runtimes=$selected filters=$libraries limit=$normalizedLimit")

            val modelIds = linkedSetOf<String>()

            val queryUrl = modelsApiUrlWithFilter(
                filters = libraries.toSet(),
                limit = normalizedLimit,
                pipelineTag = pipelineTag
            )
            val multiSearchIds = extractModelIds(getJsonArray(queryUrl))
            AppLogger.i("HF catalog ids filters=$libraries pipeline=$pipelineTag full=true url=$queryUrl count=${multiSearchIds.size}")
            modelIds += multiSearchIds

            if (modelIds.isEmpty()) {
                libraries.forEach { library ->
                    val perFilterUrl = modelsApiUrlWithFilter(
                        filters = setOf(library),
                        limit = normalizedLimit,
                        pipelineTag = pipelineTag
                    )
                    val ids = extractModelIds(getJsonArray(perFilterUrl))
                    AppLogger.i("HF catalog ids filter=$library pipeline=$pipelineTag full=true url=$perFilterUrl count=${ids.size}")
                    modelIds += ids
                }
            }

            if (modelIds.isEmpty()) {
                AppLogger.i("HF catalog pipeline-filtered fetch returned empty, retrying without pipeline_tag")
                val noPipelineUrl = modelsApiUrlWithFilter(
                    filters = libraries.toSet(),
                    limit = normalizedLimit,
                    pipelineTag = null
                )
                val ids = extractModelIds(getJsonArray(noPipelineUrl))
                AppLogger.i("HF catalog ids filters=$libraries pipeline=<none> full=true url=$noPipelineUrl count=${ids.size}")
                modelIds += ids
            }

            if (modelIds.isEmpty()) {
                libraries.forEach { library ->
                    val perFilterNoPipelineUrl = modelsApiUrlWithFilter(
                        filters = setOf(library),
                        limit = normalizedLimit,
                        pipelineTag = null
                    )
                    val ids = extractModelIds(getJsonArray(perFilterNoPipelineUrl))
                    AppLogger.i("HF catalog ids filter=$library pipeline=<none> full=true url=$perFilterNoPipelineUrl count=${ids.size}")
                    modelIds += ids
                }
            }

            AppLogger.i("HF catalog model ids merged count=${modelIds.size}")
            val semaphore = Semaphore(4)
            val models = coroutineScope {
                modelIds.toList().map { modelId ->
                    async { semaphore.withPermit { fetchRepoModelDetails(modelId, selected) } }
                }.awaitAll().filterNotNull()
            }
            AppLogger.i("HF catalog parsed repo models count=${models.size}")
            AppResult.Success(models.sortedBy { it.displayName.lowercase() })
        } catch (t: Throwable) {
            AppLogger.e("Failed merged catalog fetch", t)
            AppResult.Error("Failed to fetch model catalog", t)
        }
    }

    suspend fun searchModels(runtime: ModelRuntime, limit: Int = 16): AppResult<List<CatalogRuntimeModel>> = withContext(Dispatchers.IO) {
        try {
            val normalizedLimit = limit.coerceAtLeast(24)
            val filter = when (runtime) {
                ModelRuntime.LLAMA_CPP_GGUF -> "gguf"
                ModelRuntime.ONNX -> "onnx"
            }
            val pipelineTag = if (runtime == ModelRuntime.ONNX) "text-generation" else null

            val byLibrary = modelsApiUrlWithFilter(
                filters = setOf(filter),
                limit = normalizedLimit,
                pipelineTag = pipelineTag
            )
            val modelIds = extractModelIds(getJsonArray(byLibrary)).distinct()
            val semaphore = Semaphore(4)
            val models = coroutineScope {
                modelIds.map { modelId ->
                    async {
                        semaphore.withPermit {
                            when (runtime) {
                                ModelRuntime.LLAMA_CPP_GGUF -> fetchGgufModelDetails(modelId)
                                ModelRuntime.ONNX -> fetchOnnxModelDetails(modelId)
                            }
                        }
                    }
                }.awaitAll().filterNotNull()
            }
            AppResult.Success(models)
        } catch (t: Throwable) {
            val runtimeLabel = if (runtime == ModelRuntime.LLAMA_CPP_GGUF) "GGUF" else "ONNX"
            AppLogger.e("Failed $runtimeLabel catalog fetch", t)
            AppResult.Error("Failed to fetch $runtimeLabel models", t)
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

    private fun modelsApiUrlWithFilter(
        filters: Set<String>,
        limit: Int,
        pipelineTag: String? = null
    ): String {
        val query = mutableListOf(
            "filter=${encodeQuery(filters.joinToString(","))}",
            "sort=downloads",
            "direction=-1",
            "limit=$limit",
            "full=true"
        )
        if (!pipelineTag.isNullOrBlank()) query += "pipeline_tag=${encodeQuery(pipelineTag)}"
        return "${Constants.HF_API_BASE}/models?${query.joinToString("&")}"
    }

    private fun encodeQuery(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

    private suspend fun fetchRepoModelDetails(
        modelId: String,
        runtimes: Set<ModelRuntime>
    ): CatalogRepoModel? = withContext(Dispatchers.IO) {
        val obj = getJsonObject("${Constants.HF_API_BASE}/models/$modelId")
        val tags = extractTags(obj)

        val siblings = obj.optJSONArray("siblings") ?: JSONArray()
        val runtimeOptions = mutableMapOf<ModelRuntime, RuntimeOption>()

        if (ModelRuntime.LLAMA_CPP_GGUF in runtimes) {
            val variants = mutableListOf<ModelVariant>()
            for (i in 0 until siblings.length()) {
                val sibling = siblings.optJSONObject(i) ?: continue
                val fileName = sibling.optString("rfilename")
                if (!fileName.endsWith(".gguf", true)) continue
                val lfs = sibling.optJSONObject("lfs")
                val size = when {
                    sibling.has("size") && !sibling.isNull("size") -> sibling.optLong("size", 0L)
                    lfs != null && lfs.has("size") && !lfs.isNull("size") -> lfs.optLong("size", 0L)
                    else -> 0L
                }
                val sha = if (lfs != null && lfs.has("sha256") && !lfs.isNull("sha256")) lfs.optString("sha256") else null
                variants += ModelVariant(
                    variantId = extractQuant(fileName),
                    sizeBytes = size,
                    downloadFiles = listOf(
                        RemoteFileRef(
                            fileName = fileName,
                            downloadUrl = "https://huggingface.co/$modelId/resolve/main/$fileName",
                            sizeBytes = size,
                            sha256 = sha,
                            role = RemoteFileRole.MODEL
                        )
                    )
                )
            }
            if (variants.isNotEmpty()) {
                runtimeOptions[ModelRuntime.LLAMA_CPP_GGUF] = RuntimeOption(
                    runtime = ModelRuntime.LLAMA_CPP_GGUF,
                    variants = variants.sortedBy { it.sizeBytes },
                    recommendedTier = recommendTierFromSize(variants.minOf { it.sizeBytes })
                )
            }
        }

        if (ModelRuntime.ONNX in runtimes) {
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
                if (role == RemoteFileRole.TOKENIZER || lower.endsWith("vocab.json") || lower.endsWith("tokenizer_config.json")) {
                    hasTokenizerAsset = true
                }
            }
            if (modelFile != null && hasTokenizerAsset) {
                val variant = ModelVariant(
                    variantId = "ONNX",
                    sizeBytes = allFiles.sumOf { it.sizeBytes.coerceAtLeast(0L) },
                    downloadFiles = allFiles.sortedBy { it.role.ordinal },
                    metadata = mapOf(
                        "files" to allFiles.size.toString(),
                        "model_file" to (modelFile?.fileName ?: "")
                    )
                )
                runtimeOptions[ModelRuntime.ONNX] = RuntimeOption(
                    runtime = ModelRuntime.ONNX,
                    variants = listOf(variant),
                    recommendedTier = recommendTierFromSize(variant.sizeBytes)
                )
            }
        }

        if (runtimeOptions.isEmpty()) {
            AppLogger.i("HF catalog skipping repo=$modelId no compatible files for runtimes=$runtimes")
            return@withContext null
        }
        val display = modelId.substringAfterLast('/')
        CatalogRepoModel(
            id = modelId,
            displayName = display,
            repo = modelId,
            paramsApprox = estimateParamSize(modelId, tags),
            tags = tags,
            license = if (obj.has("license") && !obj.isNull("license")) obj.optString("license") else null,
            runtimeOptions = runtimeOptions
        )
    }

    private suspend fun fetchGgufModelDetails(modelId: String): CatalogRuntimeModel? = withContext(Dispatchers.IO) {
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
        CatalogRuntimeModel(
            id = modelId,
            displayName = display,
            repo = modelId,
            runtime = ModelRuntime.LLAMA_CPP_GGUF,
            paramsApprox = estimateParamSize(modelId, tags),
            tags = tags,
            license = if (obj.has("license") && !obj.isNull("license")) obj.optString("license") else null,
            variants = variants.sortedBy { it.sizeBytes },
            recommendedTier = recommendTierFromSize(variants.minOf { it.sizeBytes })
        )
    }

    private suspend fun fetchOnnxModelDetails(modelId: String): CatalogRuntimeModel? = withContext(Dispatchers.IO) {
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
        CatalogRuntimeModel(
            id = modelId,
            displayName = display,
            repo = modelId,
            runtime = ModelRuntime.ONNX,
            paramsApprox = estimateParamSize(modelId, tags),
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

    private fun estimateParamSize(modelId: String, tags: List<String>): String {
        val haystack = "$modelId ${tags.joinToString(" ")}".lowercase(Locale.US)
        val match = Regex("""(?<!\d)(\d+(?:\.\d+)?)\s*([bm])(?![a-z])""").find(haystack)
        if (match != null) {
            val value = match.groupValues[1].toDoubleOrNull()
            val unit = match.groupValues[2]
            if (value != null) {
                val inBillions = if (unit == "b") value else value / 1000.0
                return when {
                    inBillions < 0.5 -> "${(inBillions * 1000).toInt()}M"
                    inBillions < 1.0 -> String.format(Locale.US, "%.1fB", inBillions)
                    inBillions < 2.0 -> "1-2B"
                    inBillions < 4.0 -> "2-4B"
                    inBillions < 8.5 -> "7-8B"
                    inBillions < 15.0 -> "8-13B"
                    inBillions < 40.0 -> "30-34B"
                    else -> "${inBillions.toInt()}B+"
                }
            }
        }
        return "Unknown"
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
