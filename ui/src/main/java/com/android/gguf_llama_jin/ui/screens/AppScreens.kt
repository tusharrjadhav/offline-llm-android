package com.android.gguf_llama_jin.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
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
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.android.gguf_llama_jin.data.download.DownloadState
import com.android.gguf_llama_jin.data.websearch.WebSearchHit
import com.android.gguf_llama_jin.ui.viewmodel.AppViewModel
import com.android.gguf_llama_jin.ui.viewmodel.AppUiState
import com.android.gguf_llama_jin.ui.viewmodel.ChatMessage
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

@Composable
fun CatalogScreen(viewModel: AppViewModel, padding: PaddingValues) {
    val state by viewModel.uiState.collectAsState()
    var searchQuery by rememberSaveable { mutableStateOf("") }
    val query = searchQuery.trim()
    val filteredCatalog = if (query.isBlank()) {
        state.catalog
    } else {
        state.catalog.filter { model ->
            model.displayName.contains(query, ignoreCase = true) ||
                model.repo.contains(query, ignoreCase = true) ||
                model.tags.any { it.contains(query, ignoreCase = true) }
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
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
                        Icon(
                            imageVector = Icons.Filled.Search,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        BasicTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            modifier = Modifier.weight(1f),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                            decorationBox = { inner ->
                                if (searchQuery.isBlank()) {
                                    Text(
                                        "Search models",
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                inner()
                            }
                        )
                        if (searchQuery.isNotBlank()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(imageVector = Icons.Filled.Close, contentDescription = "Clear search")
                            }
                        }
                    }
                }
                FilledIconButton(onClick = { viewModel.refreshCatalog() }) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = "Refresh models"
                    )
                }
            }
        }

        if (state.loadingCatalog) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                }
            }
        }

        if (query.isBlank() && state.installed.isNotEmpty()) {
            item {
                Text("Installed Models", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            items(state.installed) { installed ->
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
                            Text("Quant: ${installed.quant}")
                            Text("Size: ${formatFileSize(installed.sizeBytes)}")
                        }
                        if (state.selectedModelId == installed.id) {
                            FilledTonalIconButton(onClick = {}) {
                                Icon(
                                    imageVector = Icons.Filled.CheckCircle,
                                    contentDescription = "Selected default"
                                )
                            }
                        } else {
                            OutlinedButton(onClick = { viewModel.chooseDefaultModel(installed.id) }) {
                                Icon(
                                    imageVector = Icons.Filled.RadioButtonUnchecked,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.size(6.dp))
                                Text("Set Default")
                            }
                        }
                    }
                }
            }
            item {
                HorizontalDivider()
            }
            item {
                Text("Model Catalog", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
        }

        if (query.isNotBlank() && filteredCatalog.isEmpty()) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "No models found for \"$query\"",
                        modifier = Modifier.padding(12.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        items(filteredCatalog) { model ->
            CatalogModelCard(model = model, state = state, viewModel = viewModel)
        }
    }
}

