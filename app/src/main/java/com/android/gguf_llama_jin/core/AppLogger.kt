package com.android.gguf_llama_jin.core

import android.util.Log

object AppLogger {
    private const val TAG = "GGUFApp"

    fun i(message: String) {
        try {
            Log.i(TAG, message)
        } catch (_: Throwable) {
            println("[$TAG][INFO] $message")
        }
    }

    fun e(message: String, throwable: Throwable? = null) {
        try {
            Log.e(TAG, message, throwable)
        } catch (_: Throwable) {
            println("[$TAG][ERROR] $message ${throwable?.message ?: ""}".trim())
        }
    }
}
