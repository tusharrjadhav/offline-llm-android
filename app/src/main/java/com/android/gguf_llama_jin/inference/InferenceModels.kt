package com.android.gguf_llama_jin.inference

data class SamplingParams(
    val temperature: Float = 0.7f,
    val topP: Float = 0.9f,
    val maxTokens: Int = 256
)

data class TokenChunk(
    val token: String,
    val isDone: Boolean = false
)

sealed class InferenceError(message: String) : Throwable(message) {
    class NativeUnavailable : InferenceError("Native inference backend unavailable")
    class SessionFailed(reason: String) : InferenceError(reason)
}
