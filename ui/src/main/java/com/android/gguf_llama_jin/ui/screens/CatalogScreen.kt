package com.android.gguf_llama_jin.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.android.gguf_llama_jin.core.ModelRuntime
import com.android.gguf_llama_jin.data.catalog.CatalogRepoModel
import com.android.gguf_llama_jin.data.catalog.ModelVariant
import com.android.gguf_llama_jin.data.catalog.RuntimeOption
import com.android.gguf_llama_jin.data.download.DownloadState
import com.android.gguf_llama_jin.ui.viewmodel.AppUiState
import com.android.gguf_llama_jin.ui.viewmodel.AppViewModel
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

        item {
            RuntimeMultiFilterRow(
                selected = state.selectedRuntimeFilters,
                onToggle = { viewModel.toggleCatalogRuntimeFilter(it) }
            )
        }

        if (state.loadingCatalog) {
            item {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                }
            }
        }

        item {
            Text("Installed", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }

        val installedByRepo = state.installed.groupBy { it.id }
        if (installedByRepo.isEmpty()) {
            item { Text("No installed models", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        } else {
            items(installedByRepo.toList()) { (repoId, models) ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(repoId, fontWeight = FontWeight.Bold)
                        models.sortedBy { it.runtime.name }.forEach { installed ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    RuntimeBadge(installed.runtime)
                                    AssistChip(onClick = {}, label = { Text(installed.variant) })
                                    Text("Size: ${formatFileSize(installed.sizeBytes)}")
                                }
                                val isDefault = state.selectedModelByRuntime[installed.runtime] == installed.id
                                if (isDefault) {
                                    FilledTonalIconButton(onClick = {}) {
                                        Icon(Icons.Filled.CheckCircle, contentDescription = "Selected")
                                    }
                                } else {
                                    OutlinedButton(onClick = { viewModel.chooseDefaultModel(installed.runtime, installed.id) }) {
                                        Text("Set ${installed.runtime.label()} Default")
                                    }
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
            CatalogRepoCard(model = model, state = state, viewModel = viewModel)
        }
    }

    if (state.downloadRuntimePickerVisible) {
        DownloadRuntimeBottomSheet(viewModel = viewModel, state = state)
    }
}

@Composable
private fun CatalogRepoCard(model: CatalogRepoModel, state: AppUiState, viewModel: AppViewModel) {
    val runtimeOrder = listOf(ModelRuntime.LLAMA_CPP_GGUF, ModelRuntime.ONNX)
    val runtimes = runtimeOrder.filter { it in model.supportedRuntimes }
    val activeTask = state.downloads.values.firstOrNull { it.request.modelId == model.id }
    val activeRuntime = activeTask?.request?.runtime
    val activeMessage = activeRuntime?.let { state.modelMessages["${it.name}:${model.id}"] }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                runtimes.forEach { RuntimeBadge(it) }
            }
            Text(model.displayName, fontWeight = FontWeight.Bold)
            Text("Params: ${model.paramsApprox}")
            Text(model.repo)

            runtimes.forEach { runtime ->
                val option = model.runtimeOptions[runtime] ?: return@forEach
                val variants = modelVariantsForUi(option, runtime)
                val selectedVariantId = state.selectedVariantByModel["${runtime.name}:${model.id}"]
                    ?: variants.firstOrNull()?.variantId
                    ?: "-"
                val installed = state.installed.any { it.id == model.id && it.runtime == runtime && it.variant == selectedVariantId }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        val runtimeTier = option.recommendedTier.takeIf { it != "Unknown" }
                        Text(
                            runtimeTier?.let { "${runtime.label()}: $it" } ?: runtime.label(),
                            style = MaterialTheme.typography.labelLarge
                        )
                        Text("Variant: $selectedVariantId")
                        Text("Size: ${formatFileSize(variants.firstOrNull { it.variantId == selectedVariantId }?.sizeBytes ?: option.variants.minOfOrNull { it.sizeBytes } ?: 0L)}")
                    }
                    val isDefault = state.selectedModelByRuntime[runtime] == model.id
                    OutlinedButton(
                        onClick = { viewModel.chooseDefaultModel(runtime, model.id) },
                        enabled = installed && !isDefault
                    ) {
                        Text(if (isDefault) "Selected" else "Set Default")
                    }
                }
            }

            if (activeTask != null && activeTask.state != DownloadState.FAILED && activeTask.state != DownloadState.CANCELED) {
                Text("Status (${activeTask.request.runtime.label()}): ${activeTask.state}")
                val progress = if (activeTask.totalBytes > 0) {
                    (activeTask.downloadedBytes.toFloat() / activeTask.totalBytes.toFloat()).coerceIn(0f, 1f)
                } else null
                if (progress != null) {
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    Text("${(progress * 100f).roundToInt()}%")
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }

            if (!activeMessage.isNullOrBlank()) {
                Text(activeMessage, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                FilledIconButton(onClick = { viewModel.openDownloadPicker(model.id) }) {
                    Icon(Icons.Filled.Download, contentDescription = "Download")
                }

                if (activeTask != null && activeTask.state != DownloadState.COMPLETED && activeTask.state != DownloadState.CANCELED && activeTask.state != DownloadState.FAILED) {
                    FilledTonalIconButton(
                        onClick = { viewModel.pauseDownload(model.id, activeTask.request.runtime) },
                        enabled = activeTask.state == DownloadState.DOWNLOADING
                    ) {
                        Icon(Icons.Filled.Pause, contentDescription = "Pause")
                    }
                    FilledTonalIconButton(
                        onClick = { viewModel.resumeDownload(model.id, activeTask.request.runtime) },
                        enabled = activeTask.state == DownloadState.PAUSED
                    ) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = "Resume")
                    }
                    FilledTonalIconButton(onClick = { viewModel.stopDownload(model.id, activeTask.request.runtime) }) {
                        Icon(Icons.Filled.Stop, contentDescription = "Stop")
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DownloadRuntimeBottomSheet(viewModel: AppViewModel, state: AppUiState) {
    val repoId = state.pendingDownloadRepoId ?: return
    val repo = state.catalog.firstOrNull { it.id == repoId } ?: return
    val runtime = state.downloadRuntimeSelection ?: return
    val option = repo.runtimeOptions[runtime] ?: return
    val variants = modelVariantsForUi(option, runtime)
    val selectedVariant = state.downloadVariantSelectionByRuntime[runtime]
        ?: variants.firstOrNull()?.variantId

    ModalBottomSheet(onDismissRequest = { viewModel.closeDownloadPicker() }) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Download Model", style = MaterialTheme.typography.titleLarge)
            Text(repo.repo)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                repo.supportedRuntimes.forEach { runtimeOption ->
                    AssistChip(
                        onClick = { viewModel.selectDownloadRuntime(runtimeOption) },
                        label = { Text(runtimeOption.label()) }
                    )
                }
            }

            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                variants.forEach { variant ->
                    AssistChip(
                        onClick = { viewModel.selectDownloadVariant(runtime, variant.variantId) },
                        label = {
                            Text(
                                if (selectedVariant == variant.variantId) {
                                    "${variant.variantId} *"
                                } else {
                                    variant.variantId
                                }
                            )
                        }
                    )
                }
            }

            Button(
                onClick = { viewModel.confirmDownloadSelection() },
                enabled = selectedVariant != null,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Download ${runtime.label()}")
            }
        }
    }
}

