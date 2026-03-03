package com.android.gguf_llama_jin.inference

import com.android.gguf_llama_jin.core.AppLogger
import com.android.gguf_llama_jin.core.ModelRuntime
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File

class OnnxRuntimeBridgeImpl : LlmRuntimeBridge {
    override val runtime: ModelRuntime = ModelRuntime.ONNX_RUNTIME

    private var env: OrtEnvironment? = null
    private val sessions = mutableMapOf<Long, OrtSession>()
    private var handleCounter = 10_000L

    override fun loadModel(modelRef: RuntimeModelRef, contextLength: Int, threads: Int, gpuLayers: Int): Long {
        return try {
            val file = File(modelRef.path)
            if (!file.exists()) {
                AppLogger.e("ONNX model file not found: ${modelRef.path}")
                return 0L
            }
            if (env == null) env = OrtEnvironment.getEnvironment()
            val options = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(threads)
            }
            val session = env!!.createSession(modelRef.path, options)
            val handle = handleCounter++
            sessions[handle] = session
            AppLogger.i("ONNX session created. handle=$handle path=${modelRef.path}")
            handle
        } catch (t: Throwable) {
            AppLogger.e("ONNX load failed", t)
            0L
        }
    }

    override fun startSession(handle: Long, systemPrompt: String?): Long {
        return if (sessions.containsKey(handle)) handle else 0L
    }

    override fun generate(sessionHandle: Long, prompt: String, params: SamplingParams): Flow<TokenChunk> = flow {
        val session = sessions[sessionHandle]
        if (session == null) {
            emit(TokenChunk(token = "", isDone = true))
            return@flow
        }

        // V1 fallback response path until ORT GenAI tokenizer/decoder loop integration is added.
        val text = "[ONNX Runtime] Model is loaded on-device. Prompt received: $prompt"
        text.split(" ").forEach {
            emit(TokenChunk(token = "$it "))
            delay(25)
        }
        emit(TokenChunk(token = "", isDone = true))
    }.flowOn(Dispatchers.IO)

    override fun stop(sessionHandle: Long) {
        // no-op for current non-streaming decoder implementation
    }

    override fun unloadModel(handle: Long) {
        sessions.remove(handle)?.close()
    }
}
