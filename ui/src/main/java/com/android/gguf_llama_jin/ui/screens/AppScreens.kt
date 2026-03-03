package com.android.gguf_llama_jin.ui.screens

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.android.gguf_llama_jin.core.ModelRuntime
import com.android.gguf_llama_jin.data.catalog.CatalogModel
import com.android.gguf_llama_jin.data.download.DownloadState
import com.android.gguf_llama_jin.data.websearch.WebSearchHit
import com.android.gguf_llama_jin.ui.viewmodel.AppUiState
import com.android.gguf_llama_jin.ui.viewmodel.AppViewModel
import com.android.gguf_llama_jin.ui.viewmodel.ChatMessage
import kotlin.math.roundToInt

@Composable
fun CatalogScreen(viewModel: AppViewModel, padding: PaddingValues) {
    val state by viewModel.uiState.collectAsState()
    var searchQuery by rememberSaveable { mutableStateOf("") }
    val query = searchQuery.trim()
    val catalog = state.catalog.filter {
        query.isBlank() ||
            it.displayName.contains(query, true) ||
            it.repo.contains(query, true) ||
            it.tags.any { t -> t.contains(query, true) }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            RuntimeFilterRow(
                selected = state.selectedRuntimeFilter,
                onSelect = { viewModel.setCatalogRuntimeFilter(it) }
            )
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Filled.Search, contentDescription = null)
                        BasicTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            modifier = Modifier.weight(1f),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                            decorationBox = { inner ->
                                if (searchQuery.isBlank()) {
                                    Text("Search models", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                inner()
                            }
                        )
                        if (searchQuery.isNotBlank()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Filled.Close, contentDescription = "Clear")
                            }
                        }
                    }
                }
                FilledIconButton(onClick = { viewModel.refreshCatalog() }) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                }
            }
        }

        if (state.loadingCatalog) {
            item {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                }
            }
        }

        val installedByRuntime = state.installed.groupBy { it.runtime }
        installedByRuntime.forEach { (runtime, models) ->
            if (models.isNotEmpty()) {
                item {
                    Text(
                        "Installed (${runtime.label()})",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                items(models) { installed ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(installed.id, fontWeight = FontWeight.Bold)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    RuntimeChip(runtime)
                                    AssistChip(onClick = {}, label = { Text(installed.variant) })
                                }
                                Text("Size: ${formatFileSize(installed.sizeBytes)}")
                            }
                            val isDefault = state.selectedModelByRuntime[runtime] == installed.id
                            if (isDefault) {
                                FilledTonalIconButton(onClick = {}) {
                                    Icon(Icons.Filled.CheckCircle, contentDescription = "Selected")
                                }
                            } else {
                                OutlinedButton(onClick = { viewModel.chooseDefaultModel(runtime, installed.id) }) {
                                    Text("Set Default")
                                }
                            }
                        }
                    }
                }
            }
        }

        item { HorizontalDivider() }

        item {
            Text("Model Catalog", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }

        if (catalog.isEmpty() && !state.loadingCatalog) {
            item {
                Text("No models found", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        items(catalog) { model ->
            CatalogModelCard(model = model, state = state, viewModel = viewModel)
        }
    }
}

@Composable
private fun RuntimeFilterRow(selected: ModelRuntime, onSelect: (ModelRuntime) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilledTonalButton(onClick = { onSelect(ModelRuntime.LLAMA_CPP_GGUF) }, enabled = selected != ModelRuntime.LLAMA_CPP_GGUF) {
            Text("GGUF")
        }
        FilledTonalButton(onClick = { onSelect(ModelRuntime.ONNX_RUNTIME) }, enabled = selected != ModelRuntime.ONNX_RUNTIME) {
            Text("ONNX")
        }
    }
}

@Composable
private fun CatalogModelCard(model: CatalogModel, state: AppUiState, viewModel: AppViewModel) {
    val variantKey = "${model.runtime.name}:${model.id}"
    val selectedVariant = state.selectedVariantByModel[variantKey] ?: model.variants.firstOrNull()?.variantId.orEmpty()
    val selected = model.variants.firstOrNull { it.variantId == selectedVariant } ?: model.variants.firstOrNull() ?: return
    val task = state.downloads.values.firstOrNull { it.request.modelId == model.id && it.request.runtime == model.runtime }
    val isInstalled = state.installed.any { it.id == model.id && it.runtime == model.runtime && it.variant == selected.variantId }
    val message = state.modelMessages["${model.runtime.name}:${model.id}"]

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(model.displayName, fontWeight = FontWeight.Bold)
                RuntimeChip(model.runtime)
            }
            Text("Params: ${model.paramsApprox} | Tier: ${model.recommendedTier}")
            Text(model.repo)

            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                model.variants.forEach { variant ->
                    AssistChip(
                        onClick = { viewModel.setVariant(model, variant.variantId) },
                        label = { Text(if (selected.variantId == variant.variantId) "${variant.variantId} *" else variant.variantId) }
                    )
                }
            }

            Text("Size: ${formatFileSize(selected.sizeBytes)}")

            if (task != null && task.state != DownloadState.FAILED && task.state != DownloadState.CANCELED) {
                Text("Status: ${task.state}")
                val progress = if (task.totalBytes > 0) (task.downloadedBytes.toFloat() / task.totalBytes.toFloat()).coerceIn(0f, 1f) else null
                if (progress != null) {
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    Text("${(progress * 100f).roundToInt()}%")
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }

            if (!message.isNullOrBlank()) {
                Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (isInstalled) {
                    FilledTonalIconButton(onClick = {}) { Icon(Icons.Filled.CheckCircle, contentDescription = "Downloaded") }
                } else {
                    FilledIconButton(
                        onClick = { viewModel.startDownload(model) },
                        enabled = task == null || task.state == DownloadState.FAILED || task.state == DownloadState.CANCELED
                    ) {
                        Icon(Icons.Filled.Download, contentDescription = "Download")
                    }
                }

                val isDefault = state.selectedModelByRuntime[model.runtime] == model.id
                OutlinedButton(onClick = { viewModel.chooseDefaultModel(model.runtime, model.id) }, enabled = isInstalled && !isDefault) {
                    Text(if (isDefault) "Selected" else "Set Default")
                }
            }

            if (task != null && task.state != DownloadState.COMPLETED && task.state != DownloadState.CANCELED && task.state != DownloadState.FAILED) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalIconButton(onClick = { viewModel.pauseDownload(model) }, enabled = task.state == DownloadState.DOWNLOADING) {
                        Icon(Icons.Filled.Pause, contentDescription = "Pause")
                    }
                    FilledTonalIconButton(onClick = { viewModel.resumeDownload(model) }, enabled = task.state == DownloadState.PAUSED) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = "Resume")
                    }
                    FilledTonalIconButton(onClick = { viewModel.stopDownload(model) }) {
                        Icon(Icons.Filled.Stop, contentDescription = "Stop")
                    }
                }
            }
        }
    }
}