@Composable
private fun RuntimeBadge(runtime: ModelRuntime) {
    val containerColor = when (runtime) {
        ModelRuntime.LLAMA_CPP_GGUF -> MaterialTheme.colorScheme.tertiaryContainer
        ModelRuntime.ONNX -> MaterialTheme.colorScheme.secondaryContainer
    }
    val contentColor = when (runtime) {
        ModelRuntime.LLAMA_CPP_GGUF -> MaterialTheme.colorScheme.onTertiaryContainer
        ModelRuntime.ONNX -> MaterialTheme.colorScheme.onSecondaryContainer
    }
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = containerColor
    ) {
        Text(
            text = runtime.label(),
            color = contentColor,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

private fun modelVariantsForUi(option: RuntimeOption, runtime: ModelRuntime): List<ModelVariant> {
    val invalidTags = setOf("", "UNKNOWN", "ERROR", "N/A", "NA", "NULL", "NONE")
    val seen = mutableSetOf<String>()
    val cleaned = option.variants.mapNotNull { variant ->
        val raw = variant.variantId.trim()
        val normalized = raw.uppercase()
        if (normalized in invalidTags) return@mapNotNull null
        if (runtime == ModelRuntime.LLAMA_CPP_GGUF && normalized == "GGUF") return@mapNotNull null
        if (!seen.add(normalized)) return@mapNotNull null
        variant
    }
    if (cleaned.isNotEmpty()) return cleaned

    val fallback = option.variants.firstOrNull {
        val normalized = it.variantId.trim().uppercase()
        normalized.isNotBlank() && normalized !in invalidTags
    }
    return fallback?.let(::listOf) ?: emptyList()
}
