package com.android.gguf_llama_jin.inference

import com.android.gguf_llama_jin.core.AppLogger
import com.android.gguf_llama_jin.core.ModelRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

class LlmNativeBridgeImpl : LlmRuntimeBridge {
    override val runtime: ModelRuntime = ModelRuntime.LLAMA_CPP_GGUF

    private val nativeLoaded: Boolean = try {
        System.loadLibrary("gguf_jni")
        true
    } catch (_: Throwable) {
        false
    }

    init {
        AppLogger.i("LlmNativeBridgeImpl initialized. nativeLoaded=$nativeLoaded")
    }

    override fun loadModel(modelRef: RuntimeModelRef, contextLength: Int, threads: Int, gpuLayers: Int): Long {
        if (!nativeLoaded) {
            AppLogger.e("loadModel using fallback stub because native library is unavailable")
            return 1L
        }
        val handle = nativeLoadModel(modelRef.path, contextLength, threads, gpuLayers)
        AppLogger.i("nativeLoadModel complete. handle=$handle path=${modelRef.path} ctx=$contextLength threads=$threads gpuLayers=$gpuLayers")
        return handle
    }

    override fun startSession(handle: Long, systemPrompt: String?): Long {
        if (!nativeLoaded) {
            AppLogger.e("startSession using fallback stub because native library is unavailable")
            return 1L
        }
        val session = nativeStartSession(handle, systemPrompt ?: "")
        AppLogger.i("nativeStartSession complete. modelHandle=$handle sessionHandle=$session systemPromptLen=${systemPrompt?.length ?: 0}")
        return session
    }

    override fun generate(sessionHandle: Long, prompt: String, params: SamplingParams): Flow<TokenChunk> = flow {
        AppLogger.i("generate called. sessionHandle=$sessionHandle promptLen=${prompt.length} temp=${params.temperature} topP=${params.topP} maxTokens=${params.maxTokens}")
        if (!nativeLoaded) {
            AppLogger.e("generate running stub path (native backend unavailable)")
            val words = "Native backend missing. This is a stub response for prompt: $prompt".split(" ")
            for (word in words) {
                emit(TokenChunk(token = "$word "))
                delay(40)
            }
            emit(TokenChunk(token = "", isDone = true))
            return@flow
        }

        val requestId = nativeGenerateStart(
            sessionHandle,
            prompt,
            params.temperature,
            params.topP,
            params.maxTokens
        )
        AppLogger.i("nativeGenerateStart requestId=$requestId")
        var emittedTokens = 0

        while (true) {
            val poll = nativePollToken(requestId)
            if (poll.done) {
                AppLogger.i("native generation done. requestId=$requestId emittedTokens=$emittedTokens")
                emit(TokenChunk(token = "", isDone = true))
                break
            }
            if (poll.error.isNotEmpty()) {
                AppLogger.e("Native generation error: ${poll.error}")
                emit(TokenChunk(token = "", isDone = true))
                break
            }
            if (poll.token.isNotEmpty()) {
                emittedTokens++
                if (emittedTokens <= 10 || emittedTokens % 50 == 0) {
                    AppLogger.i("native token[$emittedTokens] requestId=$requestId text='${poll.token.take(40)}'")
                }
                emit(TokenChunk(token = poll.token))
            } else {
                delay(10)
            }
        }
    }.flowOn(Dispatchers.IO)

    override fun stop(sessionHandle: Long) {
        AppLogger.i("nativeStop sessionHandle=$sessionHandle")
        if (!nativeLoaded) return
        nativeStop(sessionHandle)
    }

    override fun unloadModel(handle: Long) {
        AppLogger.i("nativeUnloadModel handle=$handle")
        if (!nativeLoaded) return
        nativeUnloadModel(handle)
    }

    private external fun nativeLoadModel(modelPath: String, contextLength: Int, threads: Int, gpuLayers: Int): Long
    private external fun nativeStartSession(handle: Long, systemPrompt: String): Long
    private external fun nativeGenerateStart(sessionHandle: Long, prompt: String, temperature: Float, topP: Float, maxTokens: Int): Long
    private external fun nativePollToken(requestId: Long): NativeTokenPollResult
    private external fun nativeStop(sessionHandle: Long)
    private external fun nativeUnloadModel(handle: Long)
}
