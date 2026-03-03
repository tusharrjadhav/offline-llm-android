package com.android.gguf_llama_jin.inference

import com.android.gguf_llama_jin.core.ModelRuntime
import kotlinx.coroutines.flow.Flow

data class RuntimeModelRef(
    val modelId: String,
    val runtime: ModelRuntime,
    val variant: String,
    val path: String,
    val assetDir: String? = null
)

interface LlmRuntimeBridge {
    val runtime: ModelRuntime

    fun loadModel(modelRef: RuntimeModelRef, contextLength: Int, threads: Int, gpuLayers: Int): Long
    fun startSession(handle: Long, systemPrompt: String?): Long
    fun generate(sessionHandle: Long, prompt: String, params: SamplingParams): Flow<TokenChunk>
    fun stop(sessionHandle: Long)
    fun unloadModel(handle: Long)
}
