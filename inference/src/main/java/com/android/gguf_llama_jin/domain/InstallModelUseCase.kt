package com.android.gguf_llama_jin.domain

import android.content.Context
import com.android.gguf_llama_jin.core.AppResult
import com.android.gguf_llama_jin.core.Constants
import com.android.gguf_llama_jin.core.ModelRuntime
import com.android.gguf_llama_jin.data.catalog.ModelVariant
import com.android.gguf_llama_jin.data.download.DownloadCoordinator
import com.android.gguf_llama_jin.data.download.DownloadFile
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
        runtime: ModelRuntime,
        modelId: String,
        variant: ModelVariant
    ): AppResult<DownloadRequest> {
        val rootModelDir = File(context.filesDir, Constants.MODELS_DIR).apply { mkdirs() }
        val safeModelId = modelId.replace('/', '_')

        val files = variant.downloadFiles.map { remote ->
            val target = when (runtime) {
                ModelRuntime.LLAMA_CPP_GGUF -> File(rootModelDir, "${safeModelId}-${variant.variantId}.gguf")
                ModelRuntime.ONNX_RUNTIME -> {
                    val runtimeDir = File(rootModelDir, "onnx/$safeModelId/${variant.variantId}")
                    runtimeDir.mkdirs()
                    File(runtimeDir, remote.fileName.substringAfterLast('/'))
                }
            }
            DownloadFile(
                fileName = remote.fileName,
                url = remote.downloadUrl,
                targetPath = target.absolutePath,
                expectedSha256 = remote.sha256,
                expectedSizeBytes = remote.sizeBytes.takeIf { it > 0L }
            )
        }

        val request = DownloadRequest(
            modelId = modelId,
            runtime = runtime,
            variant = variant.variantId,
            files = files
        )

        return try {
            DownloadCoordinator.enqueue(context, request)
            DownloadForegroundService.start(context, request)
            AppResult.Success(request)
        } catch (t: Throwable) {
            AppResult.Error("Could not start download: ${t.message ?: "unknown error"}", t)
        }
    }

    suspend fun markInstalled(model: InstalledModel) {
        registry.upsert(model)
    }
}