@Composable
private fun CatalogModelCard(model: com.android.gguf_llama_jin.data.catalog.CatalogModel, state: AppUiState, viewModel: AppViewModel) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(model.displayName, fontWeight = FontWeight.Bold)
                    Text("Params: ${model.paramsApprox} | Tier: ${model.recommendedTier}")
                    Text(model.repo)

                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        model.ggufFiles.take(4).forEach { quant ->
                            val selected = state.selectedQuantByModel[model.id] == quant.quant
                            AssistChip(
                                onClick = { viewModel.setQuant(model.id, quant.quant) },
                                label = { Text(if (selected) "${quant.quant} *" else quant.quant) }
                            )
                        }
                    }

                    val selectedQuant = state.selectedQuantByModel[model.id]
                    val selectedFile = model.ggufFiles.firstOrNull { it.quant == selectedQuant } ?: model.ggufFiles.first()
                    val task = state.downloads.values.firstOrNull {
                        it.request.modelId == model.id && (
                            it.request.quant == selectedFile.quant ||
                                it.state == DownloadState.DOWNLOADING ||
                                it.state == DownloadState.QUEUED ||
                                it.state == DownloadState.VERIFYING ||
                                it.state == DownloadState.PAUSED
                            )
                    } ?: state.downloads.values.firstOrNull { it.request.modelId == model.id }
                    val isInstalled = state.installed.any { it.id == model.id && it.quant == selectedFile.quant }
                    val modelMessage = state.modelMessages[model.id]

                    if (isInstalled) {
                        AssistChip(
                            onClick = {},
                            label = { Text("Downloaded") },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Filled.CheckCircle,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        )
                    }

                    Text("Size: ${formatFileSize(selectedFile.sizeBytes)}")

                    if (task != null && task.state != DownloadState.FAILED && task.state != DownloadState.CANCELED) {
                        val progress = if (task.totalBytes > 0) {
                            (task.downloadedBytes.toFloat() / task.totalBytes.toFloat()).coerceIn(0f, 1f)
                        } else {
                            null
                        }
                        Text("Status: ${task.state}")
                        if (progress != null) {
                            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                            Text("${(progress * 100f).roundToInt()}%")
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                    }

                    if (task?.state == DownloadState.FAILED) {
                        Text("Download failed: ${task.error ?: "Unknown error"}")
                    }
                    if (!modelMessage.isNullOrBlank()) {
                        val isErrorMessage = modelMessage.contains("fail", ignoreCase = true) ||
                            modelMessage.contains("error", ignoreCase = true) ||
                            modelMessage.contains("too large", ignoreCase = true) ||
                            modelMessage.contains("could not", ignoreCase = true) ||
                            modelMessage.contains("http", ignoreCase = true)
                        val containerColor = if (isErrorMessage) {
                            MaterialTheme.colorScheme.errorContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        }
                        val textColor = if (isErrorMessage) {
                            MaterialTheme.colorScheme.onErrorContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                        Card(colors = CardDefaults.cardColors(containerColor = containerColor)) {
                            Text(
                                modelMessage,
                                modifier = Modifier.padding(10.dp),
                                color = textColor
                            )
                        }
                    }

                    val downloadingNow = task?.state == DownloadState.QUEUED ||
                        task?.state == DownloadState.DOWNLOADING ||
                        task?.state == DownloadState.VERIFYING
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!isInstalled) {
                            FilledIconButton(
                                onClick = { viewModel.startDownload(model) },
                                enabled = !downloadingNow
                            ) {
                                if (downloadingNow) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Filled.Download,
                                        contentDescription = "Download",
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                        val isDefault = state.selectedModelId == model.id
                        if (isDefault) {
                            FilledTonalButton(onClick = {}) {
                                Icon(
                                    imageVector = Icons.Filled.CheckCircle,
                                    contentDescription = "Selected default",
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        } else {
                            OutlinedButton(
                                onClick = { viewModel.chooseDefaultModel(model.id) },
                                enabled = isInstalled
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.RadioButtonUnchecked,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.size(6.dp))
                                Text("Set Default")
                            }
                        }
                    }

                    if (task != null && task.state != DownloadState.COMPLETED && !isInstalled) {
                        val canPause = task.state == DownloadState.QUEUED ||
                            task.state == DownloadState.DOWNLOADING ||
                            task.state == DownloadState.VERIFYING
                        val canResume = task.state == DownloadState.PAUSED
                        val canStop = task.state != DownloadState.CANCELED

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalIconButton(
                                onClick = { viewModel.pauseDownload(model) },
                                enabled = canPause
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Pause,
                                    contentDescription = "Pause download"
                                )
                            }
                            FilledTonalIconButton(
                                onClick = { viewModel.resumeDownload(model) },
                                enabled = canResume
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.PlayArrow,
                                    contentDescription = "Resume download"
                                )
                            }
                            FilledTonalIconButton(
                                onClick = { viewModel.stopDownload(model) },
                                enabled = canStop
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Stop,
                                    contentDescription = "Stop download"
                                )
                            }
                        }
                    }
                }
            }
        }

