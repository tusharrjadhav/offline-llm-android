package com.android.gguf_llama_jin.domain

import android.content.Context
import com.android.gguf_llama_jin.core.AppResult
import com.android.gguf_llama_jin.core.Constants
import com.android.gguf_llama_jin.data.download.DownloadCoordinator
import com.android.gguf_llama_jin.data.download.DownloadForegroundService
import com.android.gguf_llama_jin.data.download.DownloadRequest
import com.android.gguf_llama_jin.data.modelstore.InstalledModel
import com.android.gguf_llama_jin.data.modelstore.ModelRegistry
import java.io.File

class InstallModelUseCase(
    private val registry: ModelRegistry
) {
    fun enqueueDownload(
        context: Context,
        modelId: String,
        quant: String,
        url: String,
        expectedSize: Long?
    ): AppResult<Unit> {
        val modelDir = File(context.filesDir, Constants.MODELS_DIR)
        modelDir.mkdirs()
        val targetName = "${modelId.replace('/', '_')}-$quant.gguf"
        val targetPath = File(modelDir, targetName).absolutePath

        val request = DownloadRequest(
            modelId = modelId,
            quant = quant,
            url = url,
            targetPath = targetPath,
            expectedSizeBytes = expectedSize
        )
        return try {
            // Enqueue immediately so UI sees state instantly; service keeps work alive.
            DownloadCoordinator.enqueue(context, request)
            DownloadForegroundService.start(context, request)
            AppResult.Success(Unit)
        } catch (t: Throwable) {
            AppResult.Error("Could not start download: ${t.message ?: "unknown error"}", t)
        }
    }

    suspend fun markInstalled(model: InstalledModel) {
        registry.upsert(model)
    }
}
