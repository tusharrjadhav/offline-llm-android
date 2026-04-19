package com.android.gguf_llama_jin.data.catalog

import com.android.gguf_llama_jin.core.AppLogger
import com.android.gguf_llama_jin.core.AppResult
import com.android.gguf_llama_jin.core.Constants
import com.android.gguf_llama_jin.core.ModelRuntime
import com.android.gguf_llama_jin.data.catalog.dto.HfModelDetailsDto
import com.android.gguf_llama_jin.data.catalog.dto.HfModelListItemDto
import com.android.gguf_llama_jin.data.network.AppHttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.url
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.Locale

class HfCatalogApi {
    suspend fun searchRepoModels(
        runtimes: Set<ModelRuntime>,
        limit: Int = 24
    ): AppResult<List<CatalogRepoModel>> = withContext(Dispatchers.IO) {
        try {
            val selected = runtimes.ifEmpty {
                setOf(ModelRuntime.LLAMA_CPP_GGUF)
            }
            val filters = selected.map { it.toHfFilter() }.toSet().sorted()
            val pipelineTag = "text-generation"
            val normalizedLimit = limit.coerceAtLeast(24)
            AppLogger.i("HF catalog search start runtimes=$selected filters=$filters limit=$normalizedLimit")

            val modelIds = linkedSetOf<String>()
            val firstPass = requestModelIds(filters.toSet(), normalizedLimit, pipelineTag)
            AppLogger.i("HF catalog ids filters=$filters pipeline=$pipelineTag full=true count=${firstPass.size}")
            modelIds += firstPass

            if (modelIds.isEmpty()) {
                filters.forEach { filter ->
                    val ids = requestModelIds(setOf(filter), normalizedLimit, pipelineTag)
                    AppLogger.i("HF catalog ids filter=$filter pipeline=$pipelineTag full=true count=${ids.size}")
                    modelIds += ids
                }
            }

            if (modelIds.isEmpty()) {
                AppLogger.i("HF catalog pipeline-filtered fetch returned empty, retrying without pipeline_tag")
                val ids = requestModelIds(filters.toSet(), normalizedLimit, null)
                AppLogger.i("HF catalog ids filters=$filters pipeline=<none> full=true count=${ids.size}")
                modelIds += ids
            }

            if (modelIds.isEmpty()) {
                filters.forEach { filter ->
                    val ids = requestModelIds(setOf(filter), normalizedLimit, null)
                    AppLogger.i("HF catalog ids filter=$filter pipeline=<none> full=true count=${ids.size}")
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

    private suspend fun requestModelIds(
        filters: Set<String>,
        limit: Int,
        pipelineTag: String?
    ): List<String> {
        val response = AppHttpClient.client.get {
            url("${Constants.HF_API_BASE}/models")
            parameter("filter", filters.joinToString(","))
            parameter("sort", "downloads")
            parameter("direction", "-1")
            parameter("limit", limit)
            parameter("full", "true")
            if (!pipelineTag.isNullOrBlank()) parameter("pipeline_tag", pipelineTag)
        }
        if (response.status.value !in 200..299) {
            val body = response.bodyAsText().take(180)
            throw IllegalStateException("HTTP ${response.status.value} ${response.status.description}. $body")
        }
        val payload = response.body<List<HfModelListItemDto>>()
        return payload.mapNotNull { it.id?.takeIf(String::isNotBlank) }
    }

    private suspend fun requestModelDetails(modelId: String): HfModelDetailsDto {
        val response = AppHttpClient.client.get {
            url("${Constants.HF_API_BASE}/models/$modelId")
        }
        if (response.status.value !in 200..299) {
            val body = response.bodyAsText().take(180)
            throw IllegalStateException("HTTP ${response.status.value} ${response.status.description}. $body")
        }
        return response.body()
    }

    private suspend fun fetchRepoModelDetails(
        modelId: String,
        runtimes: Set<ModelRuntime>
    ): CatalogRepoModel? = withContext(Dispatchers.IO) {
        val details = requestModelDetails(modelId)
        val tags = details.tags.filter { it.isNotBlank() }

        val runtimeOptions = mutableMapOf<ModelRuntime, RuntimeOption>()

        if (ModelRuntime.LLAMA_CPP_GGUF in runtimes) {
            val variants = details.siblings.mapNotNull { sibling ->
                val fileName = sibling.fileName?.trim().orEmpty()
                if (!fileName.endsWith(".gguf", true)) return@mapNotNull null
                val size = sibling.size ?: sibling.lfs?.size ?: 0L
                val sha = sibling.lfs?.sha256
                ModelVariant(
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


        if (runtimeOptions.isEmpty()) {
            AppLogger.i("HF catalog skipping repo=$modelId no compatible files for runtimes=$runtimes")
            return@withContext null
        }

        CatalogRepoModel(
            id = modelId,
            displayName = modelId.substringAfterLast('/'),
            repo = modelId,
            paramsApprox = estimateParamSize(modelId, tags),
            tags = tags,
            license = details.license,
            runtimeOptions = runtimeOptions
        )
    }

    private fun ModelRuntime.toHfFilter(): String = when (this) {
        ModelRuntime.LLAMA_CPP_GGUF -> "gguf"
    }

    private fun estimateParamSize(modelId: String, tags: List<String>): String {
        val haystack = "$modelId ${tags.joinToString(" ")}".lowercase(Locale.US)
        val match = Regex("""(?<!\\d)(\\d+(?:\\.\\d+)?)\\s*([bm])(?![a-z])""").find(haystack)
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
        val upper = fileName.uppercase(Locale.US)
        val known = listOf("Q2_K", "Q3_K", "Q4_K_M", "Q4_K_S", "Q5_K_M", "Q5_K_S", "Q6_K", "Q8_0")
        known.firstOrNull { upper.contains(it) }?.let { return it }
        val tokens = upper.split(Regex("[^A-Z0-9_]+"))
        val generic = tokens.firstOrNull { it.matches(Regex("I?Q\\d+[A-Z0-9_]*")) }
        if (!generic.isNullOrBlank()) return generic
        return "GGUF"
    }

}
