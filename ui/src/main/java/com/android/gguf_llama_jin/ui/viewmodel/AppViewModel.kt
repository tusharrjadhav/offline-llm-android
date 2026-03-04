package com.android.gguf_llama_jin.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.android.gguf_llama_jin.core.AppLogger
import com.android.gguf_llama_jin.core.AppResult
import com.android.gguf_llama_jin.core.Constants
import com.android.gguf_llama_jin.core.DeviceHeuristics
import com.android.gguf_llama_jin.core.FitVerdict
import com.android.gguf_llama_jin.core.ModelRuntime
import com.android.gguf_llama_jin.data.catalog.CatalogRepoModel
import com.android.gguf_llama_jin.data.download.DownloadCoordinator
import com.android.gguf_llama_jin.data.download.DownloadState
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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

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

    private fun key(repoId: String, runtime: ModelRuntime) = "${runtime.name}:$repoId"
    private fun findRepo(repoId: String): CatalogRepoModel? = _uiState.value.catalog.firstOrNull { it.id == repoId }
    private fun selectedVariant(repoId: String, runtime: ModelRuntime): String? {
        val repo = findRepo(repoId) ?: return null
        val variants = repo.runtimeOptions[runtime]?.variants.orEmpty()
        if (variants.isEmpty()) return null
        return _uiState.value.selectedVariantByModel[key(repoId, runtime)] ?: variants.first().variantId
    }

    private val _uiState = MutableStateFlow(
        AppUiState(
            firstRun = settings.defaultModelId(ModelRuntime.LLAMA_CPP_GGUF) == null &&
                settings.defaultModelId(ModelRuntime.ONNX) == null,
            capabilitySnapshot = DeviceHeuristics.snapshot(appContext),
            telemetryEnabled = telemetry.isEnabled(),
            historyEnabled = settings.chatHistoryEnabled(),
            wifiOnly = settings.wifiOnlyDownloads(),
            webSearchAllowed = settings.webSearchAllowed(),
            webSearchEnabled = settings.webSearchAllowed(),
            preferredRuntime = settings.preferredRuntime(),
            selectedRuntimeFilters = setOf(ModelRuntime.LLAMA_CPP_GGUF, ModelRuntime.ONNX),
            selectedModelByRuntime = mapOf(
                ModelRuntime.LLAMA_CPP_GGUF to settings.defaultModelId(ModelRuntime.LLAMA_CPP_GGUF),
                ModelRuntime.ONNX to settings.defaultModelId(ModelRuntime.ONNX)
            )
        )
    )
    val uiState: StateFlow<AppUiState> = _uiState.asStateFlow()
    private val _effects = MutableSharedFlow<AppUiEffect>(extraBufferCapacity = 16)
    val effects: SharedFlow<AppUiEffect> = _effects.asSharedFlow()

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
                                val assetDir = if (task.request.runtime == ModelRuntime.ONNX) {
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

    fun onEvent(event: AppUiEvent) {
        when (event) {
            is AppUiEvent.ToggleCatalogRuntimeFilter -> toggleCatalogRuntimeFilter(event.runtime)
            is AppUiEvent.SetPreferredRuntime -> setPreferredRuntime(event.runtime)
            is AppUiEvent.OpenDownloadPicker -> openDownloadPicker(event.repoId)
            AppUiEvent.CloseDownloadPicker -> closeDownloadPicker()
            is AppUiEvent.SelectDownloadRuntime -> selectDownloadRuntime(event.runtime)
            is AppUiEvent.SelectDownloadVariant -> selectDownloadVariant(event.runtime, event.variantId)
            AppUiEvent.ConfirmDownloadSelection -> confirmDownloadSelection()
            is AppUiEvent.PauseDownload -> pauseDownload(event.repoId, event.runtime)
            is AppUiEvent.ResumeDownload -> resumeDownload(event.repoId, event.runtime)
            is AppUiEvent.StopDownload -> stopDownload(event.repoId, event.runtime)
            is AppUiEvent.ChooseDefaultModel -> chooseDefaultModel(event.runtime, event.modelId)
            is AppUiEvent.SetChatInput -> setChatInput(event.value)
            is AppUiEvent.ToggleWebSearchAllowed -> toggleWebSearchAllowed(event.enabled)
            AppUiEvent.DisableWebSearchForNextSends -> disableWebSearchForNextSends()
            AppUiEvent.EnableWebSearchForNextSends -> enableWebSearchForNextSends()
            AppUiEvent.ClearSearchError -> clearSearchError()
            AppUiEvent.CreateNewThread -> createNewThread()
            is AppUiEvent.SelectThread -> selectThread(event.threadId)
            AppUiEvent.ShowModelPicker -> showModelPicker()
            AppUiEvent.HideModelPicker -> hideModelPicker()
            is AppUiEvent.AppendUserMessage -> appendUserMessage(event.text)
            is AppUiEvent.AppendAssistantToken -> appendAssistantToken(event.token)
            AppUiEvent.FinalizeAssistantMessage -> finalizeAssistantMessage()
            is AppUiEvent.SendPrompt -> sendPrompt(event.forceWebSearch)
            AppUiEvent.StopGeneration -> stopGeneration()
            is AppUiEvent.ToggleTelemetry -> toggleTelemetry(event.enabled)
            is AppUiEvent.ToggleHistory -> toggleHistory(event.enabled)
            is AppUiEvent.ToggleWifiOnly -> toggleWifiOnly(event.enabled)
            AppUiEvent.ClearStatus -> clearStatus()
            AppUiEvent.RefreshCatalog -> refreshCatalog()
        }
    }

    fun toggleCatalogRuntimeFilter(runtime: ModelRuntime) {
        val current = _uiState.value.selectedRuntimeFilters
        val next = if (runtime in current) {
            val removed = current - runtime
            if (removed.isEmpty()) current else removed
        } else {
            current + runtime
        }
        _uiState.value = _uiState.value.copy(selectedRuntimeFilters = next)
        refreshCatalog()
    }

    fun setPreferredRuntime(runtime: ModelRuntime) {
        settings.setPreferredRuntime(runtime)
        _uiState.value = _uiState.value.copy(preferredRuntime = runtime)
    }

    fun refreshCatalog() {
        viewModelScope.launch {
            AppLogger.i("refreshCatalog filters=${_uiState.value.selectedRuntimeFilters}")
            _uiState.value = _uiState.value.copy(loadingCatalog = true, statusMessage = null)
            when (val result = catalogUseCase.execute(_uiState.value.selectedRuntimeFilters)) {
                is AppResult.Success -> {
                    val defaults = mutableMapOf<String, String>()
                    result.value.forEach { repo ->
                        repo.runtimeOptions.forEach { (runtime, option) ->
                            val preferred = when (runtime) {
                                ModelRuntime.LLAMA_CPP_GGUF -> option.variants.firstOrNull { it.variantId == Constants.DEFAULT_QUANT }?.variantId
                                ModelRuntime.ONNX -> option.variants.firstOrNull()?.variantId
                            } ?: option.variants.firstOrNull()?.variantId ?: return@forEach
                            defaults[key(repo.id, runtime)] = preferred
                        }
                    }
                    _uiState.value = _uiState.value.copy(
                        loadingCatalog = false,
                        catalog = result.value,
                        selectedVariantByModel = _uiState.value.selectedVariantByModel + defaults
                    )
                    AppLogger.i("refreshCatalog success models=${result.value.size}")
                }

                is AppResult.Error -> {
                    _uiState.value = _uiState.value.copy(
                        loadingCatalog = false,
                        statusMessage = result.message
                    )
                    AppLogger.e("refreshCatalog failed: ${result.message}")
                    _effects.tryEmit(AppUiEffect.ShowMessage(result.message))
                }
            }
        }
    }

    fun openDownloadPicker(repoId: String) {
        val repo = findRepo(repoId) ?: return
        val runtime = repo.supportedRuntimes.firstOrNull() ?: return
        val selectedRuntime = if (repo.supportedRuntimes.size == 1) runtime else _uiState.value.preferredRuntime.takeIf { it in repo.supportedRuntimes }
            ?: runtime
        val defaults = repo.runtimeOptions.mapValues { (_, option) ->
            option.variants.firstOrNull()?.variantId.orEmpty()
        }.filterValues { it.isNotBlank() }
        _uiState.value = _uiState.value.copy(
            pendingDownloadRepoId = repoId,
            downloadRuntimePickerVisible = true,
            downloadRuntimeSelection = selectedRuntime,
            downloadVariantSelectionByRuntime = _uiState.value.downloadVariantSelectionByRuntime + defaults
        )
    }

    fun closeDownloadPicker() {
        _uiState.value = _uiState.value.copy(
            pendingDownloadRepoId = null,
            downloadRuntimePickerVisible = false,
            downloadRuntimeSelection = null
        )
    }

    fun selectDownloadRuntime(runtime: ModelRuntime) {
        _uiState.value = _uiState.value.copy(downloadRuntimeSelection = runtime)
    }

    fun selectDownloadVariant(runtime: ModelRuntime, variantId: String) {
        _uiState.value = _uiState.value.copy(
            downloadVariantSelectionByRuntime = _uiState.value.downloadVariantSelectionByRuntime.toMutableMap().apply {
                put(runtime, variantId)
            }
        )
    }

    fun confirmDownloadSelection() {
        val repoId = _uiState.value.pendingDownloadRepoId ?: return
        val repo = findRepo(repoId) ?: return
        val runtime = _uiState.value.downloadRuntimeSelection ?: repo.supportedRuntimes.firstOrNull() ?: return
        val runtimeOption = repo.runtimeOptions[runtime] ?: return
        val selectedVariantId = _uiState.value.downloadVariantSelectionByRuntime[runtime]
            ?: runtimeOption.variants.firstOrNull()?.variantId
            ?: return
        val selectedVariant = runtimeOption.variants.firstOrNull { it.variantId == selectedVariantId } ?: return

        val isInstalled = _uiState.value.installed.any {
            it.id == repo.id && it.runtime == runtime && it.variant == selectedVariantId
        }
        if (isInstalled) {
            setModelMessage(runtime, repo.id, "Model already downloaded for $selectedVariantId")
            _effects.tryEmit(AppUiEffect.ShowMessage("Model already downloaded"))
            closeDownloadPicker()
            return
        }

        val snapshot = _uiState.value.capabilitySnapshot ?: DeviceHeuristics.snapshot(appContext)
        val storageVerdict = DeviceHeuristics.storageVerdict(snapshot.freeStorageBytes, selectedVariant.sizeBytes)
        val estimatedPeak = (selectedVariant.sizeBytes * 1.8).toLong()
        val ramVerdict = DeviceHeuristics.ramVerdict(snapshot.availableRamMb * 1024L * 1024L, estimatedPeak)

        if (storageVerdict == FitVerdict.BLOCK || ramVerdict == FitVerdict.BLOCK) {
            setModelMessage(
                runtime,
                repo.id,
                "This model is likely too large for current device resources. Try a smaller variant/model."
            )
            _effects.tryEmit(AppUiEffect.ShowMessage("Model may not fit this device"))
            return
        }

        clearModelMessage(runtime, repo.id)
        when (val result = installUseCase.enqueueDownload(
            context = appContext,
            runtime = runtime,
            modelId = repo.id,
            variant = selectedVariant
        )) {
            is AppResult.Success -> {
                setModelMessage(runtime, repo.id, "Download started: $selectedVariantId")
                _effects.tryEmit(AppUiEffect.ShowMessage("Download started"))
                closeDownloadPicker()
            }

            is AppResult.Error -> {
                setModelMessage(runtime, repo.id, result.message)
                _effects.tryEmit(AppUiEffect.ShowMessage(result.message))
            }
        }
    }

    fun pauseDownload(repoId: String, runtime: ModelRuntime) {
        val task = _uiState.value.downloads.values.firstOrNull {
            it.request.modelId == repoId && it.request.runtime == runtime
        } ?: return
        viewModelScope.launch {
            DownloadCoordinator.pause(task.request)
            setModelMessage(runtime, repoId, "Paused: ${task.request.variant}")
        }
    }

    fun resumeDownload(repoId: String, runtime: ModelRuntime) {
        val task = _uiState.value.downloads.values.firstOrNull {
            it.request.modelId == repoId && it.request.runtime == runtime
        } ?: return
        DownloadCoordinator.resume(appContext, task.request)
        setModelMessage(runtime, repoId, "Resumed: ${task.request.variant}")
    }

    fun stopDownload(repoId: String, runtime: ModelRuntime) {
        val task = _uiState.value.downloads.values.firstOrNull {
            it.request.modelId == repoId && it.request.runtime == runtime
        } ?: return
        viewModelScope.launch {
            DownloadCoordinator.cancel(task.request)
            setModelMessage(runtime, repoId, "Stopped: ${task.request.variant}")
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
            selectedRuntimeFilters = setOf(runtime),
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
            _effects.tryEmit(AppUiEffect.ShowMessage("Select an installed model first"))
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
            AppLogger.i(
                "WebSearch gate decision=${gateResult.decision} reason=${gateResult.reason} " +
                    "webAllowed=${_uiState.value.webSearchEnabled && _uiState.value.webSearchAllowed} " +
                    "explicitWeb=$explicitWeb explicitOffline=$explicitOffline promptLen=${prompt.length}"
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
                        AppLogger.i("WebSearch provider success: sources=${sourcesForMessage.size}")
                        if (sourcesForMessage.isNotEmpty()) {
                            promptForInference = GroundedPromptBuilder.build(prompt, sourcesForMessage)
                            AppLogger.i("WebSearch grounded prompt enabled")
                        } else {
                            _uiState.value = _uiState.value.copy(searchError = "No relevant web results found.")
                            AppLogger.i("WebSearch returned empty sources")
                        }
                    }

                    is AppResult.Error -> {
                        _uiState.value = _uiState.value.copy(searchError = searchResult.message)
                        AppLogger.e("WebSearch provider error: ${searchResult.message}")
                    }
                }
                _uiState.value = _uiState.value.copy(webSearchInFlight = false)
            } else {
                AppLogger.i("WebSearch skipped for this prompt")
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
                _effects.tryEmit(AppUiEffect.ShowMessage("Failed to open inference session"))
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
