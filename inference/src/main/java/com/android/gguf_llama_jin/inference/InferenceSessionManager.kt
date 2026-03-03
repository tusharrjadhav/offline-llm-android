package com.android.gguf_llama_jin.inference

import com.android.gguf_llama_jin.core.AppLogger
import kotlinx.coroutines.flow.Flow

class InferenceSessionManager(
    private val bridge: LlmNativeBridge
) {
    private var modelHandle: Long = 0L
    private var sessionHandle: Long = 0L
    private var loadedModelPath: String? = null

    private fun loadAndStart(modelPath: String, systemPrompt: String?): Long {
        val threads = maxOf(Runtime.getRuntime().availableProcessors() - 1, 1).coerceAtMost(6)
        AppLogger.i("InferenceSessionManager.loadAndStart modelPath=$modelPath threads=$threads ctx=4096")
        modelHandle = bridge.loadModel(
            modelPath = modelPath,
            contextLength = 4096,
            threads = threads,
            gpuLayers = 0
        )
        AppLogger.i("InferenceSessionManager.modelHandle=$modelHandle")
        if (modelHandle == 0L) return 0L
        sessionHandle = bridge.startSession(modelHandle, systemPrompt)
        AppLogger.i("InferenceSessionManager.sessionHandle=$sessionHandle")
        if (sessionHandle != 0L) {
            loadedModelPath = modelPath
        }
        return sessionHandle
    }

    fun ensureSession(modelPath: String, systemPrompt: String?): Long {
        if (sessionHandle != 0L && modelHandle != 0L && loadedModelPath == modelPath) {
            AppLogger.i("InferenceSessionManager.reuseSession sessionHandle=$sessionHandle modelPath=$modelPath")
            return sessionHandle
        }
        unload()
        return loadAndStart(modelPath, systemPrompt)
    }

    fun generate(prompt: String, params: SamplingParams): Flow<TokenChunk> {
        return bridge.generate(sessionHandle, prompt, params)
    }

    fun stop() {
        AppLogger.i("InferenceSessionManager.stop sessionHandle=$sessionHandle")
        if (sessionHandle != 0L) bridge.stop(sessionHandle)
    }

    fun unload() {
        AppLogger.i("InferenceSessionManager.unload modelHandle=$modelHandle sessionHandle=$sessionHandle")
        stop()
        if (modelHandle != 0L) bridge.unloadModel(modelHandle)
        sessionHandle = 0L
        modelHandle = 0L
        loadedModelPath = null
    }
}