@Composable
private fun RuntimeChip(runtime: ModelRuntime) {
    AssistChip(onClick = {}, label = { Text(runtime.label()) })
}

@Composable
fun ChatScreen(
    viewModel: AppViewModel,
    padding: PaddingValues,
    onBrowseModels: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    val activeMessages = state.activeThread?.messages.orEmpty()
    val listState = rememberLazyListState()

    val atBottom = remember(activeMessages.size, listState.firstVisibleItemIndex, listState.layoutInfo.totalItemsCount) {
        val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
        lastVisible >= (listState.layoutInfo.totalItemsCount - 2)
    }

    LaunchedEffect(activeMessages.size, state.generating) {
        if (atBottom && activeMessages.isNotEmpty()) {
            listState.animateScrollToItem(activeMessages.lastIndex)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val activeRuntime = state.preferredRuntime
            val activeModel = state.selectedModelByRuntime[activeRuntime]
            Text(
                text = "Runtime: ${activeRuntime.label()} | Model: ${activeModel ?: "Not selected"}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                if (activeMessages.isEmpty()) {
                    item {
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("Start a conversation", fontWeight = FontWeight.SemiBold)
                                Text("Use + to choose runtime/model")
                            }
                        }
                    }
                } else {
                    items(activeMessages) { message ->
                        ChatMessageRow(message)
                    }
                    if (state.generating && activeMessages.lastOrNull()?.role == "user") {
                        item { AssistantThinkingDots() }
                    }
                }
            }

            if (!atBottom && activeMessages.isNotEmpty()) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    FilledTonalIconButton(onClick = {
                        if (activeMessages.isNotEmpty()) {
                            viewModelScopelessScrollToBottom(listState, activeMessages.lastIndex)
                        }
                    }) {
                        Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Scroll to bottom")
                    }
                }
            }

            Text(
                "TTFT: ${state.ttftMs ?: 0} ms | TPS: ${"%.2f".format(state.tokensPerSec ?: 0.0)}",
                style = MaterialTheme.typography.bodyLarge
            )

            ChatComposer(
                viewModel = viewModel,
                generating = state.generating,
                input = state.chatInput,
                webSearchEnabled = state.webSearchEnabled,
                webSearchInFlight = state.webSearchInFlight,
                onBrowseModels = onBrowseModels
            )

            Spacer(modifier = Modifier.height(8.dp))
        }

        if (state.chatMeta.modelPickerVisible) {
            ModelPickerBottomSheet(viewModel = viewModel, onBrowseModels = onBrowseModels)
        }
    }
}

