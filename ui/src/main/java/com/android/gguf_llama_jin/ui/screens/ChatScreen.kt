package com.android.gguf_llama_jin.ui.screens

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.android.gguf_llama_jin.core.ModelRuntime
import com.android.gguf_llama_jin.data.websearch.WebSearchHit
import com.android.gguf_llama_jin.ui.viewmodel.AppUiState
import com.android.gguf_llama_jin.ui.viewmodel.AppViewModel
import com.android.gguf_llama_jin.ui.viewmodel.ChatMessage

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

            if (!state.statusMessage.isNullOrBlank()) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = state.statusMessage ?: "",
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { viewModel.showModelPicker() }) {
                                Text("Choose model")
                            }
                            OutlinedButton(onClick = { viewModel.clearStatus() }) {
                                Text("Dismiss")
                            }
                        }
                    }
                }
            }

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

            ChatComposer(
                viewModel = viewModel,
                generating = state.generating,
                input = state.chatInput,
                webSearchEnabled = state.webSearchEnabled,
                webSearchInFlight = state.webSearchInFlight
            )

            Text(
                "TTFT: ${state.ttftMs ?: 0} ms | TPS: ${"%.2f".format(state.tokensPerSec ?: 0.0)}",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(bottom = 4.dp)
            )
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
    webSearchInFlight: Boolean
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
                        leadingIcon = { Icon(Icons.Filled.Language, contentDescription = null) },
                        trailingIcon = {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = "Disable search",
                                modifier = Modifier.size(16.dp)
                            )
                        }
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
                title = "ONNX",
                runtime = ModelRuntime.ONNX,
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
