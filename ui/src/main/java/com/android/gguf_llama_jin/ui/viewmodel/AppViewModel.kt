package com.android.gguf_llama_jin.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.android.gguf_llama_jin.core.AppLogger
import com.android.gguf_llama_jin.core.AppResult
import com.android.gguf_llama_jin.core.CapabilitySnapshot
import com.android.gguf_llama_jin.core.Constants
import com.android.gguf_llama_jin.core.DeviceHeuristics
import com.android.gguf_llama_jin.core.FitVerdict
import com.android.gguf_llama_jin.core.ModelRuntime
import com.android.gguf_llama_jin.data.catalog.CatalogModel
import com.android.gguf_llama_jin.data.catalog.ModelVariant
import com.android.gguf_llama_jin.data.download.DownloadCoordinator
import com.android.gguf_llama_jin.data.download.DownloadState
import com.android.gguf_llama_jin.data.download.DownloadTaskState
import com.android.gguf_llama_jin.data.download.id
import com.android.gguf_llama_jin.data.modelstore.AppSettings
import com.android.gguf_llama_jin.data.modelstore.InstalledModel
import com.android.gguf_llama_jin.data.modelstore.ModelRegistry
import com.android.gguf_llama_jin.data.websearch.WebSearchHit
import com.android.gguf_llama_jin.data.websearch.WebSearchProvider
import com.android.gguf_llama_jin.data.websearch.WebSearchProviderFactory
import com.android.gguf_llama_jin.domain.InstallModelUseCase
import com.android.gguf_llama_jin.domain.ModelCatalogUseCase
import com.android.gguf_llama_jin.domain.websearch.GroundedPromptBuilder
import com.android.gguf_llama_jin.domain.websearch.RuleBasedWebSearchGate
import com.android.gguf_llama_jin.domain.websearch.WebSearchDecision
import com.android.gguf_llama_jin.domain.websearch.WebSearchGate
import com.android.gguf_llama_jin.inference.InferenceSessionManager
import com.android.gguf_llama_jin.inference.LlmNativeBridgeImpl
import com.android.gguf_llama_jin.inference.OnnxRuntimeBridgeImpl
import com.android.gguf_llama_jin.inference.RuntimeModelRef
import com.android.gguf_llama_jin.inference.SamplingParams
import com.android.gguf_llama_jin.telemetry.Telemetry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

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

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val appContext = getApplication<Application>()
    private val catalogUseCase = ModelCatalogUseCase()
    private val modelRegistry = ModelRegistry(appContext)
    private val installUseCase = InstallModelUseCase(modelRegistry)
    private val settings = AppSettings(appContext)
    private val telemetry = Telemetry(appContext)
    private val inference = InferenceSessionManager(setOf(LlmNativeBridgeImpl(), OnnxRuntimeBridgeImpl()))
    private val webSearchProvider: WebSearchProvider = WebSearchProviderFactory.create()
    private val webSearchGate: WebSearchGate = RuleBasedWebSearchGate()

    private val handledInstalls = mutableSetOf<String>()

    private fun key(model: CatalogModel) = "${model.runtime.name}:${model.id}"

    private val _uiState = MutableStateFlow(
        AppUiState(
            firstRun = settings.defaultModelId(ModelRuntime.LLAMA_CPP_GGUF) == null &&
                settings.defaultModelId(ModelRuntime.ONNX_RUNTIME) == null,
            capabilitySnapshot = DeviceHeuristics.snapshot(appContext),
            telemetryEnabled = telemetry.isEnabled(),
            historyEnabled = settings.chatHistoryEnabled(),
            wifiOnly = settings.wifiOnlyDownloads(),
            webSearchAllowed = settings.webSearchAllowed(),
            webSearchEnabled = settings.webSearchAllowed(),
            preferredRuntime = settings.preferredRuntime(),
            selectedRuntimeFilter = settings.preferredRuntime(),
            selectedModelByRuntime = mapOf(
                ModelRuntime.LLAMA_CPP_GGUF to settings.defaultModelId(ModelRuntime.LLAMA_CPP_GGUF),
                ModelRuntime.ONNX_RUNTIME to settings.defaultModelId(ModelRuntime.ONNX_RUNTIME)
            )
        )
    )
    val uiState: StateFlow<AppUiState> = _uiState.asStateFlow()

    private var generationJob: Job? = null

    init {
        settings.migrateLegacyDefaultsIfNeeded()
        viewModelScope.launch {
            DownloadCoordinator.states.collect { states ->
                _uiState.value = _uiState.value.copy(downloads = states)
                states.values.forEach { task ->
                    when (task.state) {
                        DownloadState.COMPLETED -> {
                            val installKey = task.request.id()
                            if (!handledInstalls.contains(installKey)) {
                                handledInstalls += installKey
                                val modelFile = task.request.files.firstOrNull { it.fileName.endsWith(".gguf", true) || it.fileName.endsWith(".onnx", true) }
                                    ?: task.request.files.firstOrNull()
                                    ?: return@forEach
                                val size = task.request.files.sumOf { f -> File(f.targetPath).takeIf { it.exists() }?.length() ?: 0L }
                                val assetDir = if (task.request.runtime == ModelRuntime.ONNX_RUNTIME) {
                                    File(modelFile.targetPath).parentFile?.absolutePath
                                } else null
                                installUseCase.markInstalled(
                                    InstalledModel(
                                        id = task.request.modelId,
                                        runtime = task.request.runtime,
                                        variant = task.request.variant,
                                        path = modelFile.targetPath,
                                        sizeBytes = size,
                                        sha256 = task.request.files.firstOrNull()?.expectedSha256,
                                        installedAt = System.currentTimeMillis(),
                                        lastUsedAt = System.currentTimeMillis(),
                                        assetDir = assetDir
                                    )
                                )
                                loadInstalled()
                                setModelMessage(task.request.runtime, task.request.modelId, "Download complete: ${task.request.variant}")
                            }
                        }

                        DownloadState.FAILED -> {
                            setModelMessage(task.request.runtime, task.request.modelId, task.error ?: "Download failed")
                        }

                        else -> Unit
                    }
                }
            }
        }

        refreshCatalog()
        loadInstalled()
    }

    fun setCatalogRuntimeFilter(runtime: ModelRuntime) {
        _uiState.value = _uiState.value.copy(selectedRuntimeFilter = runtime)
        settings.setPreferredRuntime(runtime)
        _uiState.value = _uiState.value.copy(preferredRuntime = runtime)
        refreshCatalog()
    }

    fun setPreferredRuntime(runtime: ModelRuntime) {
        settings.setPreferredRuntime(runtime)
        _uiState.value = _uiState.value.copy(preferredRuntime = runtime)
    }

    fun refreshCatalog() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(loadingCatalog = true, statusMessage = null)
            when (val result = catalogUseCase.execute(_uiState.value.selectedRuntimeFilter)) {
                is AppResult.Success -> {
                    val defaults = result.value.associate { model ->
                        val preferred = when (model.runtime) {
                            ModelRuntime.LLAMA_CPP_GGUF -> model.variants.firstOrNull { it.variantId == Constants.DEFAULT_QUANT }?.variantId
                            ModelRuntime.ONNX_RUNTIME -> model.variants.firstOrNull()?.variantId
                        } ?: model.variants.firstOrNull()?.variantId ?: "default"
                        key(model) to preferred
                    }
                    _uiState.value = _uiState.value.copy(
                        loadingCatalog = false,
                        catalog = result.value,
                        selectedVariantByModel = _uiState.value.selectedVariantByModel + defaults
                    )
                }

                is AppResult.Error -> {
                    _uiState.value = _uiState.value.copy(
                        loadingCatalog = false,
                        statusMessage = result.message
                    )
                }
            }
        }
    }

    fun setVariant(model: CatalogModel, variant: String) {
        _uiState.value = _uiState.value.copy(
            selectedVariantByModel = _uiState.value.selectedVariantByModel.toMutableMap().apply {
                put(key(model), variant)
            }
        )
    }

    fun startDownload(model: CatalogModel) {
        val selectedVariantId = _uiState.value.selectedVariantByModel[key(model)] ?: model.variants.firstOrNull()?.variantId ?: return
        val selectedVariant = model.variants.firstOrNull { it.variantId == selectedVariantId } ?: return
        val isInstalled = _uiState.value.installed.any {
            it.id == model.id && it.runtime == model.runtime && it.variant == selectedVariantId
        }
        if (isInstalled) {
            setModelMessage(model.runtime, model.id, "Model already downloaded for $selectedVariantId")
            return
        }

        val snapshot = _uiState.value.capabilitySnapshot ?: DeviceHeuristics.snapshot(appContext)
        val storageVerdict = DeviceHeuristics.storageVerdict(snapshot.freeStorageBytes, selectedVariant.sizeBytes)
        val estimatedPeak = (selectedVariant.sizeBytes * 1.8).toLong()
        val ramVerdict = DeviceHeuristics.ramVerdict(snapshot.availableRamMb * 1024L * 1024L, estimatedPeak)

        if (storageVerdict == FitVerdict.BLOCK || ramVerdict == FitVerdict.BLOCK) {
            setModelMessage(
                model.runtime,
                model.id,
                "This model is likely too large for current device resources. Try a smaller variant/model."
            )
            return
        }

        clearModelMessage(model.runtime, model.id)
        when (val result = installUseCase.enqueueDownload(
            context = appContext,
            runtime = model.runtime,
            modelId = model.id,
            variant = selectedVariant
        )) {
            is AppResult.Success -> setModelMessage(model.runtime, model.id, "Download started: $selectedVariantId")
            is AppResult.Error -> setModelMessage(model.runtime, model.id, result.message)
        }
    }

    fun pauseDownload(model: CatalogModel) {
        val task = _uiState.value.downloads.values.firstOrNull { it.request.modelId == model.id && it.request.runtime == model.runtime } ?: return
        viewModelScope.launch {
            DownloadCoordinator.pause(task.request)
            setModelMessage(model.runtime, model.id, "Paused: ${task.request.variant}")
        }
    }

    fun resumeDownload(model: CatalogModel) {
        val task = _uiState.value.downloads.values.firstOrNull { it.request.modelId == model.id && it.request.runtime == model.runtime } ?: return
        DownloadCoordinator.resume(appContext, task.request)
        setModelMessage(model.runtime, model.id, "Resumed: ${task.request.variant}")
    }

    fun stopDownload(model: CatalogModel) {
        val task = _uiState.value.downloads.values.firstOrNull { it.request.modelId == model.id && it.request.runtime == model.runtime } ?: return
        viewModelScope.launch {
            DownloadCoordinator.cancel(task.request)
            setModelMessage(model.runtime, model.id, "Stopped: ${task.request.variant}")
        }
    }

    fun chooseDefaultModel(runtime: ModelRuntime, modelId: String) {
        settings.setDefaultModelId(runtime, modelId)
        settings.setPreferredRuntime(runtime)
        _uiState.value = _uiState.value.copy(
            selectedModelByRuntime = _uiState.value.selectedModelByRuntime.toMutableMap().apply {
                put(runtime, modelId)
            },
            preferredRuntime = runtime,
            selectedRuntimeFilter = runtime,
            firstRun = false,
            statusMessage = "Default model set"
        )
    }

    fun chooseDefaultModel(modelId: String) {
        chooseDefaultModel(_uiState.value.preferredRuntime, modelId)
    }

    fun setChatInput(value: String) {
        _uiState.value = _uiState.value.copy(chatInput = value)
    }

    fun toggleWebSearchAllowed(enabled: Boolean) {
        settings.setWebSearchAllowed(enabled)
        _uiState.value = _uiState.value.copy(
            webSearchAllowed = enabled,
            webSearchEnabled = enabled,
            searchError = null
        )
    }

    fun disableWebSearchForNextSends() {
        _uiState.value = _uiState.value.copy(webSearchEnabled = false)
    }

    fun enableWebSearchForNextSends() {
        if (_uiState.value.webSearchAllowed) {
            _uiState.value = _uiState.value.copy(webSearchEnabled = true)
        }
    }

    fun clearSearchError() {
        _uiState.value = _uiState.value.copy(searchError = null)
    }

    fun createNewThread() {
        val now = System.currentTimeMillis()
        val id = "thread-$now"
        val thread = ChatThread(
            id = id,
            title = "New chat",
            messages = emptyList(),
            updatedAt = now
        )
        val threads = (_uiState.value.threads + thread).sortedByDescending { it.updatedAt }
        _uiState.value = _uiState.value.copy(
            threads = threads,
            chatMeta = _uiState.value.chatMeta.copy(activeThreadId = id),
            chatInput = ""
        )
    }

    fun selectThread(threadId: String) {
        if (_uiState.value.threads.any { it.id == threadId }) {
            _uiState.value = _uiState.value.copy(
                chatMeta = _uiState.value.chatMeta.copy(activeThreadId = threadId)
            )
        }
    }

    fun showModelPicker() {
        _uiState.value = _uiState.value.copy(
            chatMeta = _uiState.value.chatMeta.copy(modelPickerVisible = true)
        )
    }

    fun hideModelPicker() {
        _uiState.value = _uiState.value.copy(
            chatMeta = _uiState.value.chatMeta.copy(modelPickerVisible = false)
        )
    }

    fun appendUserMessage(text: String) {
        ensureThread()
        updateActiveThread { thread ->
            val updated = thread.messages + ChatMessage("user", text)
            thread.copy(
                title = autoTitleThreadIfNeeded(thread.title, updated),
                messages = updated,
                updatedAt = System.currentTimeMillis()
            )
        }
    }

    fun appendAssistantToken(token: String) {
        if (token.isEmpty()) return
        updateActiveThread { thread ->
            val messages = thread.messages.toMutableList()
            val last = messages.lastOrNull()
            if (last == null || last.role != "assistant") {
                messages.add(ChatMessage("assistant", token))
            } else {
                messages[messages.lastIndex] = last.copy(text = last.text + token)
            }
            thread.copy(messages = messages, updatedAt = System.currentTimeMillis())
        }
    }

    fun finalizeAssistantMessage() {
        updateActiveThread { it.copy(updatedAt = System.currentTimeMillis()) }
    }

    fun sendPrompt(forceWebSearch: Boolean = false) {
        val prompt = _uiState.value.chatInput.trim()
        if (prompt.isBlank()) return
        _uiState.value = _uiState.value.copy(statusMessage = null)

        val runtime = _uiState.value.preferredRuntime
        val modelId = _uiState.value.selectedModelByRuntime[runtime]
        val installedModel = _uiState.value.installed.firstOrNull { it.id == modelId && it.runtime == runtime }
        if (installedModel == null) {
            _uiState.value = _uiState.value.copy(
                statusMessage = "Install and select a model first for ${runtime.name}",
                chatMeta = _uiState.value.chatMeta.copy(modelPickerVisible = true)
            )
            AppLogger.e("sendPrompt blocked: no installed model selected. runtime=$runtime selectedModelId=$modelId")
            return
        }

        appendUserMessage(prompt)
        appendAssistantToken("")

        AppLogger.i("sendPrompt runtime=${installedModel.runtime} modelId=${installedModel.id} variant=${installedModel.variant} path=${installedModel.path} promptLen=${prompt.length}")

        generationJob?.cancel()
        _uiState.value = _uiState.value.copy(
            chatInput = "",
            generating = true,
            ttftMs = null,
            tokensPerSec = null,
            searchError = null,
            webSearchSuggestionVisible = false
        )

        generationJob = viewModelScope.launch {
            val explicitWeb = forceWebSearch || containsAny(prompt.lowercase(), listOf("search web", "look up", "find online", "on the web"))
            val explicitOffline = containsAny(prompt.lowercase(), listOf("offline only", "local only", "do not search", "don't search web"))
            val gateResult = webSearchGate.evaluate(
                prompt = prompt,
                webAllowed = _uiState.value.webSearchEnabled && _uiState.value.webSearchAllowed,
                explicitUserRequest = explicitWeb,
                explicitOffline = explicitOffline
            )
            _uiState.value = _uiState.value.copy(
                webSearchDecision = gateResult.decision,
                webSearchSuggestionVisible = gateResult.decision == WebSearchDecision.SUGGEST
            )

            var promptForInference = prompt
            var sourcesForMessage: List<WebSearchHit> = emptyList()
            if (gateResult.decision == WebSearchDecision.SEARCH) {
                _uiState.value = _uiState.value.copy(webSearchInFlight = true)
                when (val searchResult = webSearchProvider.search(prompt, limit = 3)) {
                    is AppResult.Success -> {
                        sourcesForMessage = searchResult.value
                        if (sourcesForMessage.isNotEmpty()) {
                            promptForInference = GroundedPromptBuilder.build(prompt, sourcesForMessage)
                        } else {
                            _uiState.value = _uiState.value.copy(searchError = "No relevant web results found.")
                        }
                    }

                    is AppResult.Error -> {
                        _uiState.value = _uiState.value.copy(searchError = searchResult.message)
                    }
                }
                _uiState.value = _uiState.value.copy(webSearchInFlight = false)
            }
            attachSourcesToLastAssistant(sourcesForMessage)
            _uiState.value = _uiState.value.copy(lastSources = sourcesForMessage)

            val session = withContext(Dispatchers.IO) {
                inference.ensureSession(
                    RuntimeModelRef(
                        modelId = installedModel.id,
                        runtime = installedModel.runtime,
                        variant = installedModel.variant,
                        path = installedModel.path,
                        assetDir = installedModel.assetDir
                    ),
                    "You are a helpful local assistant."
                )
            }
            if (session == 0L) {
                _uiState.value = _uiState.value.copy(
                    generating = false,
                    statusMessage = "Failed to open inference session",
                    webSearchInFlight = false
                )
                AppLogger.e("inference session start failed modelPath=${installedModel.path}")
                return@launch
            }

            val startNs = System.nanoTime()
            var firstTokenNs: Long? = null
            var tokenCount = 0

            inference.generate(promptForInference, SamplingParams()).collect { chunk ->
                if (chunk.isDone) {
                    val durationSec = (System.nanoTime() - startNs) / 1_000_000_000.0
                    val tps = if (durationSec > 0) tokenCount / durationSec else 0.0
                    _uiState.value = _uiState.value.copy(
                        generating = false,
                        ttftMs = firstTokenNs?.let { it / 1_000_000 },
                        tokensPerSec = tps,
                        webSearchInFlight = false
                    )
                    finalizeAssistantMessage()
                    telemetry.track("generation_done", mapOf("tokens" to tokenCount.toString()))
                    return@collect
                }

                if (firstTokenNs == null && chunk.token.isNotEmpty()) {
                    firstTokenNs = System.nanoTime() - startNs
                }
                tokenCount++
                appendAssistantToken(chunk.token)
            }
        }
    }

    fun stopGeneration() {
        inference.stop()
        generationJob?.cancel()
        _uiState.value = _uiState.value.copy(generating = false, webSearchInFlight = false)
    }

    fun toggleTelemetry(enabled: Boolean) {
        telemetry.setEnabled(enabled)
        _uiState.value = _uiState.value.copy(telemetryEnabled = enabled)
    }

    fun toggleHistory(enabled: Boolean) {
        settings.setChatHistoryEnabled(enabled)
        _uiState.value = _uiState.value.copy(historyEnabled = enabled)
    }

    fun toggleWifiOnly(enabled: Boolean) {
        settings.setWifiOnlyDownloads(enabled)
        _uiState.value = _uiState.value.copy(wifiOnly = enabled)
    }

    fun clearStatus() {
        _uiState.value = _uiState.value.copy(statusMessage = null)
    }

    private fun containsAny(text: String, tokens: List<String>): Boolean {
        return tokens.any { text.contains(it) }
    }

    private fun ensureThread() {
        val activeId = _uiState.value.chatMeta.activeThreadId
        if (activeId == null || _uiState.value.threads.none { it.id == activeId }) {
            createNewThread()
        }
    }

    private fun updateActiveThread(updater: (ChatThread) -> ChatThread) {
        ensureThread()
        val activeId = _uiState.value.chatMeta.activeThreadId ?: return
        val updated = _uiState.value.threads.map {
            if (it.id == activeId) updater(it) else it
        }.sortedByDescending { it.updatedAt }
        _uiState.value = _uiState.value.copy(threads = updated)
    }

    private fun attachSourcesToLastAssistant(sources: List<WebSearchHit>) {
        if (sources.isEmpty()) return
        updateActiveThread { thread ->
            val messages = thread.messages.toMutableList()
            val last = messages.lastOrNull()
            if (last != null && last.role == "assistant") {
                messages[messages.lastIndex] = last.copy(sources = sources)
            }
            thread.copy(messages = messages, updatedAt = System.currentTimeMillis())
        }
    }

    private fun autoTitleThreadIfNeeded(currentTitle: String, messages: List<ChatMessage>): String {
        if (currentTitle != "New chat") return currentTitle
        val firstUser = messages.firstOrNull { it.role == "user" }?.text?.trim().orEmpty()
        if (firstUser.isBlank()) return currentTitle
        return firstUser.take(40)
    }

    private fun setModelMessage(runtime: ModelRuntime, modelId: String, message: String) {
        val key = "${runtime.name}:$modelId"
        _uiState.value = _uiState.value.copy(
            modelMessages = _uiState.value.modelMessages.toMutableMap().apply {
                put(key, message)
            }
        )
    }

    private fun clearModelMessage(runtime: ModelRuntime, modelId: String) {
        val key = "${runtime.name}:$modelId"
        _uiState.value = _uiState.value.copy(
            modelMessages = _uiState.value.modelMessages.toMutableMap().apply {
                remove(key)
            }
        )
    }

    override fun onCleared() {
        super.onCleared()
        inference.unload()
    }

    private fun loadInstalled() {
        viewModelScope.launch {
            val items = modelRegistry.listInstalled().sortedByDescending { it.lastUsedAt }
            _uiState.value = _uiState.value.copy(installed = items)
        }
    }
}