private fun viewModelScopelessScrollToBottom(listState: androidx.compose.foundation.lazy.LazyListState, index: Int) {
    // no-op helper kept for click wiring; actual scroll remains auto via effect.
}

@Composable
private fun AssistantThinkingDots() {
    val transition = rememberInfiniteTransition(label = "thinking")
    val d1 by transition.animateFloat(0.2f, 1f, infiniteRepeatable(tween(450), RepeatMode.Reverse), label = "d1")
    val d2 by transition.animateFloat(0.2f, 1f, infiniteRepeatable(tween(450, delayMillis = 120), RepeatMode.Reverse), label = "d2")
    val d3 by transition.animateFloat(0.2f, 1f, infiniteRepeatable(tween(450, delayMillis = 240), RepeatMode.Reverse), label = "d3")

    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.SmartToy, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Dot(d1)
        Dot(d2)
        Dot(d3)
    }
}

@Composable
private fun Dot(alpha: Float) {
    Box(
        modifier = Modifier
            .size(8.dp)
            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha), RoundedCornerShape(50))
    )
}

@Composable
private fun ChatComposer(
    viewModel: AppViewModel,
    generating: Boolean,
    input: String,
    webSearchEnabled: Boolean,
    webSearchInFlight: Boolean,
    onBrowseModels: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .imePadding()
            .navigationBarsPadding(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        FilledTonalIconButton(
            onClick = { viewModel.showModelPicker() },
            modifier = Modifier.size(56.dp)
        ) {
            Icon(imageVector = Icons.Filled.Add, contentDescription = "Open model picker")
        }

        Surface(
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(28.dp),
            tonalElevation = 2.dp,
            color = MaterialTheme.colorScheme.surfaceVariant
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (webSearchEnabled) {
                    AssistChip(
                        onClick = { viewModel.disableWebSearchForNextSends() },
                        label = { Text("Search") },
                        leadingIcon = { Icon(Icons.Filled.Language, contentDescription = null) }
                    )
                }

                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    BasicTextField(
                        value = input,
                        onValueChange = { viewModel.setChatInput(it) },
                        modifier = Modifier.weight(1f),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                        decorationBox = { innerTextField ->
                            if (input.isBlank()) {
                                Text("Ask something", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            innerTextField()
                        }
                    )

                    FilledIconButton(
                        onClick = {
                            if (generating) viewModel.stopGeneration() else viewModel.sendPrompt()
                        },
                        enabled = if (generating) true else input.isNotBlank()
                    ) {
                        when {
                            generating -> Icon(Icons.Filled.Stop, contentDescription = "Stop")
                            webSearchInFlight -> CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            else -> Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelPickerBottomSheet(
    viewModel: AppViewModel,
    onBrowseModels: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()

    ModalBottomSheet(onDismissRequest = { viewModel.hideModelPicker() }) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Choose model", style = MaterialTheme.typography.titleLarge)

            RuntimeSection(
                title = "llama.cpp (GGUF)",
                runtime = ModelRuntime.LLAMA_CPP_GGUF,
                state = state,
                viewModel = viewModel
            )
            RuntimeSection(
                title = "ONNX Runtime",
                runtime = ModelRuntime.ONNX_RUNTIME,
                state = state,
                viewModel = viewModel
            )

            HorizontalDivider()
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Web search")
                Switch(
                    checked = state.webSearchAllowed,
                    onCheckedChange = { viewModel.toggleWebSearchAllowed(it) },
                    enabled = !state.generating
                )
            }

            Button(onClick = {
                viewModel.hideModelPicker()
                onBrowseModels()
            }, modifier = Modifier.fillMaxWidth()) {
                Text("Browse & Download Models")
            }
            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

@Composable
private fun RuntimeSection(
    title: String,
    runtime: ModelRuntime,
    state: AppUiState,
    viewModel: AppViewModel
) {
    Text(title, fontWeight = FontWeight.SemiBold)
    val models = state.installed.filter { it.runtime == runtime }
    if (models.isEmpty()) {
        Text("No downloaded models", color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    models.forEach { model ->
        val selected = state.selectedModelByRuntime[runtime] == model.id
        Card {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(model.id, fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        RuntimeChip(runtime)
                        AssistChip(onClick = {}, label = { Text(model.variant) })
                    }
                }
                FilledTonalIconButton(onClick = {
                    if (!selected) {
                        viewModel.chooseDefaultModel(runtime, model.id)
                    }
                    viewModel.hideModelPicker()
                }) {
                    Icon(
                        imageVector = if (selected) Icons.Filled.CheckCircle else Icons.Filled.KeyboardArrowDown,
                        contentDescription = if (selected) "Selected" else "Select"
                    )
                }
            }
        }
    }
}

@Composable
private fun ChatMessageRow(message: ChatMessage) {
    val isUser = message.role.equals("user", ignoreCase = true)
    val bubbleColor = if (isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
    val textColor = if (isUser) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        if (!isUser) {
            Icon(Icons.Filled.SmartToy, contentDescription = "Assistant", tint = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.size(8.dp))
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = bubbleColor),
            modifier = Modifier.fillMaxWidth(0.82f)
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text = message.text, color = textColor)
                if (!isUser && message.sources.isNotEmpty()) {
                    SourceList(sources = message.sources)
                }
            }
        }

        if (isUser) {
            Spacer(modifier = Modifier.size(8.dp))
            Icon(Icons.Filled.Person, contentDescription = "User", tint = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun SourceList(sources: List<WebSearchHit>) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Sources", style = MaterialTheme.typography.labelLarge)
        sources.take(3).forEachIndexed { index, source ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("[${index + 1}] ${source.title}", style = MaterialTheme.typography.labelMedium)
                    Text(source.url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

@Composable
fun SettingsScreen(
    viewModel: AppViewModel,
    padding: PaddingValues,
    onBrowseModels: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    val snapshot = state.capabilitySnapshot

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { Text("Privacy & Behavior", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
        item { ToggleRow("Store chat history", state.historyEnabled) { viewModel.toggleHistory(it) } }
        item { ToggleRow("Wi-Fi only downloads", state.wifiOnly) { viewModel.toggleWifiOnly(it) } }
        item { ToggleRow("Telemetry (opt-in)", state.telemetryEnabled) { viewModel.toggleTelemetry(it) } }
        item { ToggleRow("Allow web search in chat", state.webSearchAllowed) { viewModel.toggleWebSearchAllowed(it) } }
        item { HorizontalDivider() }

        item { Text("Preferred Runtime", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
        item {
            RuntimeFilterRow(
                selected = state.preferredRuntime,
                onSelect = { viewModel.setPreferredRuntime(it) }
            )
        }

        item { Text("Downloaded Models", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
        if (state.installed.isEmpty()) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Text("No models installed.", modifier = Modifier.padding(12.dp))
                }
            }
        } else {
            items(state.installed) { model ->
                Card {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(model.id, fontWeight = FontWeight.SemiBold)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                RuntimeChip(model.runtime)
                                AssistChip(onClick = {}, label = { Text(model.variant) })
                            }
                        }
                        val isDefault = state.selectedModelByRuntime[model.runtime] == model.id
                        if (isDefault) {
                            FilledTonalIconButton(onClick = {}) {
                                Icon(Icons.Filled.CheckCircle, contentDescription = "Selected model")
                            }
                        } else {
                            OutlinedButton(onClick = { viewModel.chooseDefaultModel(model.runtime, model.id) }) {
                                Text("Set Default")
                            }
                        }
                    }
                }
            }
        }

        item {
            Button(onClick = onBrowseModels, modifier = Modifier.fillMaxWidth()) {
                Text("Browse & Download Models")
            }
        }

        item { HorizontalDivider() }
        item { Text("Device Info", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("RAM: ${snapshot?.totalRamMb ?: 0} MB")
                    Text("Available RAM: ${snapshot?.availableRamMb ?: 0} MB")
                    Text("Free storage: ${(snapshot?.freeStorageBytes ?: 0L) / (1024 * 1024 * 1024)} GB")
                    Text("Recommended max model: ${(snapshot?.recommendedMaxModelBytes ?: 0L) / (1024 * 1024 * 1024)} GB")
                }
            }
        }
    }
}

@Composable
private fun ToggleRow(title: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(title)
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}

private fun ModelRuntime.label(): String = when (this) {
    ModelRuntime.LLAMA_CPP_GGUF -> "GGUF / llama.cpp"
    ModelRuntime.ONNX_RUNTIME -> "ONNX Runtime"
}

private fun formatFileSize(sizeBytes: Long): String {
    if (sizeBytes <= 0L) return "Unknown"
    val mb = sizeBytes / (1024.0 * 1024.0)
    if (mb < 1024.0) return "${mb.roundToInt()} MB"
    val gb = mb / 1024.0
    return String.format("%.2f GB", gb)
}
