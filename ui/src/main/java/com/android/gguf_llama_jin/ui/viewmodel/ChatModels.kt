package com.android.gguf_llama_jin.ui.viewmodel

import com.android.gguf_llama_jin.data.websearch.WebSearchHit

data class ChatMessage(
    val role: String,
    val text: String,
    val sources: List<WebSearchHit> = emptyList()
)

data class ChatThread(
    val id: String,
    val title: String,
    val messages: List<ChatMessage>,
    val updatedAt: Long
)

data class ChatUiMeta(
    val activeThreadId: String? = null,
    val modelPickerVisible: Boolean = false
)
