package com.android.gguf_llama_jin.inference

import com.android.gguf_llama_jin.core.AppLogger

class InferenceSessionManager(
    bridges: Set<LlmRuntimeBridge>
) {
    private val bridgeByRuntime = bridges.associateBy { it.runtime }

    private var modelHandle: Long = 0L
    private var sessionHandle: Long = 0L
    private var currentModelRef: RuntimeModelRef? = null
    private var activeBridge: LlmRuntimeBridge? = null

    private fun loadAndStart(modelRef: RuntimeModelRef, systemPrompt: String?): Long {
        val bridge = bridgeByRuntime[modelRef.runtime] ?: return 0L
        activeBridge = bridge
        val threads = maxOf(Runtime.getRuntime().availableProcessors() - 1, 1).coerceAtMost(6)
        AppLogger.i("InferenceSessionManager.loadAndStart runtime=${modelRef.runtime} modelPath=${modelRef.path} threads=$threads ctx=4096")
        modelHandle = bridge.loadModel(
            modelRef = modelRef,
            contextLength = 4096,
            threads = threads,
            gpuLayers = 0
        )
        AppLogger.i("InferenceSessionManager.modelHandle=$modelHandle")
        if (modelHandle == 0L) return 0L
        sessionHandle = bridge.startSession(modelHandle, systemPrompt)
        AppLogger.i("InferenceSessionManager.sessionHandle=$sessionHandle")
        if (sessionHandle != 0L) {
            currentModelRef = modelRef
        }
        return sessionHandle
    }

    fun ensureSession(modelRef: RuntimeModelRef, systemPrompt: String?): Long {
        if (sessionHandle != 0L && modelHandle != 0L && currentModelRef == modelRef) {
            AppLogger.i("InferenceSessionManager.reuseSession sessionHandle=$sessionHandle runtime=${modelRef.runtime}")
            return sessionHandle
        }
        unload()
        return loadAndStart(modelRef, systemPrompt)
    }

    fun generate(prompt: String, params: SamplingParams) =
        activeBridge?.generate(sessionHandle, prompt, params)
            ?: throw IllegalStateException("No active runtime bridge")

    fun stop() {
        AppLogger.i("InferenceSessionManager.stop sessionHandle=$sessionHandle")
        if (sessionHandle != 0L) activeBridge?.stop(sessionHandle)
    }

    fun unload() {
        AppLogger.i("InferenceSessionManager.unload modelHandle=$modelHandle sessionHandle=$sessionHandle")
        stop()
        if (modelHandle != 0L) activeBridge?.unloadModel(modelHandle)
        sessionHandle = 0L
        modelHandle = 0L
        currentModelRef = null
        activeBridge = null
    }
}