@Composable
fun ChatScreen(
    viewModel: AppViewModel,
    padding: PaddingValues,
    onBrowseModels: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    val activeMessages = state.activeThread?.messages.orEmpty()
    val chatListState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val isAtBottom by remember(chatListState, activeMessages.size) {
        derivedStateOf {
            val total = chatListState.layoutInfo.totalItemsCount
            if (total == 0) return@derivedStateOf true
            val lastVisible = chatListState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            lastVisible >= total - 1
        }
    }
    var pendingNewResponse by remember { mutableStateOf(false) }
    val latestContentKey = "${activeMessages.size}:${activeMessages.lastOrNull()?.text?.length ?: 0}"
    val waitingForFirstAssistantToken = state.generating && (
        activeMessages.lastOrNull()?.role.equals("user", ignoreCase = true) ||
            (
                activeMessages.lastOrNull()?.role.equals("assistant", ignoreCase = true) &&
                    activeMessages.lastOrNull()?.text.orEmpty().isBlank()
                )
        )

    LaunchedEffect(latestContentKey, isAtBottom) {
        if (activeMessages.isEmpty()) {
            pendingNewResponse = false
            return@LaunchedEffect
        }
        if (isAtBottom) {
            chatListState.scrollToItem(activeMessages.lastIndex)
            pendingNewResponse = false
        } else {
            pendingNewResponse = true
        }
    }

    if (state.chatMeta.modelPickerVisible) {
        ModelPickerBottomSheet(
            viewModel = viewModel,
            onBrowseModels = {
                viewModel.hideModelPicker()
                onBrowseModels()
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (activeMessages.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                ChatEmptyState(viewModel)
            }
        } else {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                ChatMessageList(
                    messages = activeMessages,
                    modifier = Modifier.fillMaxSize(),
                    listState = chatListState,
                    generating = state.generating,
                    waitingForFirstAssistantToken = waitingForFirstAssistantToken
                )
                if (!isAtBottom && (pendingNewResponse || state.generating)) {
                    SmallFloatingActionButton(
                        onClick = {
                            pendingNewResponse = false
                            scope.launch {
                                chatListState.animateScrollToItem(activeMessages.lastIndex)
                            }
                        },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.KeyboardArrowDown,
                            contentDescription = "Scroll to latest response"
                        )
                    }
                }
            }
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (state.webSearchSuggestionVisible) {
                AssistChip(
                    onClick = { viewModel.enableWebSearchForNextSends() },
                    label = { Text("Search web for fresher info?") },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Filled.Language,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                )
            }
            if (!state.searchError.isNullOrBlank()) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Text(
                        state.searchError ?: "",
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(10.dp)
                    )
                }
            }
            Text("TTFT: ${state.ttftMs ?: 0} ms | TPS: ${"%.2f".format(state.tokensPerSec ?: 0.0)}")
            ChatComposer(
                viewModel = viewModel,
                generating = state.generating,
                input = state.chatInput,
                webSearchEnabled = state.webSearchEnabled,
                webSearchInFlight = state.webSearchInFlight
            )
        }
    }
}

@Composable
private fun ChatEmptyState(viewModel: AppViewModel) {
    val state by viewModel.uiState.collectAsState()
    val snapshot = state.capabilitySnapshot

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("On-device Chat", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("Start a new conversation", style = MaterialTheme.typography.titleMedium)

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Device capability", fontWeight = FontWeight.SemiBold)
                Text("RAM: ${snapshot?.totalRamMb ?: 0} MB")
                Text("Free storage: ${(snapshot?.freeStorageBytes ?: 0L) / (1024 * 1024 * 1024)} GB")
                Text("Recommended max model: ${(snapshot?.recommendedMaxModelBytes ?: 0L) / (1024 * 1024 * 1024)} GB")
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(onClick = { viewModel.setChatInput("Summarize this text in 5 bullets") }, label = { Text("Summarize") })
            AssistChip(onClick = { viewModel.setChatInput("Rewrite this to sound professional") }, label = { Text("Rewrite") })
            AssistChip(onClick = { viewModel.setChatInput("Give me 3 ideas for...") }, label = { Text("Ideas") })
        }
    }
}

