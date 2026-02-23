package com.android.gguf_llama_jin

import com.android.gguf_llama_jin.inference.InferenceSessionManager
import com.android.gguf_llama_jin.inference.LlmNativeBridge
import com.android.gguf_llama_jin.inference.SamplingParams
import com.android.gguf_llama_jin.inference.TokenChunk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Test

class InferenceSessionManagerTest {
    @Test
    fun stop_is_idempotent() {
        val bridge = FakeBridge()
        val manager = InferenceSessionManager(bridge)
        manager.ensureSession("/tmp/model.gguf", null)

        manager.stop()
        manager.stop()

        assertEquals(2, bridge.stopCalls)
    }

    private class FakeBridge : LlmNativeBridge {
        var stopCalls = 0

        override fun loadModel(modelPath: String, contextLength: Int, threads: Int, gpuLayers: Int): Long = 1L
        override fun startSession(handle: Long, systemPrompt: String?): Long = 1L
        override fun generate(sessionHandle: Long, prompt: String, params: SamplingParams): Flow<TokenChunk> = flowOf(TokenChunk("ok", true))
        override fun stop(sessionHandle: Long) {
            stopCalls++
        }

        override fun unloadModel(handle: Long) = Unit
    }
}
