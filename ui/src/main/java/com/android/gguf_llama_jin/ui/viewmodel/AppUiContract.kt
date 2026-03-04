package com.android.gguf_llama_jin.ui.viewmodel

import com.android.gguf_llama_jin.core.ModelRuntime
import com.android.gguf_llama_jin.data.catalog.CatalogModel

sealed interface AppUiEvent {
    data class SetCatalogRuntimeFilter(val runtime: ModelRuntime) : AppUiEvent
    data class SetPreferredRuntime(val runtime: ModelRuntime) : AppUiEvent
    data class SetVariant(val model: CatalogModel, val variant: String) : AppUiEvent
    data class StartDownload(val model: CatalogModel) : AppUiEvent
    data class PauseDownload(val model: CatalogModel) : AppUiEvent
    data class ResumeDownload(val model: CatalogModel) : AppUiEvent
    data class StopDownload(val model: CatalogModel) : AppUiEvent
    data class ChooseDefaultModel(val runtime: ModelRuntime, val modelId: String) : AppUiEvent
    data class SetChatInput(val value: String) : AppUiEvent
    data class ToggleWebSearchAllowed(val enabled: Boolean) : AppUiEvent
    data object DisableWebSearchForNextSends : AppUiEvent
    data object EnableWebSearchForNextSends : AppUiEvent
    data object ClearSearchError : AppUiEvent
    data object CreateNewThread : AppUiEvent
    data class SelectThread(val threadId: String) : AppUiEvent
    data object ShowModelPicker : AppUiEvent
    data object HideModelPicker : AppUiEvent
    data class AppendUserMessage(val text: String) : AppUiEvent
    data class AppendAssistantToken(val token: String) : AppUiEvent
    data object FinalizeAssistantMessage : AppUiEvent
    data class SendPrompt(val forceWebSearch: Boolean = false) : AppUiEvent
    data object StopGeneration : AppUiEvent
    data class ToggleTelemetry(val enabled: Boolean) : AppUiEvent
    data class ToggleHistory(val enabled: Boolean) : AppUiEvent
    data class ToggleWifiOnly(val enabled: Boolean) : AppUiEvent
    data object ClearStatus : AppUiEvent
    data object RefreshCatalog : AppUiEvent
}

sealed interface AppUiEffect {
    data class ShowMessage(val message: String) : AppUiEffect
}