@Composable
private fun ChatMessageList(
    messages: List<ChatMessage>,
    modifier: Modifier = Modifier,
    listState: LazyListState,
    generating: Boolean,
    waitingForFirstAssistantToken: Boolean
) {
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        state = listState,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(messages.size) { index ->
            val msg = messages[index]
            val isLast = index == messages.lastIndex
            val waitingAssistant = generating &&
                isLast &&
                msg.role.equals("assistant", ignoreCase = true) &&
                msg.text.isBlank()

            when {
                waitingAssistant -> AssistantTypingRow()
                msg.role.equals("assistant", ignoreCase = true) && msg.text.isBlank() && msg.sources.isEmpty() -> Unit
                else -> ChatMessageRow(message = msg)
            }
        }
        if (generating && waitingForFirstAssistantToken) {
            item {
                AssistantTypingRow()
            }
        }
    }
}

@Composable
private fun AssistantTypingRow() {
    val transition = rememberInfiniteTransition(label = "typingDots")
    val dot1Alpha by transition.animateFloat(
        initialValue = 0.2f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 700),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dot1"
    )
    val dot2Alpha by transition.animateFloat(
        initialValue = 0.2f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 700, delayMillis = 150),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dot2"
    )
    val dot3Alpha by transition.animateFloat(
        initialValue = 0.2f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 700, delayMillis = 300),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dot3"
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically
    ) {
        AssistChip(
            onClick = {},
            label = {},
            leadingIcon = {
                Icon(
                    imageVector = Icons.Filled.SmartToy,
                    contentDescription = "Assistant",
                    modifier = Modifier.size(18.dp)
                )
            },
            colors = AssistChipDefaults.assistChipColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        )
        Spacer(modifier = Modifier.size(8.dp))
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceVariant
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(".", color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = dot1Alpha), style = MaterialTheme.typography.titleLarge)
                Text(".", color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = dot2Alpha), style = MaterialTheme.typography.titleLarge)
                Text(".", color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = dot3Alpha), style = MaterialTheme.typography.titleLarge)
            }
        }
    }
}

