package com.android.gguf_llama_jin.inference

import kotlinx.coroutines.flow.Flow

interface LlmNativeBridge {
    fun loadModel(modelPath: String, contextLength: Int, threads: Int, gpuLayers: Int): Long
    fun startSession(handle: Long, systemPrompt: String?): Long
    fun generate(sessionHandle: Long, prompt: String, params: SamplingParams): Flow<TokenChunk>
    fun stop(sessionHandle: Long)
    fun unloadModel(handle: Long)
}
