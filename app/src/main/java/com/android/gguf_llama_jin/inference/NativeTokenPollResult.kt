package com.android.gguf_llama_jin.inference

data class NativeTokenPollResult(
    val token: String,
    val done: Boolean,
    val error: String
)