@Composable
private fun ChatComposer(
    viewModel: AppViewModel,
    generating: Boolean,
    input: String,
    webSearchEnabled: Boolean,
    webSearchInFlight: Boolean
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        FilledTonalIconButton(
            onClick = { viewModel.showModelPicker() },
            modifier = Modifier.size(56.dp)
        ) {
            Icon(imageVector = Icons.Filled.Add, contentDescription = "Open tools")
        }

        Surface(
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(28.dp),
            tonalElevation = 2.dp,
            color = MaterialTheme.colorScheme.surfaceVariant
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (webSearchEnabled) {
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Language,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Text("Search")
                            IconButton(
                                onClick = { viewModel.disableWebSearchForNextSends() },
                                modifier = Modifier.size(20.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Close,
                                    contentDescription = "Disable search",
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    BasicTextField(
                        value = input,
                        onValueChange = { viewModel.setChatInput(it) },
                        modifier = Modifier.weight(1f),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                        decorationBox = { innerTextField ->
                            if (input.isBlank()) {
                                Text(
                                    "Ask something",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            innerTextField()
                        }
                    )

                    IconButton(onClick = {}, enabled = !generating && !webSearchInFlight) {
                        Icon(
                            imageVector = Icons.Filled.Mic,
                            contentDescription = "Voice input"
                        )
                    }

                    FilledIconButton(
                        onClick = {
                            if (generating) viewModel.stopGeneration() else viewModel.sendPrompt()
                        },
                        enabled = if (generating) true else input.isNotBlank()
                    ) {
                        if (generating) {
                            Icon(imageVector = Icons.Filled.Stop, contentDescription = "Stop generation")
                        } else if (webSearchInFlight) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(imageVector = Icons.AutoMirrored.Filled.Send, contentDescription = "Send message")
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

            if (state.installed.isEmpty()) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Text("No installed models yet. Browse and download a model first.", modifier = Modifier.padding(12.dp))
                }
            } else {
                state.installed.forEach { model ->
                    val selected = state.selectedModelId == model.id
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
                                Text("Quant: ${model.quant}")
                            }
                            if (selected) {
                                FilledTonalIconButton(onClick = {}) {
                                    Icon(
                                        imageVector = Icons.Filled.CheckCircle,
                                        contentDescription = "Selected model"
                                    )
                                }
                            } else {
                                FilledTonalIconButton(onClick = {
                                    viewModel.chooseDefaultModel(model.id)
                                    viewModel.hideModelPicker()
                                }) {
                                    Icon(
                                        imageVector = Icons.Filled.RadioButtonUnchecked,
                                        contentDescription = "Set default model"
                                    )
                                }
                            }
                        }
                    }
                }
            }

            HorizontalDivider()
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Filled.Language,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Column {
                        Text("Web search")
                        Text(
                            if (state.webSearchAllowed) "Enabled for chat" else "Disabled",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Switch(
                    checked = state.webSearchAllowed,
                    onCheckedChange = { viewModel.toggleWebSearchAllowed(it) },
                    enabled = !state.generating
                )
            }

            HorizontalDivider()
            Button(onClick = onBrowseModels, modifier = Modifier.fillMaxWidth()) {
                Text("Browse & Download Models")
            }
            Spacer(modifier = Modifier.height(12.dp))
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
            AssistChip(
                onClick = {},
                label = {},
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Filled.SmartToy,
                        contentDescription = "Assistant",
                        modifier = Modifier.size(18.dp)
                    )
                },
                colors = AssistChipDefaults.assistChipColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
            )
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
            AssistChip(
                onClick = {},
                label = {},
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Filled.Person,
                        contentDescription = "User",
                        modifier = Modifier.size(18.dp)
                    )
                },
                colors = AssistChipDefaults.assistChipColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            )
        }
    }
}

@Composable
private fun SourceList(sources: List<WebSearchHit>) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            "Sources",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
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
        item {
            Text("Privacy & Behavior", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }

        item {
            ToggleRow(
                title = "Store chat history",
                checked = state.historyEnabled,
                onChecked = { viewModel.toggleHistory(it) }
            )
        }

        item {
            ToggleRow(
                title = "Wi-Fi only downloads",
                checked = state.wifiOnly,
                onChecked = { viewModel.toggleWifiOnly(it) }
            )
        }

        item {
            ToggleRow(
                title = "Telemetry (opt-in)",
                checked = state.telemetryEnabled,
                onChecked = { viewModel.toggleTelemetry(it) }
            )
        }

        item {
            ToggleRow(
                title = "Allow web search in chat",
                checked = state.webSearchAllowed,
                onChecked = { viewModel.toggleWebSearchAllowed(it) }
            )
        }

        item { HorizontalDivider() }

        item {
            Text("Downloaded Models", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }

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
                            Text("Quant: ${model.quant}")
                        }
                        if (state.selectedModelId == model.id) {
                            FilledTonalIconButton(onClick = {}) {
                                Icon(
                                    imageVector = Icons.Filled.CheckCircle,
                                    contentDescription = "Selected model"
                                )
                            }
                        } else {
                            OutlinedButton(onClick = { viewModel.chooseDefaultModel(model.id) }) {
                                Icon(
                                    imageVector = Icons.Filled.RadioButtonUnchecked,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.size(6.dp))
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

        item {
            Text("Device Info", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }

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

private fun formatFileSize(sizeBytes: Long): String {
    if (sizeBytes <= 0L) return "Unknown"
    val mb = sizeBytes / (1024.0 * 1024.0)
    if (mb < 1024.0) return "${mb.roundToInt()} MB"
    val gb = mb / 1024.0
    return String.format("%.2f GB", gb)
}
