package com.android.gguf_llama_jin.ui.viewmodel

import com.android.gguf_llama_jin.core.CapabilitySnapshot
import com.android.gguf_llama_jin.core.ModelRuntime
import com.android.gguf_llama_jin.data.catalog.CatalogModel
import com.android.gguf_llama_jin.data.download.DownloadTaskState
import com.android.gguf_llama_jin.data.modelstore.InstalledModel
import com.android.gguf_llama_jin.data.websearch.WebSearchHit
import com.android.gguf_llama_jin.domain.websearch.WebSearchDecision

data class AppUiState(
    val loadingCatalog: Boolean = false,
    val catalog: List<CatalogModel> = emptyList(),
    val selectedRuntimeFilter: ModelRuntime = ModelRuntime.LLAMA_CPP_GGUF,
    val preferredRuntime: ModelRuntime = ModelRuntime.LLAMA_CPP_GGUF,
    val installed: List<InstalledModel> = emptyList(),
    val downloads: Map<String, DownloadTaskState> = emptyMap(),
    val selectedModelByRuntime: Map<ModelRuntime, String?> = emptyMap(),
    val selectedVariantByModel: Map<String, String> = emptyMap(),
    val modelMessages: Map<String, String> = emptyMap(),
    val threads: List<ChatThread> = emptyList(),
    val chatMeta: ChatUiMeta = ChatUiMeta(),
    val firstRun: Boolean = true,
    val capabilitySnapshot: CapabilitySnapshot? = null,
    val generating: Boolean = false,
    val chatInput: String = "",
    val statusMessage: String? = null,
    val telemetryEnabled: Boolean = false,
    val historyEnabled: Boolean = true,
    val wifiOnly: Boolean = true,
    val webSearchAllowed: Boolean = false,
    val webSearchEnabled: Boolean = false,
    val webSearchInFlight: Boolean = false,
    val webSearchDecision: WebSearchDecision? = null,
    val webSearchSuggestionVisible: Boolean = false,
    val lastSources: List<WebSearchHit> = emptyList(),
    val searchError: String? = null,
    val ttftMs: Long? = null,
    val tokensPerSec: Double? = null
) {
    val activeThread: ChatThread?
        get() = threads.firstOrNull { it.id == chatMeta.activeThreadId }
}
